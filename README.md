# DNStow — DNSchOne project continuation

## جست‌وجوی DNS عمومی IPv4 و IPv6
- جست‌وجوی آنلاین در چند منبع مستقل انجام می‌شود: public-dns.info، فهرست روزآمد Byteful/PingProxies، فهرست‌های DNS تأییدشده Black Lantern، فهرست accepted پروژه disposable و فهرست ارائه‌دهندگان AdGuard.
- برای IPv6، فایل‌های مستقل چندین کشور از فهرست Byteful/PingProxies نیز دریافت می‌شوند.
- IPهای تکراری، خصوصی، رزروشده، مستنداتی و قالب‌های نامعتبر حذف می‌شوند.
- هر پنجره تا ۱۰۰۰ نتیجه را برای نمایش آماده می‌کند؛ مجموعه‌های بزرگ‌تر از این تعداد نیز برای جست‌وجو در حافظه نگهداری می‌شوند.
- جست‌وجوی DNSهای کشورها از طریق جست‌وجوی سراسری انجام می‌شود؛ فقط چین، آمریکا و ایران پنجره مستقل دارند. IPv6 امکان انتخاب تا ۱۰۰ آدرس را دارد.
- تعداد «کشف‌شده» با تعداد «پاسخ‌گو» یکی نیست. دکمه آزمایش، حداکثر ۱۲۰ آدرس اول هر خانواده را با پرس‌وجوی واقعی UDP/53 آزمایش می‌کند و پاسخ‌های تأییدشده را جداگانه علامت می‌زند. نتیجه آزمایش به شبکه و زمان آزمایش وابسته است.
- بعضی منابع ممکن است موقتاً در دسترس نباشند؛ برنامه نتایج منابع موفق را نگه می‌دارد و منابع ناموفق را نادیده می‌گیرد.

## اسکرول پنجره‌ها
- صفحه اصلی ثابت است و محتوای زبانه در اسکرول خودش قرار دارد.
- فهرست‌های داخلی کشورها اسکرول مستقل دارند و لمس/کشیدن آن‌ها به اسکرول بیرونی صفحه منتقل نمی‌شود.
- کارت‌های کشورها در چیدمان دو ستونه نمایش داده می‌شوند.

## روشن و خاموش کردن DNS
- دکمه خاموش‌کردن یک فرمان توقف اختصاصی به خود سرویس VPN می‌فرستد.
- سرویس، تونل TUN و حلقه پردازش بسته‌ها را می‌بندد و وضعیت خاموش را در تنظیمات ثبت می‌کند.
- وضعیت روشن تنها پس از موفقیت `VpnService.Builder.establish()` ثبت می‌شود.

## IPv6 اختیاری
- IPv6 از گزینه مستقل بالای صفحه فعال یا غیرفعال می‌شود.
- پنجره IPv6 دارای جست‌وجو، انتخاب تعداد، انتخاب و حذف آدرس‌ها و آزمایش پاسخ‌گویی است.
- مسیر‌دهی فعلی فقط DNS روی UDP را پردازش می‌کند؛ DNS روی TCP و IPv6 Extension Headerها در این نسخه پشتیبانی نمی‌شوند.

## آیکون
- نشان پرچم از آیکون برنامه حذف شده است؛ طرح اصلی DNS حفظ شده است.

## محدودیت فنی
این برنامه یک DNS forwarder محلی مبتنی بر VpnService است، نه تونل عمومی تمام ترافیک. بعضی شبکه‌ها IPv6 را مسدود می‌کنند یا به DNSهای عمومی دسترسی ندارند؛ بنابراین تعداد کشف‌شده یا پاسخ‌گو ممکن است بین شبکه‌ها متفاوت باشد.

## GitHub Actions و ساخت Repository تازه
Workflow در مسیر `.github/workflows/android.yml` قرار دارد و بدون فیلتر نام شاخه، با هر Push و Pull Request اجرا می‌شود. اجرای دستی نیز از تب Actions با گزینه Run workflow فعال است. خروجی APK پس از بیلد موفق با نام `DNStow-debug` در بخش Artifacts قرار می‌گیرد.

