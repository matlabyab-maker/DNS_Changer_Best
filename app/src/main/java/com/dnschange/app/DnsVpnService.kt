package com.dnschange.app

import android.app.*
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal IPv4/UDP DNS forwarder.
 * It intercepts DNS packets routed to the selected resolvers and forwards them unchanged.
 * This first build intentionally does not tunnel arbitrary application traffic.
 */
class DnsVpnService : VpnService() {
    companion object {
        const val PREF_ACTIVE = "vpn_active"
        const val ACTION_STOP = "com.dnschange.app.action.STOP_DNS_VPN"
        const val PREF_HEARTBEAT = "vpn_heartbeat_elapsed"
    }
    private val statePrefs by lazy { getSharedPreferences("dnschange", MODE_PRIVATE) }
    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var first = "8.8.8.8"
    private var second = "8.8.4.4"
    private var ipv6Enabled = false
    private var extraIpv6 = emptyList<String>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatTask = object : Runnable {
        override fun run() {
            val workerAlive = running.get() && tun != null && worker?.isAlive == true
            if (workerAlive) {
                statePrefs.edit().putLong(PREF_HEARTBEAT, android.os.SystemClock.elapsedRealtime()).apply()
                mainHandler.postDelayed(this, 8_000L)
            } else if (statePrefs.getBoolean(PREF_ACTIVE, false)) {
                statePrefs.edit().putBoolean(PREF_ACTIVE, false).putLong(PREF_HEARTBEAT, 0L).apply()
                stopTunnel()
                stopForeground(true)
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            statePrefs.edit().putBoolean(PREF_ACTIVE, false).putLong(PREF_HEARTBEAT, 0L).commit()
            mainHandler.removeCallbacks(heartbeatTask)
            stopForeground(true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        first = intent?.getStringExtra("dns1") ?: first
        second = intent?.getStringExtra("dns2") ?: second
        ipv6Enabled = intent?.getBooleanExtra("ipv6_enabled", false) ?: false
        extraIpv6 = if (ipv6Enabled) intent?.getStringArrayListExtra("ipv6_dns")?.filter { it.contains(":") } ?: emptyList() else emptyList()
        startForeground(31, notification())
        stopTunnel()
        try {
            val builder = Builder()
                .setSession("DNStow")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addDnsServer(first)
                .addDnsServer(second)
            if (first.contains(":")) builder.addRoute(first, 128) else builder.addRoute(first, 32)
            if (second.contains(":")) builder.addRoute(second, 128) else builder.addRoute(second, 32)
            if (ipv6Enabled && (first.contains(":") || second.contains(":") || extraIpv6.isNotEmpty())) {
                builder.addAddress("fd00:1111:2222::1", 128)
                extraIpv6.distinct().forEach { address ->
                    try { builder.addDnsServer(address).addRoute(address, 128) } catch (_: Exception) {}
                }
            }
            tun = builder.establish()
            if (tun == null) throw IllegalStateException("VPN interface could not be established")
            running.set(true)
            worker = Thread { loop() }.also { it.name = "DNStow-DNS-Worker"; it.start() }
            statePrefs.edit().putBoolean(PREF_ACTIVE, true)
                .putLong(PREF_HEARTBEAT, android.os.SystemClock.elapsedRealtime()).commit()
            mainHandler.removeCallbacks(heartbeatTask)
            mainHandler.postDelayed(heartbeatTask, 8_000L)
        } catch (e: Exception) {
            statePrefs.edit().putBoolean(PREF_ACTIVE, false).putLong(PREF_HEARTBEAT, 0L).apply()
            mainHandler.removeCallbacks(heartbeatTask)
            stopTunnel()
            stopForeground(true)
            stopSelf()
        }
        // Do not silently resurrect a VPN after Android has killed the process.
        return START_NOT_STICKY
    }

    private fun loop() {
        val fd = tun ?: return
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                FileOutputStream(fd.fileDescriptor).use { output ->
                    val packet = ByteArray(32767)
                    while (running.get()) {
                        val n = try { input.read(packet) } catch (_: java.io.IOException) { break }
                        if (n <= 0) continue
                        try {
                            val response = handle(packet.copyOf(n))
                            if (response != null && running.get()) synchronized(output) { output.write(response) }
                        } catch (_: Exception) {
                            // One failed DNS request must not terminate the packet worker.
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // TUN descriptor failure is terminal; heartbeat watchdog clears the active state.
        } finally {
            if (running.get()) {
                running.set(false)
                statePrefs.edit().putBoolean(PREF_ACTIVE, false).putLong(PREF_HEARTBEAT, 0L).apply()
                mainHandler.post { stopForeground(true); stopSelf() }
            }
        }
    }

    private fun handle(p: ByteArray): ByteArray? {
        if (p.isEmpty()) return null
        val version = (p[0].toInt() ushr 4) and 0x0F
        if (version == 6) return handleIpv6(p)
        if (version != 4 || p.size < 28) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p.size < ihl + 8 || p[9].toInt() != 17) return null // UDP only
        val src = p.copyOfRange(12,16)
        val dst = p.copyOfRange(16,20)
        val destIp = InetAddress.getByAddress(dst).hostAddress
        if (destIp != first && destIp != second) return null
        val udpLen = u16(p,ihl+4)
        if (udpLen < 8 || ihl + udpLen > p.size) return null
        val srcPort = u16(p,ihl)
        val dstPort = u16(p,ihl+2)
        if (dstPort != 53) return null
        val query = p.copyOfRange(ihl+8, ihl+udpLen)
        val answer = DatagramSocket().use { sock ->
            protect(sock)
            sock.soTimeout=5000
            sock.send(DatagramPacket(query,query.size,InetAddress.getByName(destIp),53))
            val buf=ByteArray(4096)
            val dp=DatagramPacket(buf,buf.size)
            sock.receive(dp)
            dp.data.copyOf(dp.length)
        }
        return buildIpv4Udp(dst,src,dstPort,srcPort,answer)
    }

    /** Handles plain IPv6/UDP DNS packets addressed to one of the selected IPv6 resolvers. */
    private fun handleIpv6(p: ByteArray): ByteArray? {
        if (p.size < 48 || !ipv6Enabled) return null
        val nextHeader = p[6].toInt() and 255
        if (nextHeader != 17) return null // UDP; extension headers are intentionally not interpreted
        val payloadLength = u16(p, 4)
        if (payloadLength < 8 || 40 + payloadLength > p.size) return null
        val src = p.copyOfRange(8, 24)
        val dst = p.copyOfRange(24, 40)
        val destIp = InetAddress.getByAddress(dst).hostAddress.substringBefore("%")
        val allowed = (listOf(first, second).filter { it.contains(":") } + extraIpv6).map {
            try { InetAddress.getByName(it).hostAddress.substringBefore("%") } catch (_: Exception) { it }
        }.toSet()
        if (destIp !in allowed) return null
        val srcPort = u16(p, 40)
        val dstPort = u16(p, 42)
        val udpLength = u16(p, 44)
        if (dstPort != 53 || udpLength < 8 || 40 + udpLength > p.size) return null
        val query = p.copyOfRange(48, 40 + udpLength)
        val answer = DatagramSocket().use { sock ->
            protect(sock)
            sock.soTimeout = 5000
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(destIp), 53))
            val buffer = ByteArray(4096)
            val response = DatagramPacket(buffer, buffer.size)
            sock.receive(response)
            response.data.copyOf(response.length)
        }
        return buildIpv6Udp(dst, src, dstPort, srcPort, answer)
    }

    private fun buildIpv6Udp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, data: ByteArray): ByteArray {
        val udpLength = 8 + data.size
        val out = ByteArray(40 + udpLength)
        out[0] = 0x60
        put16(out, 4, udpLength)
        out[6] = 17
        out[7] = 64
        System.arraycopy(src, 0, out, 8, 16)
        System.arraycopy(dst, 0, out, 24, 16)
        put16(out, 40, srcPort)
        put16(out, 42, dstPort)
        put16(out, 44, udpLength)
        System.arraycopy(data, 0, out, 48, data.size)
        put16(out, 46, ipv6UdpChecksum(src, dst, out, 40, udpLength))
        return out
    }

    private fun ipv6UdpChecksum(src: ByteArray, dst: ByteArray, packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        fun add(bytes: ByteArray, start: Int, count: Int) {
            var i = start
            while (i < start + count) {
                val value = ((bytes[i].toInt() and 255) shl 8) or
                    (if (i + 1 < start + count) bytes[i + 1].toInt() and 255 else 0)
                sum += value
                i += 2
            }
        }
        add(src, 0, src.size); add(dst, 0, dst.size)
        sum += (length ushr 16).toLong() + (length and 0xffff).toLong() + 17L
        add(packet, offset, length)
        while ((sum shr 16) > 0) sum = (sum and 0xffff) + (sum shr 16)
        val result = sum.inv().toInt() and 0xffff
        return if (result == 0) 0xffff else result
    }

    private fun buildIpv4Udp(src:ByteArray,dst:ByteArray,srcPort:Int,dstPort:Int,data:ByteArray):ByteArray {
        val total=20+8+data.size
        val out=ByteArray(total)
        out[0]=0x45; out[1]=0
        put16(out,2,total); put16(out,4,0); put16(out,6,0x4000)
        out[8]=64; out[9]=17
        System.arraycopy(src,0,out,12,4); System.arraycopy(dst,0,out,16,4)
        put16(out,10,checksum(out,0,20))
        put16(out,20,srcPort); put16(out,22,dstPort); put16(out,24,8+data.size)
        System.arraycopy(data,0,out,28,data.size)
        return out
    }
    private fun checksum(b:ByteArray,off:Int,len:Int):Int {
        var sum=0L; var i=off
        while(i<off+len) { val v=((b[i].toInt() and 255) shl 8) or (if(i+1<off+len) b[i+1].toInt() and 255 else 0); sum+=v; i+=2 }
        while((sum shr 16)>0) sum=(sum and 0xffff)+(sum shr 16)
        return sum.inv().toInt() and 0xffff
    }
    private fun u16(b:ByteArray,i:Int)=((b[i].toInt() and 255) shl 8) or (b[i+1].toInt() and 255)
    private fun put16(b:ByteArray,i:Int,v:Int) { b[i]=(v shr 8).toByte(); b[i+1]=v.toByte() }

    private fun notification():Notification {
        val channel="dnschange_vpn"
        if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"DNStow VPN",NotificationManager.IMPORTANCE_LOW))
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,channel) else Notification.Builder(this)
        return builder.setContentTitle("DNStow").setContentText("Local DNS forwarding is active")
            .setSmallIcon(android.R.drawable.stat_sys_warning).build()
    }
    private fun stopTunnel() {
        running.set(false)
        try { tun?.close() } catch (_:Exception) {}
        tun=null
        worker?.interrupt()
    }
    override fun onDestroy() {
        stopTunnel()
        mainHandler.removeCallbacks(heartbeatTask)
        statePrefs.edit().putBoolean(PREF_ACTIVE, false).putLong(PREF_HEARTBEAT, 0L).apply()
        stopForeground(true)
        super.onDestroy()
    }
    override fun onRevoke() {
        stopTunnel()
        mainHandler.removeCallbacks(heartbeatTask)
        statePrefs.edit().putBoolean(PREF_ACTIVE, false).putLong(PREF_HEARTBEAT, 0L).apply()
        stopSelf()
        super.onRevoke()
    }
}
