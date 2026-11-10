package com.dnschange.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var dns1: EditText
    private lateinit var dns2: EditText
    private lateinit var status: TextView
    private lateinit var toggleButton: Button
    private lateinit var ipv6Toggle: CheckBox
    private lateinit var ipv6Results: LinearLayout
    private lateinit var ipv6Search: EditText
    private lateinit var ipv6Count: Spinner
    private lateinit var ipv6Status: TextView
    private lateinit var ipv4Search: EditText
    private lateinit var ipv4Results: LinearLayout
    private lateinit var ipv4Status: TextView
    private lateinit var ipv4ProbeStatus: TextView
    private lateinit var ipv6ProbeStatus: TextView
    private val discoveredIpv4 = linkedSetOf<String>()
    private val discoveredIpv6 = linkedSetOf<String>()
    // Online country discoveries are kept separately from curated resolver groups.
    private val discoveredCountryDns = linkedMapOf<String, LinkedHashSet<String>>()
    private val responsiveIpv4 = linkedSetOf<String>()
    private val responsiveIpv6 = linkedSetOf<String>()
    @Volatile private var discoveryLoading = false
    @Volatile private var probingIpv4 = false
    @Volatile private var probingIpv6 = false
    private val selectedIpv6 = linkedSetOf<String>()
    private val importedResolvers = mutableListOf<Resolver>()
    private val savedPairs = mutableListOf<Resolver>()
    private val newDataTabs = linkedSetOf<Int>()
    // One softly randomized color per IPv4 series; the same series keeps its color across renders.
    private val dnsSeriesColors = linkedMapOf<String, Int>()
    private val tabButtons = mutableListOf<TextView>()
    private var savedPairsContainer: LinearLayout? = null
    private val blinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val vpnUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val vpnUiWatchdog = object : Runnable {
        override fun run() {
            if (::toggleButton.isInitialized) syncVpnUiState()
            vpnUiHandler.postDelayed(this, 10_000L)
        }
    }
    private var blinkOn = true
    private lateinit var tabContent: FrameLayout
    private lateinit var countryGridContainer: LinearLayout
    private var targetField = 1
    private var vpnEnabled = false
    private val prefs by lazy { getSharedPreferences("dnschange", MODE_PRIVATE) }

    data class Resolver(val name: String, val first: String, val second: String, val note: String = "")

    // Only China, the United States, and Iran have dedicated country windows.
    private val countries = linkedMapOf(
        "🇨🇳 چین" to listOf(
            Resolver("Alibaba AliDNS", "223.5.5.5", "223.6.6.6", "ارائه‌دهنده چینی؛ آدرس‌های رسمی عمومی"),
            Resolver("Tencent DNSPod", "119.29.29.29", "182.254.116.116", "ارائه‌دهنده چینی")
        ),
        "🇺🇸 آمریکا" to listOf(
            Resolver("Google Public DNS", "8.8.8.8", "8.8.4.4", "شبکه Anycast جهانی"),
            Resolver("Cloudflare", "1.1.1.1", "1.0.0.1", "شبکه Anycast جهانی"),
            Resolver("OpenDNS", "208.67.222.222", "208.67.220.220", "Cisco OpenDNS"),
            Resolver("Quad9", "9.9.9.9", "149.112.112.112", "شبکه جهانی")
        ),
        "🇮🇷 ایران" to emptyList()
    )

    // Retain the previous non-window country seeds in the global search catalog.
    private val searchOnlyCountrySeeds = linkedMapOf(
        "🇨🇦 کانادا" to listOf(
            Resolver("CIRA Canadian Shield Private", "149.112.121.10", "149.112.122.10", "DNS بدون فیلتر محتوایی"),
            Resolver("CIRA Canadian Shield Protected", "149.112.121.20", "149.112.122.20", "مسدودسازی بدافزار و فیشینگ"),
            Resolver("CIRA Canadian Shield Family", "149.112.121.30", "149.112.122.30", "فیلتر خانوادگی")
        ),
        "🇫🇷 فرانسه" to listOf(
            Resolver("FDN ns0", "80.67.169.12", "80.67.169.40", "ارائه‌دهنده فرانسوی"),
            Resolver("Aquilenet", "185.233.100.100", "185.233.100.101", "ارائه‌دهنده فرانسوی")
        ),
        "🇬🇧 انگلیس" to listOf(
            Resolver("OpenDNS", "208.67.222.222", "208.67.220.220", "شبکه جهانی"),
            Resolver("Google Public DNS", "8.8.8.8", "8.8.4.4", "شبکه Anycast جهانی")
        )
    )

    // This map controls the three dedicated windows and their country-specific feeds.
    private val countryCodes = linkedMapOf(
        "🇨🇳 چین" to "CN",
        "🇺🇸 آمریکا" to "US",
        "🇮🇷 ایران" to "IR"
    )

    // Curated IPv6 seeds supplement the independently downloaded online resolver lists.
    private val ipv6Resolvers = listOf(
        Resolver("Google Public DNS", "2001:4860:4860::8888", "2001:4860:4860::8844", "IPv6 عمومی گوگل"),
        Resolver("Cloudflare", "2606:4700:4700::1111", "2606:4700:4700::1001", "IPv6 عمومی کلادفلر"),
        Resolver("Quad9", "2620:fe::fe", "2620:fe::9", "DNS امن Quad9"),
        Resolver("OpenDNS", "2620:119:35::35", "2620:119:53::53", "Cisco OpenDNS"),
        Resolver("AdGuard DNS", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff", "مسدودسازی تبلیغات و ردیاب‌ها"),
        Resolver("DNS.SB", "2a09::", "2a09:1::", "DNS عمومی"),
        Resolver("Cloudflare Family", "2606:4700:4700::1112", "2606:4700:4700::1002", "فیلتر بدافزار و محتوای بزرگسال"),
        Resolver("Mullvad DNS", "2a07:e340::1", "2a07:e340::2", "DNS عمومی با تمرکز بر حریم خصوصی"),
        Resolver("DNS0", "2a0f:fc80::", "2a0f:fc80::1", "DNS عمومی اروپایی")
    )

    private val publicDns = listOf(
        Resolver("Google", "8.8.8.8", "8.8.4.4"),
        Resolver("Cloudflare", "1.1.1.1", "1.0.0.1"),
        Resolver("Quad9", "9.9.9.9", "149.112.112.112"),
        Resolver("OpenDNS", "208.67.222.222", "208.67.220.220"),
        Resolver("Comodo Secure DNS", "8.26.56.26", "8.20.247.20"),
        Resolver("AdGuard DNS", "94.140.14.14", "94.140.15.15"),
        Resolver("AliDNS", "223.5.5.5", "223.6.6.6"),
        Resolver("DNSPod", "119.29.29.29", "182.254.116.116"),
        Resolver("CIRA Canadian Shield", "149.112.121.10", "149.112.122.10"),
        Resolver("FDN France", "80.67.169.12", "80.67.169.40")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restore()
        syncVpnUiState()
        refreshPublicDns()
    }

    override fun onResume() {
        super.onResume()
        if (::toggleButton.isInitialized) syncVpnUiState()
        vpnUiHandler.removeCallbacks(vpnUiWatchdog)
        vpnUiHandler.postDelayed(vpnUiWatchdog, 10_000L)
    }

    override fun onPause() {
        vpnUiHandler.removeCallbacks(vpnUiWatchdog)
        super.onPause()
    }

    private fun syncVpnUiState() {
        val activeFlag = prefs.getBoolean(DnsVpnService.PREF_ACTIVE, false)
        val heartbeat = prefs.getLong(DnsVpnService.PREF_HEARTBEAT, 0L)
        val heartbeatAge = android.os.SystemClock.elapsedRealtime() - heartbeat
        val heartbeatFresh = heartbeat > 0L && heartbeatAge in 0L..30_000L
        vpnEnabled = activeFlag && heartbeatFresh
        if (activeFlag && !heartbeatFresh) {
            prefs.edit().putBoolean(DnsVpnService.PREF_ACTIVE, false).apply()
        }
        if (::toggleButton.isInitialized) updateToggleAppearance()
        if (::status.isInitialized) {
            status.text = if (vpnEnabled) "وضعیت: روشن" else "وضعیت: خاموش"
            status.setTextColor(if (vpnEnabled) 0xFF765700.toInt() else 0xFF42536A.toInt())
            status.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (vpnEnabled) 0xFFFFF2C2.toInt() else 0xFFF0F3F8.toInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), if (vpnEnabled) 0xFFD6A900.toInt() else 0xFFB8C5D5.toInt())
            }
        }
    }

    /** Update the main toggle color and outlined edge to reflect the actual VPN state. */
    private fun updateToggleAppearance() {
        if (!::toggleButton.isInitialized) return
        val activeYellow = 0xFFFFD54F.toInt()
        val inactiveBlue = 0xFF1976D2.toInt()
        val mainText = if (vpnEnabled) "خاموش کردن DNS" else "روشن کردن DNS"
        toggleButton.text = "On : Yellow  |  Off : Blue\n$mainText"
        toggleButton.textSize = 10f
        toggleButton.gravity = Gravity.CENTER
        toggleButton.backgroundTintList = null
        toggleButton.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (vpnEnabled) activeYellow else inactiveBlue)
            cornerRadius = dp(9).toFloat()
            setStroke(dp(4), if (vpnEnabled) 0xFF9A7100.toInt() else 0xFF123F78.toInt())
        }
        toggleButton.setTextColor(if (vpnEnabled) 0xFF302400.toInt() else 0xFFFFFFFF.toInt())
        toggleButton.elevation = if (vpnEnabled) dp(6).toFloat() else dp(2).toFloat()
    }

    /** Fixed header + tab host: the activity itself never scrolls. Only the selected tab scrolls. */
    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(3))
            setBackgroundColor(0xFFF4F6FA.toInt())
        }
        setContentView(page)

        page.addView(TextView(this).apply {
            text = "DNStow"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFF152238.toInt())
            setPadding(0, 0, 0, dp(2))
        }, LinearLayout.LayoutParams(-1, -2))

        val dnsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val dnsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dns1 = edit("DNS 1").apply { setText("8.8.8.8") }
        dns2 = edit("DNS 2").apply { setText("8.8.4.4") }
        dnsRow.addView(dns1, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(5) })
        dnsRow.addView(dns2, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(5) })
        dnsCard.addView(dnsRow)

        // Main activation button and its live status sit side by side.
        val activationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toggleButton = Button(this).apply {
            text = "OFF  |  روشن کردن DNS"
            textSize = 15f
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { toggleDns() }
        }
        updateToggleAppearance()
        activationRow.addView(toggleButton, LinearLayout.LayoutParams(0, dp(48), 4f).apply { marginEnd = dp(5) })
        status = TextView(this).apply {
            text = "وضعیت: خاموش"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF42536A.toInt())
            setPadding(dp(3), dp(4), dp(3), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF0F3F8.toInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), 0xFFB8C5D5.toInt())
            }
        }
        activationRow.addView(status, LinearLayout.LayoutParams(0, dp(48), 1.35f))
        dnsCard.addView(activationRow, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(2) })

        // DNS suggestion and optional IPv6 controls share one compact row.
        val dnsOptionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val suggestButton = Button(this).apply {
            text = "پیشنهاد DNS"
            textSize = 10f
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(2), 0, dp(2), 0)
            setOnClickListener { suggestSecondDns() }
        }
        dnsOptionsRow.addView(suggestButton, LinearLayout.LayoutParams(0, dp(48), 0.9f).apply {
            marginEnd = dp(3)
        })

        ipv6Toggle = CheckBox(this).apply {
            text = "IPv6 اختیاری"
            textSize = 11f
            isChecked = prefs.getBoolean("ipv6_enabled", false)
            setTextColor(0xFF1B3554.toInt())
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("ipv6_enabled", checked).apply()
                toast(if (checked) "IPv6 در سرویس DNS فعال می‌شود." else "IPv6 غیرفعال شد.")
            }
        }
        dnsOptionsRow.addView(ipv6Toggle, LinearLayout.LayoutParams(0, dp(48), 1.1f).apply {
            marginStart = dp(3)
        })
        dnsCard.addView(dnsOptionsRow, LinearLayout.LayoutParams(-1, dp(48)).apply {
            topMargin = dp(2)
        })

        dns1.setOnClickListener { selectTarget(1) }
        dns2.setOnClickListener { selectTarget(2) }
        dns1.setOnFocusChangeListener { _, focused ->
            if (focused) selectTarget(1)
        }
        dns2.setOnFocusChangeListener { _, focused ->
            if (focused) selectTarget(2)
        }
        selectTarget(1)

        page.addView(dnsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })

        val researchButton = Button(this).apply {
            text = "Research  |  جست‌وجوی تازه DNS و IPv6"
            textSize = 11f
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener {
                if (discoveryLoading) toast("جست‌وجوی قبلی هنوز در حال اجراست.")
                else {
                    toast("جست‌وجوی تازه DNS در حال انجام است…")
                    refreshPublicDns(force = true)
                }
            }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun compactAction(label: String, weight: Float, click: () -> Unit, first: Boolean = false): Button {
            val button = Button(this).apply {
                text = label
                textSize = 16f
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(1), 0, dp(1), 0)
                setOnClickListener { click() }
            }
            actionRow.addView(button, LinearLayout.LayoutParams(0, dp(48), weight).apply {
                if (!first) marginStart = dp(2)
                marginEnd = dp(1)
            })
            return button
        }
        researchButton.textSize = 16f
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            researchButton.setAutoSizeTextTypeUniformWithConfiguration(10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        researchButton.minimumHeight = 0
        researchButton.minHeight = 0
        researchButton.text = "Research"
        actionRow.addView(researchButton, LinearLayout.LayoutParams(0, dp(48), 1.05f).apply { marginEnd = dp(1) })
        compactAction("⌕ جستجو", 0.82f, { globalSearchDialog() })
        compactAction("ⓘ Info", 0.58f, { showHowToWorkDialog() })
        compactAction("ورود متن", 0.72f, { importDnsListDialog() })
        compactAction("ورود TXT", 0.66f, { openTxtImport() })
        page.addView(actionRow, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(2) })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFE3EAF3.toInt())
        }
        page.addView(tabs, LinearLayout.LayoutParams(-1, dp(34)))
        tabContent = FrameLayout(this)
        page.addView(tabContent, LinearLayout.LayoutParams(-1, 0, 1f))

        val labels = listOf("کشورها", "DNS عمومی", "IPv6", "ذخیره‌شده", "راهنما")
        labels.forEachIndexed { index, label ->
            val button = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(0xFF1B3554.toInt())
                setPadding(dp(3), 0, dp(3), 0)
                setOnClickListener { newDataTabs.remove(index); updateTabBadges(labels); showTab(index, tabs, labels) }
            }
            tabButtons.add(button)
            tabs.addView(button, LinearLayout.LayoutParams(0, -1, 1f))
        }
        blinkHandler.post(object : Runnable {
            override fun run() {
                blinkOn = !blinkOn
                updateTabBadges(labels)
                blinkHandler.postDelayed(this, 550)
            }
        })
        showTab(0, tabs, labels)
    }

    private fun showTab(index: Int, tabs: LinearLayout, labels: List<String>) {
        for (i in 0 until tabs.childCount) {
            val selected = i == index
            tabs.getChildAt(i).setBackgroundColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFE3EAF3.toInt())
            (tabs.getChildAt(i) as TextView).setTypeface(null, if (selected) 1 else 0)
        }
        tabContent.removeAllViews()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(3), dp(1), dp(4))
        }
        scroll.addView(content)
        tabContent.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        when (index) {
            0 -> buildCountriesTab(content)
            1 -> buildPublicTab(content)
            2 -> buildIpv6Tab(content)
            3 -> buildSavedTab(content)
            else -> buildInfoTab(content)
        }
    }

    private fun updateTabBadges(labels: List<String>) {
        if (tabButtons.size != labels.size) return
        tabButtons.forEachIndexed { i, button ->
            val marked = newDataTabs.contains(i)
            button.text = labels[i] + if (marked && blinkOn) "  ⬇" else ""
            button.setTextColor(if (marked && blinkOn) 0xFFE05A00.toInt() else 0xFF1B3554.toInt())
        }
    }

    private fun buildCountriesTab(content: LinearLayout) {
        content.addView(TextView(this).apply {
            text = "کشورها: چین، آمریکا و ایران  •  لمس DNS برای انتخاب در کادر بالایی  •  Sort برای مرتب‌سازی"
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(0xFF526176.toInt())
            maxLines = 1
        }, LinearLayout.LayoutParams(-1, dp(18)))
        countryGridContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(countryGridContainer, LinearLayout.LayoutParams(-1, -2))
        renderCountryGrid()
        tabContent.post { if (::countryGridContainer.isInitialized) renderCountryGrid() }
    }

    private fun renderCountryGrid() {
        if (!::countryGridContainer.isInitialized) return
        countryGridContainer.removeAllViews()
        val cards = mutableListOf<View>()
        // Only the three configured countries are rendered as dedicated windows.
        countries.keys.forEach { country ->
            val curated = countries[country].orEmpty()
            val onlineAddresses = discoveredCountryDns[country].orEmpty().toList()
            val onlineResolvers = onlineAddresses.mapIndexed { index, address ->
                Resolver("DNS کشف‌شده ${index + 1}", address, "", "کشور: ${countryCodes[country].orEmpty()}؛ منبع: فهرست آنلاین کشوری؛ پاسخ‌گویی را آزمایش کنید")
            }
            val allAddresses = (curated + onlineResolvers).flatMap { listOf(it.first, it.second) }
                .filter { it.isNotBlank() }.distinct()
            val count = allAddresses.size
            val countryCode = countryCodes[country].orEmpty()
            // Keep the complete country heading on one horizontal line; the card dimensions stay unchanged.
            val waiting = if (count == 0) "  ┆  در انتظار" else ""
            val threshold = if (count >= 100) "  ┆  ✓۱۰۰+" else if (count > 0) "  ┆  ${100 - count} تا ۱۰۰" else ""
            cards.add(resolverListCard("$country [$countryCode]  ┆  $count DNS$threshold$waiting", curated + onlineResolvers,
                countryWindowHeight(), dividerBeforeIndex = curated.size.takeIf { onlineResolvers.isNotEmpty() }))
        }
        if (importedResolvers.isNotEmpty()) cards.add(resolverListCard("فهرست DNSهای واردشده", importedResolvers, countryWindowHeight()))
        addThreeColumnGrid(countryGridContainer, cards)
    }

    private fun buildPublicTab(content: LinearLayout) {
        panel(content, "DNSهای عمومی") {
            addView(TextView(this@MainActivity).apply {
                text = "ابتدا DNS 1 یا DNS 2 را از بالای صفحه انتخاب کنید؛ سپس آدرس دلخواه را لمس کنید."
                textSize = 13f
                setTextColor(0xFF526176.toInt())
            })
        }
        val publicResolversWithCountry = (publicDns + importedResolvers).map { resolver ->
            val addresses = listOf(resolver.first, resolver.second).filter { it.isNotBlank() }
            val existingCountry = Regex("کشور\\s*:\\s*([^|]+)").find(resolver.note)?.groupValues?.getOrNull(1)?.trim()
            val country = existingCountry?.takeIf { it.isNotBlank() }
                ?: addresses.map { countryLabelForAddress(it) }.distinct().joinToString("، ")
            resolver.copy(note = listOf(resolver.note, "کشور: $country").filter { it.isNotBlank() && !it.startsWith("کشور:") }.plus("کشور: $country").joinToString(" | "))
        }
        content.addView(resolverListCard("DNSهای شناخته‌شده و واردشده", publicResolversWithCountry, dp(260)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        panel(content, "جست‌وجوی آنلاین IPv4 (بیش از ۵۰ نتیجه)") {
            addView(TextView(this@MainActivity).apply {
                text = "فهرست عمومی از منبع آنلاین دریافت می‌شود. نشانی‌های تکراری و مواردی که قالب IP معتبر ندارند حذف می‌شوند. دسترسی‌پذیری هر DNS ممکن است با شبکه شما متفاوت باشد."
                textSize = 11f
                setTextColor(0xFF64748B.toInt())
            })
            ipv4Search = EditText(this@MainActivity).apply {
                hint = "جست‌وجوی بخشی از نشانی IPv4"
                setSingleLine(true)
                textSize = 13f
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderIpv4Results(s?.toString().orEmpty()) }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            addView(ipv4Search, LinearLayout.LayoutParams(-1, dp(44)))
            addView(Button(this@MainActivity).apply {
                text = "جست‌وجو / به‌روزرسانی فهرست IPv4"
                isAllCaps = false
                setOnClickListener { refreshPublicDns(force = true) }
            }, LinearLayout.LayoutParams(-1, dp(42)))
            addView(Button(this@MainActivity).apply {
                text = "آزمایش پاسخ‌گویی ۱۲۰ DNS اول IPv4"
                isAllCaps = false
                setOnClickListener { probeDnsServers(4) }
            }, LinearLayout.LayoutParams(-1, dp(42)))
            ipv4Status = TextView(this@MainActivity).apply { text = if (discoveredIpv4.isEmpty()) "در انتظار دریافت فهرست آنلاین…" else "IPv4: ${discoveredIpv4.size} آدرس یکتا دریافت شد."; textSize = 11f; setTextColor(0xFF526176.toInt()) }
            addView(ipv4Status)
            ipv4ProbeStatus = TextView(this@MainActivity).apply { text = "وضعیت پاسخ‌گویی هنوز آزمایش نشده است."; textSize = 11f; setTextColor(0xFF526176.toInt()) }
            addView(ipv4ProbeStatus)
        }
        ipv4Results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ipv4Results, LinearLayout.LayoutParams(-1, -2))
        renderIpv4Results("")
    }

    /** Resolve the country label for a DNS address using the country-specific feeds and seed lists. */
    private fun countryLabelForAddress(address: String): String {
        val matchedCountries = (countries.keys + searchOnlyCountrySeeds.keys + discoveredCountryDns.keys).distinct().filter { country ->
            (countries[country].orEmpty() + searchOnlyCountrySeeds[country].orEmpty())
                .any { address == it.first || address == it.second } ||
                discoveredCountryDns[country]?.contains(address) == true
        }
        return matchedCountries.map { it.substringAfterLast(" ").ifBlank { it } }
            .distinct().takeIf { it.isNotEmpty() }?.joinToString("، ")
            ?: if (address in setOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220"))
                "جهانی (Anycast؛ چندکشوری)" else "نامشخص در فهرست کشورها"
    }

    private fun renderIpv4Results(query: String, requestedLimit: Int = 100) {
        if (!::ipv4Results.isInitialized) return
        ipv4Results.removeAllViews()
        val q = query.trim().lowercase()
        val knownIpv4 = (publicDns + countries.values.flatten()).flatMap { listOf(it.first, it.second) }.toSet()
        val allMatches = discoveredIpv4.filter { it !in knownIpv4 && (q.isEmpty() || it.contains(q)) }
        val matches = allMatches.take(requestedLimit)
        matches.forEachIndexed { index, address ->
            val probeMark = if (responsiveIpv4.contains(address)) "  ✓ پاسخ‌گو" else "  • آزمایش‌نشده"
            val countryLabel = countryLabelForAddress(address)
            ipv4Results.addView(dnsAddressButton("${index + 1}.  $address  |  کشور: $countryLabel$probeMark", address), LinearLayout.LayoutParams(-1, dp(44)))
        }
        if (matches.isEmpty()) ipv4Results.addView(TextView(this).apply {
            text = if (discoveryLoading) "در حال دریافت و آماده‌سازی نتایج…" else "نتیجه‌ای پیدا نشد؛ دکمه به‌روزرسانی را بزنید."
            textSize = 12f; setTextColor(0xFF64748B.toInt())
        })
        if (allMatches.size > matches.size) ipv4Results.addView(Button(this).apply {
            text = "نمایش ۱۰۰ نتیجه بعدی (${matches.size} از ${allMatches.size})"
            isAllCaps = false
            setOnClickListener { renderIpv4Results(query, requestedLimit + 100) }
        }, LinearLayout.LayoutParams(-1, dp(42)))
    }

    private fun refreshPublicDns(force: Boolean = false) {
        if (discoveryLoading) return
        if (!force && discoveredIpv4.isNotEmpty() && discoveredIpv6.isNotEmpty() &&
            countryCodes.keys.all { discoveredCountryDns[it]?.isNotEmpty() == true }) return
        discoveryLoading = true
        if (::ipv4Status.isInitialized) ipv4Status.text = "در حال دریافت فهرست کامل DNS کشورها…"
        if (::ipv6Status.isInitialized) ipv6Status.text = "در حال جست‌وجوی آنلاین IPv6…"
        if (::countryGridContainer.isInitialized) renderCountryGrid()

        Thread {
            val v4 = linkedSetOf<String>()
            val v6 = linkedSetOf<String>()
            val countryResults = linkedMapOf<String, LinkedHashSet<String>>()
            val countryNamesByCode = linkedMapOf<String, String>()
            val globalSources = listOf(
                "https://public-dns.info/nameservers.csv",
                "https://raw.githubusercontent.com/pingproxies/public-dns-directory/main/resolvers/global/all.txt",
                "https://raw.githubusercontent.com/pingproxies/public-dns-directory/main/resolvers/global/trusted.txt",
                "https://raw.githubusercontent.com/blacklanternsecurity/public-dns-servers/main/nameservers.txt",
                "https://raw.githubusercontent.com/disposable/public-dns/main/json/accepted.json"
            )
            val pool = Executors.newFixedThreadPool(18)
            try {
                // PRIORITY: fetch the three visible country windows first (China, USA, Iran).
                // Publish these results immediately so the user can select DNS without waiting
                // for the slower global catalog and IPv6 feeds.
                // Supplement the countries already shown in Persian with independent feeds.
                // Fetch every feed even after 100 entries: merge all unique results, never truncate.
                val countrySources = countryCodes.flatMap { (countryName, code) ->
                    val lower = code.lowercase(java.util.Locale.ROOT)
                    listOf(
                        Triple(code, countryName, "https://raw.githubusercontent.com/pingproxies/public-dns-directory/main/resolvers/by-country/$code.txt"),
                        Triple(code, countryName, "https://cdn.jsdelivr.net/gh/pingproxies/public-dns-directory@main/resolvers/by-country/$code.txt"),
                        Triple(code, countryName, "https://public-dns.info/nameserver/$lower.txt"),
                        Triple(code, countryName, "https://public-dns.info/nameserver/$lower.csv")
                    )
                }
                val countryJobs = countrySources.map { entry -> Callable {
                    val result = fetchDnsFeed(entry.third)
                    Triple(entry.first, entry.second, result.first + result.second)
                } }
                pool.invokeAll(countryJobs, 24, TimeUnit.SECONDS).forEach { future ->
                    try {
                        val (code, countryName, addresses) = future.get()
                        val existingLabel = countryCodes.entries.firstOrNull { it.value.equals(code, true) }?.key
                        countryNamesByCode.putIfAbsent(code, existingLabel ?: countryName)
                        countryResults.getOrPut(code) { linkedSetOf() }.addAll(addresses)
                    } catch (_: Exception) { }
                }
                runOnUiThread {
                    countryResults.forEach { (code, addresses) ->
                        val label = countryCodes.entries.firstOrNull { it.value.equals(code, true) }?.key ?: code
                        val known = countries[label].orEmpty().flatMap { listOf(it.first, it.second) }
                            .filter { it.isNotBlank() }.toSet()
                        discoveredCountryDns.getOrPut(label) { linkedSetOf() }.addAll(addresses.filter { it !in known })
                    }
                    saveCountryResearch()
                    if (::countryGridContainer.isInitialized) renderCountryGrid()
                    toast("فهرست DNS چین، آمریکا و ایران در اولویت دریافت شد؛ ادامه جست‌وجوی عمومی در پس‌زمینه انجام می‌شود.")
                }

                // The metadata JSON is essential: unlike a plain global IP list, it preserves
                // the country_code and country fields so every available country gets its own card.
                val taggedFuture = pool.submit(Callable {
                    fetchCountryTaggedResolvers("https://raw.githubusercontent.com/pingproxies/public-dns-directory/main/data/resolvers.json")
                })
                val tagged = try { taggedFuture.get(12, TimeUnit.SECONDS) } catch (_: Exception) { Pair(emptyMap(), emptyMap()) }
                tagged.first.forEach { (code, addresses) ->
                    countryResults.getOrPut(code) { linkedSetOf() }.addAll(addresses)
                }
                countryNamesByCode.putAll(tagged.second)
                countryResults.values.forEach { addresses ->
                    addresses.forEach { address ->
                        if (address.contains(":")) v6.add(address) else v4.add(address)
                    }
                }

                // Independent global lists supplement the tagged country dataset.
                pool.invokeAll(globalSources.map { source -> Callable { fetchDnsFeed(source) } }, 18, TimeUnit.SECONDS)
                    .forEach { future ->
                        try {
                            val result = future.get()
                            v4.addAll(result.first)
                            v6.addAll(result.second)
                        } catch (_: Exception) { }
                    }


            } catch (_: Exception) {
                // Keep all partial results collected from feeds that completed successfully.
            } finally {
                pool.shutdownNow()
            }

            runOnUiThread {
                // Keep metadata for every country in the searchable catalog, without creating
                // additional country windows. Only CN, US, and IR exist in countryCodes.
                countryResults.forEach { (code, addresses) ->
                    val label = countryCodes.entries.firstOrNull { it.value.equals(code, true) }?.key
                        ?: countryNamesByCode[code] ?: code
                    val known = countries[label].orEmpty().flatMap { listOf(it.first, it.second) }
                        .filter { it.isNotBlank() }.toSet()
                    discoveredCountryDns.getOrPut(label) { linkedSetOf() }.addAll(addresses.filter { it !in known })
                }
                discoveredIpv4.addAll(v4)
                discoveredIpv6.addAll(v6)
                discoveryLoading = false
                saveCountryResearch()
                if (v4.isNotEmpty() || discoveredCountryDns.values.any { it.isNotEmpty() }) newDataTabs.add(0)
                if (v4.isNotEmpty()) newDataTabs.add(1)
                if (v6.isNotEmpty()) newDataTabs.add(2)
                updateTabBadges(listOf("کشورها", "DNS عمومی", "IPv6", "ذخیره‌شده", "راهنما"))
                if (::countryGridContainer.isInitialized) renderCountryGrid()
                if (::ipv4Status.isInitialized) ipv4Status.text =
                    "IPv4: ${discoveredIpv4.size} آدرس یکتای کشف‌شده از چند منبع؛ بدون محدودیت تعداد."
                if (::ipv6Status.isInitialized) ipv6Status.text =
                    "IPv6: ${discoveredIpv6.size} آدرس یکتای کشف‌شده از فهرست‌های کشوری و منابع عمومی؛ بدون محدودیت تعداد."
                if (::ipv4Results.isInitialized) renderIpv4Results(ipv4Search.text.toString())
                if (::ipv6Results.isInitialized) renderIpv6Results(ipv6Search.text.toString())
                val countryTotal = discoveredCountryDns.values.sumOf { it.size }
                val countriesWith100 = discoveredCountryDns.count { it.value.size >= 100 }
                if (countryTotal == 0 && v4.isEmpty() && v6.isEmpty()) {
                    toast("دریافت آنلاین ممکن نشد؛ اینترنت یا دسترسی به منابع DNS را بررسی کنید.")
                } else {
                    toast("پژوهش پایان یافت: $countryTotal DNS یکتای کشوری؛ پنجره‌های مستقل: چین، آمریکا و ایران؛ سایر کشورها در جست‌وجوی سراسری.")
                }
            }
        }.apply { name = "DNStow-CountryDNS-Research"; isDaemon = true; start() }
    }

    /** Reads the country-tagged master JSON and groups real resolver IPs by ISO country code. */
    private fun fetchCountryTaggedResolvers(source: String): Pair<Map<String, Set<String>>, Map<String, String>> {
        val grouped = linkedMapOf<String, LinkedHashSet<String>>()
        val names = linkedMapOf<String, String>()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DNStow-Android/2.0 country resolver research")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return Pair(grouped, names)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val rows = root.optJSONArray("resolvers") ?: root.optJSONArray("data") ?: JSONArray()
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                val ip = item.optString("ip", item.optString("address", "")).trim()
                val code = item.optString("country_code", item.optString("countryCode", ""))
                    .trim().uppercase(java.util.Locale.ROOT)
                val country = item.optString("country", item.optString("country_name", "")).trim()
                if (!code.matches(Regex("[A-Z]{2}")) || ip.isBlank()) continue
                val version = if (ip.contains(":")) 6 else 4
                if (!isPublicIp(ip, version)) continue
                grouped.getOrPut(code) { linkedSetOf() }.add(if (version == 6) ip.lowercase() else ip)
                if (country.isNotBlank()) names.putIfAbsent(code, country)
            }
        } catch (_: Exception) {
            // Country-specific TXT mirrors below still work if the JSON feed is unavailable.
        } finally {
            connection?.disconnect()
        }
        return Pair(grouped, names)
    }

    /** Downloads one feed with strict size/time limits and extracts only public literal IPs. */
    private fun fetchDnsFeed(source: String): Pair<Set<String>, Set<String>> {
        val v4 = linkedSetOf<String>()
        val v6 = linkedSetOf<String>()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 7000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DNStow-Android/1.2 (+country resolver research)")
                setRequestProperty("Accept", "text/plain, text/csv, application/json, */*")
            }
            if (connection.responseCode !in 200..299) return Pair(v4, v6)
            val body = connection.inputStream.bufferedReader().use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                var total = 0
                while (total < 12_000_000) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, 12_000_000 - total))
                    if (read <= 0) break
                    out.append(buffer, 0, read)
                    total += read
                }
                out.toString()
            }
            val ipv4Pattern = Regex("(?<![0-9.])(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?:\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])){3}(?![0-9.])")
            val ipv6Pattern = Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f:])")
            ipv4Pattern.findAll(body).forEach { match ->
                val ip = match.value
                if (isPublicIp(ip, 4)) v4.add(ip)
            }
            ipv6Pattern.findAll(body).forEach { match ->
                val ip = match.value.trimEnd(':')
                if (isPublicIp(ip, 6)) v6.add(ip.lowercase())
            }
            Regex("\\[([0-9a-fA-F:]{2,})\\](?::[0-9]{1,5})?").findAll(body).forEach { match ->
                val ip = match.groupValues[1]
                if (isPublicIp(ip, 6)) v6.add(ip.lowercase())
            }
        } catch (_: Exception) {
            // Keep results from other independent feeds.
        } finally {
            connection?.disconnect()
        }
        return Pair(v4, v6)
    }

    /** Tests real UDP/53 DNS answers for a bounded sample; discovered entries remain visible separately. */
    private fun probeDnsServers(version: Int) {
        if (version == 4 && probingIpv4 || version == 6 && probingIpv6) return
        val candidates = (if (version == 4) discoveredIpv4 else discoveredIpv6).take(120)
        if (candidates.isEmpty()) {
            toast("ابتدا فهرست آنلاین را دریافت کنید.")
            return
        }
        if (version == 4) {
            probingIpv4 = true
            if (::ipv4ProbeStatus.isInitialized) ipv4ProbeStatus.text = "در حال آزمایش پاسخ DNS از ${candidates.size} آدرس…"
        } else {
            probingIpv6 = true
            if (::ipv6ProbeStatus.isInitialized) ipv6ProbeStatus.text = "در حال آزمایش پاسخ DNS از ${candidates.size} آدرس…"
        }
        Thread {
            val pool = Executors.newFixedThreadPool(20)
            val tasks = candidates.map { ip -> Callable { ip to dnsResponds(ip) } }
            val good = linkedSetOf<String>()
            try {
                pool.invokeAll(tasks, 25, TimeUnit.SECONDS).forEach { future ->
                    try {
                        val result = future.get()
                        if (result.second) good.add(result.first)
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
            } finally {
                pool.shutdownNow()
            }
            runOnUiThread {
                if (version == 4) {
                    responsiveIpv4.clear(); responsiveIpv4.addAll(good)
                    probingIpv4 = false
                    if (::ipv4ProbeStatus.isInitialized) ipv4ProbeStatus.text = "IPv4: از ${candidates.size} آدرس آزمایش‌شده، ${good.size} DNS به پرس‌وجوی واقعی پاسخ داد."
                    if (::ipv4Results.isInitialized) renderIpv4Results(ipv4Search.text.toString())
                } else {
                    responsiveIpv6.clear(); responsiveIpv6.addAll(good)
                    probingIpv6 = false
                    if (::ipv6ProbeStatus.isInitialized) ipv6ProbeStatus.text = "IPv6: از ${candidates.size} آدرس آزمایش‌شده، ${good.size} DNS به پرس‌وجوی واقعی پاسخ داد."
                    if (::ipv6Results.isInitialized) renderIpv6Results(ipv6Search.text.toString())
                }
            }
        }.apply { name = "DNSchOne-Probe-IPv$version"; isDaemon = true; start() }
    }

    private fun dnsResponds(ip: String): Boolean {
        return try {
            val id = (System.nanoTime().toInt() and 0xffff)
            val qname = byteArrayOf(7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0)
            val query = ByteArray(12 + qname.size + 4)
            query[0] = (id shr 8).toByte(); query[1] = id.toByte()
            query[2] = 1 // recursion desired
            query[5] = 1 // one question
            System.arraycopy(qname, 0, query, 12, qname.size)
            val end = 12 + qname.size
            query[end] = 0; query[end + 1] = 1 // A record
            query[end + 2] = 0; query[end + 3] = 1 // IN class
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 1400
                val destination = InetAddress.getByName(ip)
                socket.send(java.net.DatagramPacket(query, query.size, destination, 53))
                val buffer = ByteArray(2048)
                val response = java.net.DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val responseId = (((buffer[0].toInt() and 255) shl 8) or (buffer[1].toInt() and 255))
                response.length >= 12 && responseId == id && (buffer[2].toInt() and 0x80) != 0
            }
        } catch (_: Exception) { false }
    }

    private fun isValidIp(value: String, version: Int): Boolean {
        if (value.isBlank() || value.length > 45 || value.any { it.isWhitespace() }) return false
        return try {
            val bytes = InetAddress.getByName(value).address
            if (version == 4) bytes.size == 4 && value.matches(Regex("[0-9.]+"))
            else bytes.size == 16 && value.contains(":")
        } catch (_: Exception) { false }
    }

    private fun isPublicIp(value: String, version: Int): Boolean {
        if (!isValidIp(value, version)) return false
        return try {
            val address = InetAddress.getByName(value)
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            if (version == 4) {
                val a = bytes[0].toInt() and 255
                val b = bytes[1].toInt() and 255
                // Exclude private, carrier-grade NAT, documentation and reserved ranges.
                !(a == 10 || a == 127 || a == 0 || (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 100 && b in 64..127) || (a == 192 && b == 0) ||
                    (a == 198 && (b == 18 || b == 19 || b == 51)) || (a == 203 && b == 0) || a >= 224)
            } else {
                // Public global-unicast IPv6 addresses are in 2000::/3; exclude documentation space.
                (bytes[0].toInt() and 0xE0) == 0x20 &&
                    !((bytes[0].toInt() and 255) == 0x20 && (bytes[1].toInt() and 255) == 0x01 &&
                        (bytes[2].toInt() and 255) == 0x0D && (bytes[3].toInt() and 255) == 0xB8)
            }
        } catch (_: Exception) { false }
    }

    /** Size the inner country lists so the outer country cards finish at the bottom of the main tab viewport. */
    private fun countryWindowHeight(): Int {
        val viewport = if (::tabContent.isInitialized) tabContent.height else 0
        if (viewport > 0) return (viewport - dp(112)).coerceAtLeast(dp(120))
        val screenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).toInt()
        return dp((screenDp - 390).coerceIn(150, 430))
    }

    private fun resolverListCard(title: String, resolvers: List<Resolver>, listHeight: Int, dividerBeforeIndex: Int? = null): View {
        return panelCard(title) {
            val list = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(2), dp(2), dp(2), dp(2))
            }
            var currentResolvers = if (!discoveryLoading) {
                resolvers.withIndex().sortedWith(compareBy<IndexedValue<Resolver>>(
                    { ipv4SeriesKey(it.value.first) }, { it.value.first }, { it.index }
                )).map { it.value }
            } else resolvers.toList()
            var currentSort = if (!discoveryLoading) "گروه‌بندی سری‌های مشابه" else "ترتیب اصلی"
            var visibleCount = 50
            var innerScroll: ScrollView? = null
            var moreButton: Button? = null

            fun renderItems(showOnlineDivider: Boolean) {
                val oldY = innerScroll?.scrollY ?: 0
                list.removeAllViews()
                val endIndex = minOf(visibleCount, currentResolvers.size)
                currentResolvers.take(endIndex).forEachIndexed { resolverIndex, resolver ->
                    if (showOnlineDivider && dividerBeforeIndex != null && resolverIndex == dividerBeforeIndex) {
                        list.addView(TextView(this@MainActivity).apply {
                            text = "نتایج جست‌وجوی آنلاین"
                            textSize = 9f
                            gravity = Gravity.CENTER
                            setTypeface(null, 1)
                            setTextColor(0xFF176B87.toInt())
                            setPadding(0, dp(5), 0, dp(2))
                        }, LinearLayout.LayoutParams(-1, -2))
                        list.addView(DashedDividerView(this@MainActivity), LinearLayout.LayoutParams(-1, dp(8)).apply {
                            topMargin = dp(2); bottomMargin = dp(4)
                        })
                    }
                    val item = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(2), dp(2), dp(2), dp(2))
                        setBackgroundColor(seriesColorForResolver(resolver))
                    }
                    val discoveredEntry = resolver.name.startsWith("DNS کشف", ignoreCase = true)
                    if (!discoveredEntry) {
                        item.addView(TextView(this@MainActivity).apply {
                            text = resolver.name
                            textSize = 8f
                            gravity = Gravity.CENTER
                            maxLines = 2
                            setTypeface(null, 1)
                            setTextColor(0xFF1C2B40.toInt())
                        })
                    }
                    item.addView(dnsAddressButton(if (discoveredEntry) resolver.first else "DNS اول: ${resolver.first}", resolver.first))
                    if (resolver.second.isNotBlank()) item.addView(dnsAddressButton(if (discoveredEntry) resolver.second else "DNS دوم: ${resolver.second}", resolver.second))
                    if (resolver.note.isNotBlank()) item.addView(TextView(this@MainActivity).apply {
                        text = resolver.note
                        textSize = 7f
                        gravity = Gravity.CENTER
                        maxLines = 2
                        setTextColor(0xFF64748B.toInt())
                    })
                    list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
                    if (resolverIndex < endIndex - 1 && !(showOnlineDivider && resolverIndex + 1 == dividerBeforeIndex)) {
                        list.addView(DashedDividerView(this@MainActivity), LinearLayout.LayoutParams(-1, dp(3)).apply {
                            topMargin = 0; bottomMargin = dp(1)
                        })
                    }
                }
                moreButton?.visibility = if (endIndex < currentResolvers.size) View.VISIBLE else View.GONE
                if (endIndex < currentResolvers.size) {
                    moreButton?.text = "نمایش ۵۰ DNS بعدی (${endIndex} از ${currentResolvers.size})"
                }
                innerScroll?.post { innerScroll?.scrollTo(0, oldY) }
            }

            val sortButton = Button(this@MainActivity).apply {
                text = "↕ Sort  |  مرتب‌سازی: $currentSort"
                textSize = 8f
                minHeight = dp(24)
                setPadding(dp(1), 0, dp(1), 0)
                setOnClickListener { anchor ->
                    val options = listOf("ترتیب اصلی", "گروه‌بندی سری‌های مشابه", "ابتدا عدد یک‌رقمی", "ابتدا عدد دورقمی", "ابتدا عدد سه‌رقمی")
                    android.widget.PopupMenu(this@MainActivity, anchor).apply {
                        options.forEachIndexed { index, label -> menu.add(0, index, index, label) }
                        setOnMenuItemClickListener { selected ->
                            currentSort = selected.title.toString()
                            val indexed = resolvers.withIndex()
                            currentResolvers = when (selected.itemId) {
                                1 -> indexed.sortedWith(compareBy<IndexedValue<Resolver>>(
                                    { ipv4SeriesKey(it.value.first) }, { it.value.first }
                                )).map { it.value }
                                in 2..4 -> {
                                    val preferredDigits = selected.itemId - 1
                                    indexed.sortedWith(compareBy<IndexedValue<Resolver>>(
                                        { if (firstIpv4OctetDigits(it.value.first) == preferredDigits) 0 else 1 },
                                        { firstIpv4OctetDigits(it.value.first) },
                                        { firstIpv4OctetValue(it.value.first) },
                                        { it.index }
                                    )).map { it.value }
                                }
                                else -> resolvers.toList()
                            }
                            visibleCount = 50
                            (anchor as Button).text = "↕ Sort  |  مرتب‌سازی: $currentSort"
                            renderItems(showOnlineDivider = selected.itemId == 0)
                            true
                        }
                        show()
                    }
                }
            }
            val listActions = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listActions.addView(sortButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(2) })
            moreButton = Button(this@MainActivity).apply {
                isAllCaps = false
                textSize = 8f
                minHeight = dp(24)
                setPadding(dp(1), 0, dp(1), 0)
                setOnClickListener {
                    visibleCount = minOf(visibleCount + 50, currentResolvers.size)
                    renderItems(showOnlineDivider = discoveryLoading)
                }
            }
            listActions.addView(moreButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2) })
            addView(listActions, LinearLayout.LayoutParams(-1, dp(48)))

            val countryScroll = object : ScrollView(this@MainActivity) {
                private var downY = 0f
                private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downY = event.y
                            // Give this country's list first ownership of the gesture.
                            // Without this, the outer page ScrollView intercepts the swipe
                            // before the inner country list can move.
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dy = event.y - downY
                            if (kotlin.math.abs(dy) > touchSlop) {
                                val childCanScroll = if (dy < 0) canScrollVertically(1) else canScrollVertically(-1)
                                // Hand the gesture back to the page only at this list's edge.
                                parent?.requestDisallowInterceptTouchEvent(childCanScroll)
                            }
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                            parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    return super.onInterceptTouchEvent(event)
                }
            }.apply {
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isNestedScrollingEnabled = false
                addView(list)
            }
            innerScroll = countryScroll
            addView(countryScroll, LinearLayout.LayoutParams(-1, listHeight))
            renderItems(showOnlineDivider = discoveryLoading)
        }
    }

    /** Similar IPv4 series (same first two octets) share one soft random pastel color. */
    private fun seriesColorForResolver(resolver: Resolver): Int {
        val key = ipv4SeriesKey(resolver.first).takeIf { !it.startsWith("zzzz:") }
            ?: "name:${resolver.name.substringBefore(' ')}"
        return dnsSeriesColors.getOrPut(key) {
            val hue = java.util.Random().nextFloat() * 360f
            android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.20f, 0.98f))
        }
    }

    private fun ipv4Octets(address: String): List<Int>? {
        val parts = address.trim().split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        return values.takeIf { it.all { octet -> octet in 0..255 } }
    }

    private fun firstIpv4OctetDigits(address: String): Int = ipv4Octets(address)?.firstOrNull()?.toString()?.length ?: 4
    private fun firstIpv4OctetValue(address: String): Int = ipv4Octets(address)?.firstOrNull() ?: Int.MAX_VALUE
    private fun ipv4SeriesKey(address: String): String {
        val octets = ipv4Octets(address) ?: return "zzzz:$address"
        return "%03d.%03d".format(java.util.Locale.US, octets[0], octets[1])
    }

    private fun dnsAddressButton(label: String, address: String): View {
        return TextView(this).apply {
            val shown = android.text.SpannableString(label)
            val addressStart = label.indexOf(address)
            if (addressStart >= 0 && address.isNotEmpty()) {
                shown.setSpan(android.text.style.RelativeSizeSpan(1.85f), addressStart, addressStart + address.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                shown.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), addressStart, addressStart + address.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = shown
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(0xFF176B87.toInt())
            setPadding(dp(1), dp(3), dp(1), dp(3))
            setBackgroundColor(0xFFE2F1F5.toInt())
            isClickable = true
            setOnClickListener {
                if (targetField == 1) dns1.setText(address) else dns2.setText(address)
                save()
                toast("$address در DNS $targetField قرار گرفت.")
            }
        }
    }

    private fun buildIpv6Tab(content: LinearLayout) {
        panel(content, "جست‌وجو و انتخاب DNSهای IPv6") {
            addView(TextView(this@MainActivity).apply {
                text = "تعداد آدرس‌های موردنیاز را تعیین کنید، سپس در فهرست جست‌وجو کنید و آدرس‌ها را انتخاب نمایید. برای استفاده از آن‌ها، گزینه IPv6 بالای صفحه را روشن کنید."
                textSize = 12f
                setTextColor(0xFF526176.toInt())
            })
            ipv6Status = TextView(this@MainActivity).apply {
                text = if (discoveredIpv6.isEmpty()) "در حال آماده‌سازی جست‌وجوی آنلاین IPv6…" else "IPv6: ${discoveredIpv6.size} آدرس یکتا دریافت شد."
                textSize = 11f
                setTextColor(0xFF526176.toInt())
            }
            addView(ipv6Status)
            ipv6Count = Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, (1..100).map { "$it آدرس" })
                setSelection((prefs.getInt("ipv6_count", 2) - 1).coerceIn(0, 99))
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        prefs.edit().putInt("ipv6_count", position + 1).apply()
                    }
                }
            }
            addView(ipv6Count, LinearLayout.LayoutParams(-1, dp(44)))
            ipv6Search = EditText(this@MainActivity).apply {
                hint = "جست‌وجوی نام سرویس یا آدرس IPv6"
                setSingleLine(true)
                textSize = 13f
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderIpv6Results(s?.toString().orEmpty()) }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            }
            addView(ipv6Search, LinearLayout.LayoutParams(-1, dp(46)))
            addView(Button(this@MainActivity).apply {
                text = "جست‌وجو در فهرست IPv6"
                isAllCaps = false
                setOnClickListener { renderIpv6Results(ipv6Search.text.toString()); toast("فهرست IPv6 بررسی و فیلتر شد.") }
            }, LinearLayout.LayoutParams(-1, dp(44)))
            addView(Button(this@MainActivity).apply {
                text = "آزمایش پاسخ‌گویی ۱۲۰ DNS اول IPv6"
                isAllCaps = false
                setOnClickListener { probeDnsServers(6) }
            }, LinearLayout.LayoutParams(-1, dp(42)))
            ipv6ProbeStatus = TextView(this@MainActivity).apply {
                text = "وضعیت پاسخ‌گویی هنوز آزمایش نشده است."
                textSize = 11f
                setTextColor(0xFF526176.toInt())
            }
            addView(ipv6ProbeStatus)
        }
        panel(content, "آدرس‌های انتخاب‌شده") {
            addView(TextView(this@MainActivity).apply {
                text = "برای افزودن یا حذف، مربع کنار هر آدرس را لمس کنید. تعداد انتخاب‌ها مطابق تعداد تعیین‌شده محدود می‌شود."
                textSize = 11f
                setTextColor(0xFF64748B.toInt())
            })
            addView(Button(this@MainActivity).apply {
                text = "پاک‌کردن انتخاب‌ها"
                isAllCaps = false
                setOnClickListener { selectedIpv6.clear(); persistIpv6(); renderIpv6Results(ipv6Search.text.toString()) }
            })
        }
        ipv6Results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ipv6Results, LinearLayout.LayoutParams(-1, -2))
        renderIpv6Results("")
    }

    private fun renderIpv6Results(query: String, requestedLimit: Int = 100) {
        if (!::ipv6Results.isInitialized) return
        selectedIpv6.clear()
        selectedIpv6.addAll(prefs.getStringSet("ipv6_selected", emptySet()) ?: emptySet())
        ipv6Results.removeAllViews()
        val q = query.trim().lowercase()
        val staticMatches = ipv6Resolvers.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) || it.note.lowercase().contains(q) }
        val knownIpv6 = ipv6Resolvers.flatMap { listOf(it.first, it.second) }.toSet()
        val onlineAddresses = discoveredIpv6.filter { it !in knownIpv6 && (q.isEmpty() || it.lowercase().contains(q)) }
        val visibleStatic = staticMatches.take(requestedLimit)
        val remainingSlots = (requestedLimit - visibleStatic.size).coerceAtLeast(0)
        val visibleOnline = onlineAddresses.take(remainingSlots).mapIndexed { index, ip ->
            Resolver("DNS عمومی آنلاین #${index + 1}", ip, "", "کشور: ${countryLabelForAddress(ip)}")
        }
        val totalMatches = staticMatches.size + onlineAddresses.size
        val matches = visibleStatic + visibleOnline
        matches.forEach { resolver ->
            listOf(resolver.first, resolver.second).filter { it.isNotBlank() }.forEach { address ->
                val check = CheckBox(this).apply {
                    val countryText = if (resolver.note.startsWith("کشور:")) "  |  ${resolver.note}" else "  |  کشور: ${countryLabelForAddress(address)}"
                    text = "${resolver.name}  •  $address$countryText" + if (responsiveIpv6.contains(address)) "  ✓ پاسخ‌گو" else "  • آزمایش‌نشده"
                    textSize = 12f
                    setTextColor(0xFF1B3554.toInt())
                    isChecked = selectedIpv6.contains(address)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            val max = if (::ipv6Count.isInitialized) ipv6Count.selectedItemPosition + 1 else prefs.getInt("ipv6_count", 2)
                            if (selectedIpv6.size >= max) { isChecked = false; toast("حداکثر $max آدرس می‌توانید انتخاب کنید."); return@setOnCheckedChangeListener }
                            selectedIpv6.add(address)
                        } else selectedIpv6.remove(address)
                        persistIpv6()
                    }
                }
                ipv6Results.addView(check, LinearLayout.LayoutParams(-1, dp(42)))
            }
        }
        if (matches.isEmpty()) ipv6Results.addView(TextView(this).apply { text = "نتیجه‌ای پیدا نشد."; textSize = 13f })
        if (totalMatches > matches.size) ipv6Results.addView(Button(this).apply {
            text = "نمایش ۱۰۰ نتیجه بعدی (${matches.size} از ${totalMatches})"
            isAllCaps = false
            setOnClickListener { renderIpv6Results(query, requestedLimit + 100) }
        }, LinearLayout.LayoutParams(-1, dp(42)))
    }

    private fun persistIpv6() {
        prefs.edit().putStringSet("ipv6_selected", selectedIpv6.toSet()).apply()
        toast("فهرست IPv6 ذخیره شد.")
    }

    private class DashedDividerView(context: android.content.Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7892A8.toInt()
            strokeWidth = context.resources.displayMetrics.density
            style = android.graphics.Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * context.resources.displayMetrics.density, 3f * context.resources.displayMetrics.density), 0f)
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val y = height / 2f
            canvas.drawLine(paddingLeft.toFloat(), y, (width - paddingRight).toFloat(), y, paint)
        }
    }

    private fun addThreeColumnGrid(parent: LinearLayout, cards: List<View>) {
        var index = 0
        while (index < cards.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                weightSum = 3f
            }
            for (column in 0 until 3) {
                val params = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                val card = cards.getOrNull(index + column) ?: View(this)
                row.addView(card, params)
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            index += 3
        }
    }

    private fun addTwoColumnGrid(parent: LinearLayout, cards: List<View>) {
        var index = 0
        while (index < cards.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            val left = cards[index]
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            if (index + 1 < cards.size) {
                val right = cards[index + 1]
                row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
            } else {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(4) })
            }
            parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            index += 2
        }
    }

    private fun panelCard(title: String, body: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            setBackgroundColor(0xFFFFFFFF.toInt())
            addView(TextView(this@MainActivity).apply {
                // A single-line header uses the card's existing width instead of stacking title parts.
                text = title.replace("\n", " • ")
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    setAutoSizeTextTypeUniformWithConfiguration(9, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }
                setTypeface(null, 1)
                setTextColor(0xFF172A46.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(2))
            })
            body()
        }
    }

    private fun buildSavedTab(content: LinearLayout) {
        panel(content, "ذخیره جفت DNS با نام دلخواه") {
            addView(Button(this@MainActivity).apply {
                text = "ذخیره دو DNS فعلی با نام جدید"
                isAllCaps = false
                setOnClickListener { saveCurrentPairDialog() }
            })
            addView(Button(this@MainActivity).apply {
                text = "خروجی TXT همه فهرست‌ها و ذخیره‌ها"
                isAllCaps = false
                setOnClickListener { exportAllTxt() }
            })
            addView(Button(this@MainActivity).apply {
                text = "ذخیره فهرست پیش‌فرض کشورها"
                isAllCaps = false
                setOnClickListener { saveCatalog(); toast("فهرست کشورها ذخیره شد.") }
            })
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        savedPairsContainer = list
        content.addView(list)
        renderSavedPairs(list)
    }

    private fun renderSavedPairs(container: LinearLayout) {
        container.removeAllViews()
        savedPairs.forEachIndexed { index, pair ->
            panel(container, "${index + 1}. ${pair.name}") {
                addView(TextView(this@MainActivity).apply {
                    text = "DNS 1: ${pair.first}\nDNS 2: ${pair.second}"
                    textSize = 13f
                    setTextColor(0xFF34445A.toInt())
                })
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(Button(this@MainActivity).apply {
                    text = "بارگذاری"
                    isAllCaps = false
                    setOnClickListener { dns1.setText(pair.first); dns2.setText(pair.second); save(); toast("${pair.name} بارگذاری شد.") }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                row.addView(Button(this@MainActivity).apply {
                    text = "ویرایش نام"
                    isAllCaps = false
                    setOnClickListener { editSavedPairName(index) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                row.addView(Button(this@MainActivity).apply {
                    text = "حذف"
                    isAllCaps = false
                    setOnClickListener { savedPairs.removeAt(index); persistSavedPairs(); renderSavedPairs(container) }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(row)
            }
        }
        if (savedPairs.isEmpty()) container.addView(TextView(this).apply { text = "هنوز جفت DNS ذخیره نشده است."; textSize = 13f })
    }

    private fun saveCurrentPairDialog() {
        val name = EditText(this).apply { hint = "نام این جفت DNS"; setSingleLine(true) }
        android.app.AlertDialog.Builder(this).setTitle("ذخیره جفت DNS")
            .setView(name).setPositiveButton("ذخیره") { _, _ ->
                val a = dns1.text.toString().trim(); val b = dns2.text.toString().trim()
                if ((!validIp(a) && !validIpv6(a)) || (!validIp(b) && !validIpv6(b))) { toast("ابتدا دو IP معتبر وارد کنید."); return@setPositiveButton }
                savedPairs.add(Resolver(name.text.toString().trim().ifBlank { "DNS ذخیره‌شده ${savedPairs.size + 1}" }, a, b))
                persistSavedPairs(); savedPairsContainer?.let { renderSavedPairs(it) }; newDataTabs.add(3); updateTabBadges(listOf("کشورها", "DNS عمومی", "IPv6", "ذخیره‌شده", "راهنما")); toast("جفت DNS ذخیره شد.")
                if (::tabContent.isInitialized) { /* refreshed on next tab visit */ }
            }.setNegativeButton("انصراف", null).show()
    }

    private fun editSavedPairName(index: Int) {
        val input = EditText(this).apply { setSingleLine(true); setText(savedPairs[index].name) }
        android.app.AlertDialog.Builder(this).setTitle("ویرایش نام")
            .setView(input).setPositiveButton("ثبت") { _, _ -> savedPairs[index] = savedPairs[index].copy(name = input.text.toString().trim().ifBlank { savedPairs[index].name }); persistSavedPairs(); savedPairsContainer?.let { renderSavedPairs(it) }; toast("نام ویرایش شد.") }
            .setNegativeButton("انصراف", null).show()
    }

    private fun persistSavedPairs() {
        val arr = JSONArray()
        savedPairs.forEach { arr.put(JSONObject().put("name", it.name).put("first", it.first).put("second", it.second)) }
        prefs.edit().putString("saved_pairs", arr.toString()).apply()
    }

    private fun openTxtImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/*" }
        try { startActivityForResult(intent, 79) } catch (_: Exception) { toast("انتخاب فایل در این دستگاه پشتیبانی نشد.") }
    }

    private fun importDnsText(rawText: String, defaultCountry: String = "") {
        var added = 0
        rawText.lines().forEach { raw ->
            val line = raw.trim(); if (line.isBlank() || line.startsWith("#") || line.startsWith("DNSchOne")) return@forEach
            val parts = line.split(',', ';', '\t', '|').map { it.trim() }
            val ip = parts.lastOrNull().orEmpty()
            if (validIp(ip) || validIpv6(ip)) {
                val country = if (parts.size > 1) parts.dropLast(1).joinToString(" ").ifBlank { defaultCountry } else defaultCountry
                val resolverName = if (country.isNotBlank()) "DNS واردشده از $country" else "DNS واردشده ${importedResolvers.size + 1}"
                importedResolvers.add(Resolver(resolverName, ip, "", if (country.isNotBlank()) "کشور:$country" else "فهرست:واردشده")); added++
            }
        }
        persistImported()
        if (added > 0) { newDataTabs.add(0); newDataTabs.add(1); updateTabBadges(listOf("کشورها", "DNS عمومی", "IPv6", "ذخیره‌شده", "راهنما")) }
        if (::countryGridContainer.isInitialized) renderCountryGrid()
        toast("$added آدرس معتبر از فهرست وارد شد.")
    }

    private fun importDnsListDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val name = EditText(this).apply { hint = "نام فهرست یا کشور (اختیاری)"; setSingleLine(true) }
        val text = EditText(this).apply { hint = "هر DNS در یک خط؛ نمونه: 1.1.1.1 یا نام کشور,1.1.1.1"; minLines = 7; gravity = Gravity.TOP; setTextIsSelectable(true) }
        box.addView(name); box.addView(text)
        android.app.AlertDialog.Builder(this).setTitle("وارد کردن فهرست DNS")
            .setView(box).setPositiveButton("افزودن") { _, _ ->
                importDnsText(text.text.toString(), name.text.toString().trim())
            }.setNegativeButton("انصراف", null).show()
    }

    private fun persistImported() {
        val arr = JSONArray(); importedResolvers.forEach { arr.put(JSONObject().put("name", it.name).put("first", it.first).put("note", it.note)) }
        prefs.edit().putString("imported_dns", arr.toString()).apply()
    }

    private fun globalSearchDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), 0) }
        val input = EditText(this).apply { hint = "نام کشور، سرویس‌دهنده یا آدرس DNS"; setSingleLine(true) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(results) }
        box.addView(input); box.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        val dialog = android.app.AlertDialog.Builder(this).setTitle("جست‌وجوی همه بخش‌ها").setView(box).setNegativeButton("بستن", null).create()
        fun search(q0: String) {
            results.removeAllViews(); val q = q0.trim().lowercase(); if (q.isBlank()) return
            val all = mutableListOf<Pair<String, Resolver>>()
            countries.forEach { (country, list) -> list.forEach { all.add("کشور: $country" to it) } }
            searchOnlyCountrySeeds.forEach { (country, list) -> list.forEach { all.add("فهرست جستجو؛ کشور: $country" to it) } }
            discoveredCountryDns.forEach { (country, ips) -> ips.forEach { all.add("فهرست جستجو؛ کشور: $country" to Resolver("DNS کشف‌شده", it, "")) } }
            (publicDns + importedResolvers + savedPairs).forEach { all.add("فهرست عمومی / واردشده / ذخیره‌شده" to it) }
            discoveredIpv4.forEach { all.add("IPv4 آنلاین" to Resolver("DNS آنلاین", it, "")) }
            discoveredIpv6.forEach { all.add("IPv6 آنلاین" to Resolver("DNS آنلاین", it, "")) }
            ipv6Resolvers.forEach { all.add("IPv6 پیشنهادی" to it) }
            val matches = all.filter { (group, r) -> listOf(group, r.name, r.first, r.second, r.note).any { it.lowercase().contains(q) } }.distinctBy { it.second.first + it.second.second }
            matches.forEach { (group, r) -> results.addView(Button(this).apply {
                text = "$group | ${r.name}\n${r.first}" + if (r.second.isNotBlank()) "  /  ${r.second}" else ""
                isAllCaps = false; textSize = 12f
                setOnClickListener { dns1.setText(r.first); if (r.second.isNotBlank()) dns2.setText(r.second); dialog.dismiss(); toast("DNS در کادرهای اصلی قرار گرفت.") }
            }) }
            if (matches.isEmpty()) results.addView(TextView(this).apply { text = "موردی پیدا نشد." })
        }
        input.addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { search(s?.toString().orEmpty()) }; override fun afterTextChanged(s: android.text.Editable?) {} })
        dialog.show()
    }

    private fun exportAllTxt() {
        val content = buildString {
            appendLine("DNSchOne - فهرست کامل DNSها")
            appendLine("\n=== جفت DNSهای ذخیره‌شده ===")
            savedPairs.forEach { appendLine("${it.name}\nDNS1=${it.first}\nDNS2=${it.second}\n") }
            appendLine("\n=== DNSهای کشورهای دارای پنجره ===")
            countries.forEach { (country, list) -> appendLine("[$country]"); list.forEach { appendLine("${it.name}: ${it.first} | ${it.second}") } }
            appendLine("\n=== DNSهای آنلاین بر اساس کشور ===")
            discoveredCountryDns.forEach { (country, ips) -> appendLine("[$country]"); ips.forEach { appendLine(it) } }
            appendLine("\n=== DNSهای واردشده ===")
            importedResolvers.forEach { appendLine("${it.note} ${it.name}: ${it.first}") }
            appendLine("\n=== DNSهای عمومی ===")
            publicDns.forEach { appendLine("${it.name}: ${it.first} | ${it.second}") }
            appendLine("\n=== IPv4 کشف‌شده ==="); discoveredIpv4.forEach { appendLine(it) }
            appendLine("\n=== IPv6 کشف‌شده ==="); discoveredIpv6.forEach { appendLine(it) }
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"; putExtra(Intent.EXTRA_TITLE, "DNSchOne_All_DNS_Lists.txt") }
        try { startActivityForResult(intent, 78); pendingTxtExport = content } catch (_: Exception) { toast("ذخیره فایل متنی در این دستگاه پشتیبانی نشد.") }
    }

    private var pendingTxtExport: String? = null

    private fun suggestSecondDns() {
        val selected = if (targetField == 1) dns1.text.toString().trim() else dns2.text.toString().trim()
        val pair = (countries.values.flatten() + searchOnlyCountrySeeds.values.flatten() + publicDns + ipv6Resolvers + importedResolvers + savedPairs)
            .firstOrNull { (it.first == selected || it.second == selected) && it.first.isNotBlank() && it.second.isNotBlank() }
        val suggestion = pair?.let { if (it.first == selected) it.second else it.first } ?: when (selected) {
            "1.1.1.1" -> "1.0.0.1"; "1.0.0.1" -> "1.1.1.1"
            "8.8.8.8" -> "8.8.4.4"; "8.8.4.4" -> "8.8.8.8"
            "9.9.9.9" -> "149.112.112.112"; "149.112.112.112" -> "9.9.9.9"
            "208.67.222.222" -> "208.67.220.220"; "208.67.220.220" -> "208.67.222.222"
            else -> "برای این DNS جفت تأییدشده‌ای در فهرست فعلی پیدا نشد."
        }
        val target = if (targetField == 1) 2 else 1
        android.app.AlertDialog.Builder(this).setTitle("پیشنهاد DNS مکمل برای کادر در حال ویرایش")
            .setMessage("DNS پیشنهادی برای کادر DNS $target:\n$suggestion\n\nاین پیشنهاد از جفت ثبت‌شده همان سرویس‌دهنده انتخاب شده است؛ سرعت و دسترس‌پذیری نهایی به شبکه شما بستگی دارد.")
            .setPositiveButton("قرار دادن در DNS $target") { _, _ ->
                if (suggestion.contains(".") || suggestion.contains(":")) { if (target == 2) dns2.setText(suggestion) else dns1.setText(suggestion) }
            }
            .setNegativeButton("بستن", null).show()
    }

    private fun showHowToWorkDialog() {
        val guide = """راهنمای کار با DNStow  |  How To Work

۱) انتخاب DNS
در بالای صفحه دو کادر DNS 1 و DNS 2 قرار دارد. روی هر کادر بزنید تا مقصد انتخاب مشخص شود. سپس در هر فهرست، آدرس DNS دلخواه را لمس کنید تا در کادر انتخاب‌شده قرار بگیرد. می‌توانید آدرس‌ها را دستی نیز وارد یا ویرایش کنید.

۲) روشن و خاموش کردن DNS
پس از انتخاب دو آدرس، دکمه «روشن کردن DNS» را بزنید و درخواست اتصال VPN اندروید را تأیید کنید. برای توقف، همان دکمه را دوباره بزنید. این برنامه برای راه‌اندازی سرویس به مجوز VPN محلی اندروید نیاز دارد و به روت احتیاج ندارد.

۳) پنجره کشورهای چین، آمریکا و ایران
در زبانه «کشورها» سه پنجره کشور کنار هم نمایش داده می‌شوند. هر پنجره فهرست مستقل خود را دارد و داخل همان پنجره می‌توانید به‌صورت جداگانه اسکرول کنید. لمس هر آدرس آن را در DNS 1 یا DNS 2 انتخاب‌شده قرار می‌دهد. برچسب کشور کنار آدرس‌ها، کشور فهرست یا منبع ثبت‌شده را نشان می‌دهد؛ بعضی DNSها Anycast و جهانی هستند و الزاماً فقط در همان کشور میزبانی نمی‌شوند.

۴) Research
دکمه Research فهرست‌های DNS عمومی و کشوری را دوباره از منابع آنلاین بررسی و به‌روزرسانی می‌کند. دریافت نتایج به اینترنت و در دسترس بودن منبع بستگی دارد. تعداد نتایج نمایش‌داده‌شده ممکن است با تعداد آدرس‌های پاسخ‌گو متفاوت باشد؛ هر DNS را روی شبکه خود آزمایش کنید.

۵) جستجوی سراسری
با دکمه «جستجو» می‌توانید در مجموعه فهرست‌ها جستجو کنید و آدرس یا نام سرویس‌دهنده را پیدا کنید.

۶) مرتب‌سازی و مشاهده نتایج بیشتر
در پنجره‌های فهرست، دکمه Sort برای مرتب‌سازی آدرس‌ها بر اساس ترتیب عددی ابتدای IP و گزینه نمایش نتایج بعدی برای دیدن ادامه فهرست به کار می‌رود. در فهرست‌های طولانی، داخل پنجره مربوطه اسکرول کنید.

۷) وارد کردن فهرست شخصی
در زبانه کشورها از «ورود فهرست متنی» یا «ورود فایل TXT» استفاده کنید. آدرس‌های IPv4 یا IPv6 موجود در فایل استخراج و موارد تکراری یا نامعتبر تا حد امکان کنار گذاشته می‌شوند.

۸) IPv6
در بخش IPv6 فهرست آدرس‌های سازگار را ببینید و در صورت نیاز گزینه «فعال‌سازی اختیاری DNSهای IPv6» را روشن کنید. استفاده از IPv6 اختیاری است و به پشتیبانی شبکه و اپراتور شما بستگی دارد.

۹) DNSهای عمومی و ذخیره‌شده
زبانه «DNS عمومی» شامل سرویس‌دهنده‌های شناخته‌شده و نتایج آنلاین است. در بخش «ذخیره‌شده» می‌توانید جفت DNSهای مورد استفاده خود را نگهداری و دوباره انتخاب کنید.

نکته فنی و محدودیت
سرویس VPN محلی این نسخه برای پردازش درخواست‌های DNS طراحی شده است؛ این به معنی عبور تمام ترافیک اینترنتی دستگاه از یک VPN کامل نیست. سرعت، پاسخ‌گویی، فیلترینگ و دسترس‌پذیری DNSها به اپراتور، کشور، مسیر شبکه و تنظیمات دستگاه بستگی دارد. در صورت قطع اینترنت، ابتدا DNS دیگری را آزمایش و وضعیت اتصال را بررسی کنید."""
        val textView = TextView(this).apply {
            text = guide
            textSize = 14f
            setTextColor(0xFF34445A.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(textView)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("ⓘ راهنمای برنامه | How To Work")
            .setView(scroll)
            .setPositiveButton("متوجه شدم", null)
            .show()
    }

    private fun buildInfoTab(content: LinearLayout) {
        panel(content, "راهنمای استفاده") {
            addView(TextView(this@MainActivity).apply {
                text = "۱. آدرس‌های DNS 1 و DNS 2 را در بالای صفحه وارد کنید یا از زبانه‌های کشورها و DNS عمومی انتخاب کنید.\n\n۲. دکمه فعال‌سازی DNS را بزنید و درخواست اتصال VPN اندروید را تأیید کنید. این برنامه برای تغییر DNS به دسترسی روت نیاز ندارد و از مجوز VPN محلی استفاده می‌کند.\n\n۳. برای قطع سرویس از دکمه «قطع اتصال» استفاده کنید.\n\nتوجه: این نسخه یک مسیر‌دهنده محلی DNS برای بسته‌های IPv4/UDP است؛ ترافیک عمومی برنامه‌ها را از تونل عبور نمی‌دهد. فهرست کشورها بر اساس ارائه‌دهنده‌هاست و بعضی سرویس‌ها Anycast هستند؛ بنابراین محل فیزیکی سرور الزاماً محدود به کشور درج‌شده نیست."
                textSize = 14f
                setTextColor(0xFF34445A.toInt())
                setLineSpacing(dp(3).toFloat(), 1f)
            })
        }
    }

    private fun panel(parent: LinearLayout, title: String, body: LinearLayout.() -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(8), dp(9), dp(9))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 17f
            setTextColor(0xFF172A46.toInt())
            setPadding(0, 0, 0, dp(7))
        })
        card.body()
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        inputType = android.text.InputType.TYPE_CLASS_TEXT
    }

    private fun selectTarget(field: Int) {
        targetField = if (field == 2) 2 else 1
        if (::dns1.isInitialized && ::dns2.isInitialized) {
            dns1.setBackgroundColor(if (targetField == 1) 0xFFDDF2F7.toInt() else 0xFFFFFFFF.toInt())
            dns2.setBackgroundColor(if (targetField == 2) 0xFFDDF2F7.toInt() else 0xFFFFFFFF.toInt())
        }
        if (::tabContent.isInitialized) {
            fun updateHint(view: View) {
                if (view is TextView && view.tag == "target_hint") {
                    view.text = "مقصد انتخاب فعلی: DNS $targetField"
                } else if (view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) updateHint(view.getChildAt(i))
                }
            }
            updateHint(tabContent)
        }
    }

    private fun toggleDns() {
        if (vpnEnabled) {
            // Stop the actual VPN service; do not rely on the Activity's cached flag.
            // Ask the foreground VPN service itself to tear down its TUN descriptor.
            startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
            vpnEnabled = false
            prefs.edit().putBoolean(DnsVpnService.PREF_ACTIVE, false)
                .putLong(DnsVpnService.PREF_HEARTBEAT, 0L).apply()
            toggleButton.text = "در حال خاموش‌شدن…"
            updateToggleAppearance()
            toggleButton.isEnabled = false
            status.text = "وضعیت: در حال توقف سرویس…"
            toggleButton.postDelayed({
                toggleButton.isEnabled = true
                syncVpnUiState()
            }, 700)
        } else {
            save()
            activateVpn()
        }
    }

    private fun save() {
        prefs.edit().putString("dns1", dns1.text.toString().trim())
            .putString("dns2", dns2.text.toString().trim()).apply()
    }

    private fun restore() {
        if (::dns1.isInitialized) {
            dns1.setText(prefs.getString("dns1", "8.8.8.8"))
            dns2.setText(prefs.getString("dns2", "8.8.4.4"))
        }
        try {
            val saved = JSONObject(prefs.getString("country_online", "{}") ?: "{}")
            val keys = saved.keys()
            while (keys.hasNext()) {
                val country = keys.next()
                val array = saved.optJSONArray(country) ?: continue
                val values = discoveredCountryDns.getOrPut(country) { linkedSetOf() }
                for (i in 0 until array.length()) {
                    val ip = array.optString(i)
                    if (validIp(ip) || validIpv6(ip)) values.add(ip)
                }
            }
        } catch (_: Exception) { }
        try {
            val arr = JSONArray(prefs.getString("saved_pairs", "[]") ?: "[]")
            savedPairs.clear(); for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); savedPairs.add(Resolver(o.optString("name"), o.optString("first"), o.optString("second"))) }
        } catch (_: Exception) { }
        try {
            val arr = JSONArray(prefs.getString("imported_dns", "[]") ?: "[]")
            importedResolvers.clear(); for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); importedResolvers.add(Resolver(o.optString("name"), o.optString("first"), "", o.optString("note"))) }
        } catch (_: Exception) { }
    }

    private fun saveCountryResearch() {
        val saved = JSONObject()
        discoveredCountryDns.forEach { (country, addresses) ->
            saved.put(country, JSONArray(addresses.toList()))
        }
        prefs.edit().putString("country_online", saved.toString()).apply()
    }

    private fun saveCatalog() {
        val obj = JSONObject()
        countries.forEach { (key, values) ->
            val array = JSONArray()
            values.forEach { array.put(JSONObject().put("name", it.name).put("dns1", it.first).put("dns2", it.second)) }
            obj.put(key, array)
        }
        prefs.edit().putString("catalog", obj.toString()).apply()
    }

    private fun activateVpn() {
        val first = dns1.text.toString().trim()
        val second = dns2.text.toString().trim()
        if ((!validIp(first) && !validIpv6(first)) || (!validIp(second) && !validIpv6(second))) {
            toast("هر دو DNS باید IP معتبر IPv4 یا IPv6 باشند.")
            return
        }
        if ((validIpv6(first) || validIpv6(second)) && !ipv6Toggle.isChecked) {
            toast("برای استفاده از آدرس IPv6، گزینه فعال‌سازی IPv6 را روشن کنید.")
            return
        }
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) startActivityForResult(prepareIntent, 77) else startDns()
    }

    @Deprecated("Deprecated by Android API; retained for compatibility with minSdk 23")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 79 && resultCode == RESULT_OK && data?.data != null) {
            try { val importedText = contentResolver.openInputStream(data.data!!)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty(); importDnsText(importedText) }
            catch (_: Exception) { toast("خواندن فایل TXT انجام نشد.") }
        } else if (requestCode == 78 && resultCode == RESULT_OK && data?.data != null) {
            try { contentResolver.openOutputStream(data.data!!)?.use { it.write((pendingTxtExport ?: "").toByteArray(Charsets.UTF_8)) }; toast("فایل TXT ذخیره شد.") }
            catch (_: Exception) { toast("ذخیره فایل انجام نشد.") }
            pendingTxtExport = null
        } else if (requestCode == 77 && resultCode == RESULT_OK) startDns()
        else if (requestCode == 77) {
            prefs.edit().putBoolean(DnsVpnService.PREF_ACTIVE, false).apply()
            vpnEnabled = false
            if (::toggleButton.isInitialized) {
                toggleButton.text = "OFF  |  روشن کردن DNS"
                updateToggleAppearance()
            }
            if (::status.isInitialized) status.text = "وضعیت: خاموش"
        }
    }

    private fun startDns() {
        val intent = Intent(this, DnsVpnService::class.java)
            .putExtra("dns1", dns1.text.toString().trim())
            .putExtra("dns2", dns2.text.toString().trim())
            .putExtra("ipv6_enabled", ::ipv6Toggle.isInitialized && ipv6Toggle.isChecked)
            .putStringArrayListExtra("ipv6_dns", ArrayList(prefs.getStringSet("ipv6_selected", emptySet()) ?: emptySet()))
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        // The foreground service writes an active flag and heartbeat only after
        // Builder.establish() succeeds. Until then the UI stays in a connecting state.
        vpnEnabled = false
        toggleButton.text = "در حال اتصال…"
        toggleButton.isEnabled = false
        updateToggleAppearance()
        status.text = "وضعیت: در حال اتصال…"
        toggleButton.postDelayed({
            toggleButton.isEnabled = true
            syncVpnUiState()
            if (!vpnEnabled) status.text = "وضعیت: سرویس متصل نشد یا متوقف شده است"
        }, 1200)
    }

    private fun validIp(value: String): Boolean = try {
        val parts = value.split(".")
        parts.size == 4 && parts.all { it.toInt() in 0..255 }
    } catch (_: Exception) { false }

    private fun validIpv6(value: String): Boolean = try {
        value.contains(":") && InetAddress.getByName(value) is java.net.Inet6Address
    } catch (_: Exception) { false }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