### راه‌اندازی Repository تازه با نام DNStow
1. در GitHub یک Repository خالی با نام `DNStow` بسازید. هنگام ساخت، افزودن README، `.gitignore` یا License را انتخاب نکنید تا فایل‌ها با نسخه پروژه تداخل نکنند.
2. محتویات داخل پوشه `DNStow` این ZIP را مستقیماً در ریشه Repository قرار دهید؛ پوشه DNStow را به‌صورت پوشه‌ای تو در تو در GitHub قرار ندهید.
3. مطمئن شوید فایل `.github/workflows/android.yml` در شاخه پیش‌فرض Repository و دقیقاً در همین مسیر وجود دارد.
4. اولین Commit را روی شاخه پیش‌فرض (معمولاً `main`) ثبت و Push کنید. فایل Workflow باید در همان شاخه پیش‌فرض وجود داشته باشد تا GitHub آن را شناسایی کند.
5. در تب Actions بررسی کنید که Workflow با نام `DNStow Android Build` نمایش داده شود. برای اجرای دستی، آن را باز کنید و Run workflow را بزنید.
6. اگر هیچ Workflowای دیده نمی‌شود، از Settings → Actions → General بررسی کنید که Actions برای Repository غیرفعال نشده باشد و سیاست سازمان/Repository اجازه اجرای Actions را بدهد.
7. اگر Workflow دیده می‌شود ولی اجرا قرمز می‌شود، وارد همان اجرا شوید و اولین مرحله قرمز را باز کنید؛ این حالت دیگر مشکل Trigger یا دیده‌نشدن Commit نیست و باید خطای همان مرحله بررسی شود.

این ZIP عمداً تاریخچه `.git` پروژه قبلی را ندارد تا برای Repository مستقل آماده باشد. `namespace` و `applicationId` اندروید حفظ شده‌اند تا شناسه فنی برنامه بی‌دلیل تغییر نکند.

## Country DNS browser (updated)

- Country panels are displayed in three equal-width columns.
- Each country panel's inner list viewport is 690 dp high (three times the previous 230 dp viewport); text is compacted to fit the narrower columns.
- The country tab remains vertically scrollable. At the top/bottom of a country panel's inner list, vertical gestures can pass to the page so lower country rows remain reachable.
- On refresh, each country window first downloads the complete country-specific TXT resolver list from Public DNS Info (`/nameserver/<country-code>.txt`). If fewer than 100 unique IPv4 addresses are found, CSV and independent GitHub mirrors are queried to add further unique addresses. The IPv6 country lists are fetched separately. There is no 50-, 100-, or 1,000-address display cap in country windows; the panel scrolls through all unique results returned by the sources.
- The source page's HTML table may show only its latest 100 rows, but the linked TXT file contains the full country list. For example, the current source reports 124 valid resolvers for China, 719 for Japan, 1,682 for Germany, and 11,550 for the United States. Some countries have fewer than 100 entries in the source (Iran currently lists 24), so the app does not invent or duplicate addresses to reach 100.
- Discovery does not guarantee every listed resolver answers from the user's current network. Use the DNS response test before relying on an address.


## نسخه 1.5 - قابلیت‌های مدیریتی جدید

- ورود فهرست DNS از متن یا فایل TXT؛ هر IP در یک خط یا به‌شکل «نام کشور,IP».
- ذخیره چند جفت DNS با نام دلخواه، بارگذاری، تغییر نام و حذف هر جفت.
- خروجی TXT یکپارچه از DNSهای ذخیره‌شده، کشورها، فهرست‌های واردشده، IPv4 و IPv6؛ محل ذخیره را کاربر در دستگاه انتخاب می‌کند.
- نشانگر فلش چشمک‌زن کنار زبانه‌هایی که داده تازه دارند؛ با باز کردن زبانه نشانگر پاک می‌شود.
- نمایش DNS کشورهای فاقد پنجره مستقل در جست‌وجوی سراسری همراه نام کشور.
- جست‌وجوی سراسری در فهرست‌های کشورها، DNS عمومی، ورودی‌ها، ذخیره‌ها و نتایج IPv4/IPv6.
- پیشنهاد DNS مکمل برای آدرس انتخاب‌شده با استفاده از جفت ثبت‌شده همان سرویس‌دهنده، در صورت وجود.

نکته: پیشنهاد DNS مکمل بر پایه جفت‌های شناخته‌شده است و تضمین‌کننده سریع‌ترین DNS در شبکه کاربر نیست؛ سرعت و پاسخ‌گویی باید در شبکه مقصد آزمایش شود.

## نسخه 1.11 — تکمیل فهرست مستقل DNS هر کشور

- محدودیت مصنوعی `take(100)` از دریافت DNSهای هر کشور حذف شد؛ تمام IPهای عمومی یکتا که از منابع دریافت شوند در همان کشور نگهداری و نمایش داده می‌شوند.
- برای هر کشور، دو منبع مستقل IPv4 دریافت می‌شود: فهرست کشور در Public DNS Directory و فهرست country-specific در public-dns.info.
- فهرست IPv6 اختصاصی همان کشور نیز دریافت می‌شود.
- در نسخه فعلی فقط چین، آمریکا و ایران پنجره مستقل دارند؛ داده سایر کشورها در جست‌وجوی سراسری نگهداری می‌شود.
- شمارنده هر پنجره تعداد IPهای یکتای seed و کشف‌شده را نشان می‌دهد. نتایج کشف‌شده بین اجراهای برنامه ذخیره می‌شوند.
- هیچ تضمینی برای رسیدن همه کشورها به بیش از ۱۰۰ مورد وجود ندارد؛ تعداد نهایی وابسته به داده واقعی منابع و دسترسی اینترنت است. برنامه دیگر نتایج واقعی را به ۱۰۰ مورد محدود نمی‌کند.
- چیدمان سه‌ستونه و اسکرول مستقل داخل هر پنجره حفظ شده است.


## نسخه 1.12
- در فهرست عمومی DNS، نام کشور هر آدرس در کنار آن نمایش داده می‌شود؛ این برچسب از تطبیق آدرس با فهرست‌های مستقل کشورها به دست می‌آید.
- همین برچسب برای نتایج عمومی IPv4 و IPv6 نیز نمایش داده می‌شود.
- سرویس‌های Anycast شناخته‌شده با برچسب «جهانی (Anycast؛ چندکشوری)» مشخص می‌شوند؛ اگر آدرس در فهرست کشورها پیدا نشود، این موضوع صریحاً با «نامشخص در فهرست کشورها» نشان داده می‌شود.
- پنجره‌های جداگانه کشورها و قابلیت‌های قبلی حفظ شده‌اند.


## Country DNS research update

Country windows now aggregate all unique resolver IPs returned by the country-specific Public DNS Info TXT/CSV feeds and the public-dns-directory mirrors. Results are cumulative and are not capped at 100 entries. The UI reports each country count; countries with fewer than 100 published results are not padded with invented addresses. Research requires an internet connection.


## DNStow 1.13 — Country list expansion

- Prioritizes the full country TXT list and uses CSV/GitHub mirrors only when a country has fewer than 100 unique IPv4 addresses.
- Keeps all unique country results; removes the previous README claim of a 50-address limit.
- Displays each country’s loaded count and whether the 100-address target was reached.
- Increments Android versionCode to 10 and versionName to 1.13.


## DNStow 1.13 — Country list expansion

- Prioritizes the full country TXT list and uses CSV/GitHub mirrors only when a country has fewer than 100 unique IPv4 addresses.
- Keeps all unique country results and removes the previous README claim of a 50-address limit.
- Displays each country’s loaded count and whether the 100-address target was reached.
- Increments Android versionCode to 10 and versionName to 1.13.


### پژوهش DNS کشورها (نسخهٔ گسترش‌یافته)

- فهرست اصلی JSON دارای برچسب کشور خوانده می‌شود و آدرس‌ها بر اساس `country_code` و نام کشور در پنجره‌های جداگانه قرار می‌گیرند.
- فهرست‌های TXT کشورها نیز برای تکمیل نتایج کشورهای اصلی دریافت و با دادهٔ اصلی ادغام می‌شوند.
- آدرس‌ها یکتا می‌شوند و هیچ سقف نمایشی ۱۰۰تایی یا ۱۰۰۰تایی روی پنجره‌های کشور اعمال نمی‌شود.
- تعداد ۱۰۰ یا بیشتر فقط زمانی نمایش داده می‌شود که واقعاً همان تعداد IP عمومی در منبع موجود باشد؛ برنامه IP ساختگی یا تکراری تولید نمی‌کند.
- منبع دادهٔ کشوری: `https://github.com/pingproxies/public-dns-directory` (فهرست به‌روزشونده با دادهٔ کشورها).


## DNStow — سه پنجرهٔ کشور و فهرست جست‌وجوی سراسری

- فقط سه پنجرهٔ مستقل کشور در صفحهٔ کشورها نمایش داده می‌شود: چین (CN)، آمریکا (US) و ایران (IR).
- پنجره‌های مستقل سایر کشورها حذف شده‌اند تا فضای صفحه فقط به همین سه کشور اختصاص یابد.
- اطلاعات DNS سایر کشورها حذف نمی‌شود: داده‌های کشورمحور دریافتی از منبع JSON و فهرست‌های قبلی در فهرست «جست‌وجوی همه بخش‌ها» باقی می‌مانند.
- DNSهای ثابت قبلی کانادا، فرانسه و انگلیس نیز به بخش جست‌وجوی سراسری منتقل شده‌اند و دیگر پنجرهٔ جداگانه ندارند.
- محدودیت ۱۰۰ نتیجه از پنجرهٔ جست‌وجوی سراسری برداشته شده است؛ نتایج یکتا بر اساس عبارت جست‌وجو نمایش داده می‌شوند.
- پژوهش آنلاین همچنان داده‌های کشورها را بر اساس نام کشور نگهداری می‌کند؛ تنها نمایش پنجره‌های مستقل به سه کشور محدود است.


## نسخه 1.15 — اسکرول مستقل و کاهش هنگ

- هر پنجره کشور ScrollView مستقل دارد و حرکت عمودی داخل پنجره، پنجره‌های دیگر را جابه‌جا نمی‌کند.
- ارتفاع قابل مشاهده هر پنجره کشور برای استفاده روی گوشی به 470dp تنظیم شده است.
- فهرست هر پنجره DNS را در دسته‌های 50تایی می‌سازد؛ دکمه «نمایش ۵۰ DNS بعدی» به همه نتایج دسترسی می‌دهد و هیچ DNS حذف نمی‌شود.
- نتایج عمومی IPv4 و IPv6 نیز در دسته‌های 100تایی ساخته می‌شوند تا از ایجاد هزاران View هم‌زمان و کندی رابط جلوگیری شود.
- مرتب‌سازی، انتخاب DNS و فهرست‌های ذخیره‌شده حفظ شده‌اند.


## نسخه 1.15 — اسکرول مستقل و کاهش هنگ

- هر پنجره کشور ScrollView مستقل دارد؛ حرکت عمودی درون پنجره، پنجره‌های دیگر را جابه‌جا نمی‌کند.
- ارتفاع هر پنجره کشور به 470dp تنظیم شده است.
- فهرست هر پنجره DNS را در دسته‌های 50تایی می‌سازد؛ دکمه «نمایش ۵۰ DNS بعدی» به بقیه نتایج دسترسی می‌دهد و DNSها حذف نمی‌شوند.
- نتایج عمومی IPv4 و IPv6 در دسته‌های 100تایی ساخته می‌شوند تا از ایجاد هزاران View هم‌زمان و کندی رابط جلوگیری شود.


## نسخه 1.16
- رفع خطای کامپایل Kotlin در متن دکمه صفحه‌بندی نتایج IPv6؛ شمار کل نتایج از متغیر تعریف‌شده `totalMatches` خوانده می‌شود.
