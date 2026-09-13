package com.slimsocial.nativeapp

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var homeBtn: Button
    @Volatile private var videoPlaying = false
    @Volatile private var scheduleBypassed = false
    private val prefs by lazy { getSharedPreferences("controls", MODE_PRIVATE) }
    private val blocks = listOf("Like / Reactions","Comments","Share","Follow / Friends","Profiles","Messenger","Stories","Reels / Watch","Search","Post creation","Upload","Marketplace","Group interactions","All buttons")
    private val arabicLabels = mapOf(
        "Like / Reactions" to "الإعجاب والتفاعلات",
        "Comments" to "التعليقات",
        "Share" to "المشاركة",
        "Follow / Friends" to "المتابعة والأصدقاء",
        "Profiles" to "الملفات الشخصية",
        "Messenger" to "ماسنجر",
        "Stories" to "القصص",
        "Reels / Watch" to "ريلز ومشاهدة",
        "Search" to "البحث",
        "Post creation" to "إنشاء منشور",
        "Upload" to "رفع الملفات",
        "Marketplace" to "ماركت بلايس",
        "Group interactions" to "تفاعلات المجموعات",
        "All buttons" to "كل الأزرار + منع الكتابة (وضع مشاهدة فقط)"
    )

    private val tapHandler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var lastKeywordRedirect = 0L
    private val usageHandler = Handler(Looper.getMainLooper())
    private var usageRunning = false

    private val usageTick = object : Runnable {
        override fun run() {
            if (!isWithinScheduledHours()) { usageRunning = false; showScheduleBlockedScreen(); return }
            checkAndTickUsage()
            if (usageRunning) usageHandler.postDelayed(this, 60000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        applyScreenshotProtection()
        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = !prefs.getBoolean("disable_js", false)
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace("wv;", "")
        applyCopyProtection()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun setPlaying(playing: Boolean) { videoPlaying = playing }
            @android.webkit.JavascriptInterface
            fun checkNav(url: String) { runOnUiThread { handleSpaNavigation(url) } }
            @android.webkit.JavascriptInterface
            fun openMedia(type: String, url: String) { runOnUiThread { showMediaViewer(type, url) } }
            @android.webkit.JavascriptInterface
            fun keywordRedirect() { runOnUiThread { redirectHomeForKeyword() } }
        }, "SlimBridge")
        web.webViewClient = object: WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val url = r.url.toString()
                val scheme = r.url.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") {
                    // Ignore app-deeplink / unsupported schemes (fb://, intent://, tel:, mailto:, etc.)
                    // so the WebView doesn't try to load them and show ERR_UNKNOWN_URL_SCHEME.
                    return true
                }
                if (isPageLocked(url)) return true
                if (isRefreshBlocked(url)) return true
                if (prefs.getBoolean("media_viewer", true)) {
                    val media = detectMediaViewerUrl(url)
                    if (media != null) {
                        extractMediaViaHiddenWebView(url, media)
                        return true
                    }
                }
                return handleNavigation(url)
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (!isAuth(url)) { applyControls(); applyCustom() }
            }
        }
        web.loadUrl(getHomeUrl())

        val menuBtn = findViewById<View>(R.id.menu)
        val reloadBtn = findViewById<View>(R.id.reload)
        menuBtn.setOnClickListener { checkPasswordThen { showControls() } }
        reloadBtn.setOnClickListener {
            if (prefs.getBoolean("block_refresh", false)) Toast.makeText(this, "تحديث الصفحة معطّل من الإعدادات", Toast.LENGTH_SHORT).show()
            else web.reload()
        }

        homeBtn = Button(this).apply {
            text = "🏠"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { web.loadUrl(getHomeUrl()) }
            setOnLongClickListener { showPagesChooser(); true }
        }
        (reloadBtn.parent as? ViewGroup)?.addView(homeBtn)

        applyToolbarVisibility(menuBtn, reloadBtn)

        val root = findViewById<View>(android.R.id.content)
        root.setOnClickListener {
            if (prefs.getBoolean("hide_toolbar", false)) {
                tapCount++
                tapHandler.removeCallbacksAndMessages(null)
                if (tapCount >= 3) {
                    tapCount = 0
                    checkPasswordThen { showControls() }
                } else {
                    tapHandler.postDelayed({ tapCount = 0 }, 1200)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isWithinScheduledHours()) { showScheduleBlockedScreen(); return }
        if (checkDailyLimitExceeded()) { showLimitReachedScreen(); return }
        usageRunning = true
        usageHandler.post(usageTick)
    }

    override fun onPause() {
        super.onPause()
        usageRunning = false
        usageHandler.removeCallbacksAndMessages(null)
        CookieManager.getInstance().flush()
    }

    private fun applyToolbarVisibility(menuBtn: View, reloadBtn: View) {
        val hide = prefs.getBoolean("hide_toolbar", false)
        menuBtn.visibility = if (hide) View.GONE else View.VISIBLE
        reloadBtn.visibility = if (hide) View.GONE else View.VISIBLE
        homeBtn.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun getHomeUrl(): String = prefs.getString("home_url", "https://www.facebook.com/") ?: "https://www.facebook.com/"

    private fun getCustomPages(): MutableList<Pair<String, String>> {
        val raw = prefs.getString("custom_pages", "[]") ?: "[]"
        val list = mutableListOf<Pair<String, String>>()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(obj.getString("name") to obj.getString("url"))
            }
        } catch (e: Exception) { /* ignore malformed data */ }
        return list
    }

    private fun saveCustomPages(pages: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        pages.forEach { (name, url) ->
            val obj = JSONObject()
            obj.put("name", name)
            obj.put("url", url)
            arr.put(obj)
        }
        prefs.edit().putString("custom_pages", arr.toString()).apply()
    }

    private fun showPagesChooser() {
        val pages = mutableListOf("🏠 الرئيسية" to getHomeUrl())
        pages.addAll(getCustomPages())
        if (pages.size <= 1) {
            Toast.makeText(this, "لا توجد صفحات إضافية بعد. أضِفها من الإعدادات", Toast.LENGTH_SHORT).show()
        }
        val names = pages.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("انتقل إلى صفحة")
            .setItems(names) { _, which -> web.loadUrl(pages[which].second) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun isAuth(url: String): Boolean {
        val u=url.lowercase(); return listOf("/login","/checkpoint","/recover","/reg","/registration").any { u.contains("facebook.com$it") || u.contains("facebook.com$it/") }
    }

    private fun isPageLocked(url: String): Boolean {
        if (!prefs.getBoolean("lock_page_enabled", false)) return false
        val locked = prefs.getString("lock_page_url", "") ?: ""
        if (locked.isEmpty()) return false
        if (isAuth(url)) return false
        return normalizeUrl(url) != normalizeUrl(locked)
    }

    private fun incrementBlockedCount() {
        val n = prefs.getInt("blocked_count", 0)
        prefs.edit().putInt("blocked_count", n + 1).apply()
    }

    private fun isProfilePageOrGroupUrl(u: String): Boolean {
        if (u.contains("/groups/")) return true
        if (u.contains("/pages/")) return true
        if (u.contains("/profile.php")) return true
        if (u.contains("/people/")) return true
        val regex = Regex("facebook\\.com/([a-z0-9_.\\-]+)/?(?:[?#]|$)")
        val match = regex.find(u)
        if (match != null) {
            val seg = match.groupValues[1]
            val reserved = setOf("home.php","login.php","checkpoint","help","settings","notifications","friends","photo.php","photos.php","story.php","permalink.php","sharer.php","privacy","dialog","unified","l.php","messages","messenger","watch","reel","marketplace","search","www","m","mbasic","touch")
            if (seg !in reserved && seg.isNotEmpty()) return true
        }
        return false
    }

    private fun normalizeUrl(u: String): String {
        return u.lowercase().substringBefore("?").substringBefore("#").trimEnd('/')
    }

    private fun isRefreshBlocked(url: String): Boolean {
        val isReload = normalizeUrl(url) == normalizeUrl(web.url ?: "")
        if (!isReload) return false
        if (prefs.getBoolean("block_refresh", false)) {
            runOnUiThread { Toast.makeText(this, "تحديث الصفحة معطّل من الإعدادات", Toast.LENGTH_SHORT).show() }
            incrementBlockedCount()
            return true
        }
        if (prefs.getBoolean("block_refresh_on_video", false) && videoPlaying) {
            runOnUiThread { Toast.makeText(this, "تم منع التحديث أثناء تشغيل فيديو", Toast.LENGTH_SHORT).show() }
            incrementBlockedCount()
            return true
        }
        return false
    }

    private fun getWhitelistSet(): List<String> {
        val raw = prefs.getString("group_whitelist", "") ?: ""
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    private fun getKeywordList(): List<String> {
        val raw = prefs.getString("keyword_blocklist", "") ?: ""
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    private fun getButtonBlockWords(): List<String> {
        val raw = prefs.getString("button_block_words", "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Built-in, toggle-controlled words — kept separate from the user's free-text field above.
    private fun getBuiltInButtonWords(): List<String> {
        val words = mutableListOf<String>()
        if (prefs.getBoolean("block_message_btn", false)) {
            words.addAll(listOf("مراسلة", "راسل", "إرسال رسالة", "Message", "Send Message"))
        }
        return words
    }

    private fun extractIdentifier(url: String): String? {
        val gid = extractGroupId(url)
        if (gid != null) return gid
        val regex = Regex("facebook\\.com/([a-z0-9_.\\-]+)/?(?:[?#]|$)")
        val match = regex.find(url)
        return match?.groupValues?.get(1)
    }

    private fun handleNavigation(url: String): Boolean {
        val u = url.lowercase()
        val isFacebookDomain = u.contains("facebook.com") || u.contains("fbcdn.net")

        if (prefs.getBoolean("block_external", false) && !isFacebookDomain) {
            runOnUiThread { Toast.makeText(this, "تم منع رابط خارجي", Toast.LENGTH_SHORT).show() }
            incrementBlockedCount()
            return true
        }

        if (prefs.getBoolean("block_profile_nav", false) && isFacebookDomain && !isAuth(u) && isProfilePageOrGroupUrl(u)) {
            val id = extractIdentifier(u)
            val whitelist = getWhitelistSet()
            if (id == null || !whitelist.contains(id)) {
                runOnUiThread { Toast.makeText(this, "تم منع زيارة هذا الملف الشخصي/الصفحة/المجموعة", Toast.LENGTH_SHORT).show() }
                incrementBlockedCount()
                return true
            }
        }

        if (prefs.getBoolean("block_unjoined_groups", false) && u.contains("/groups/")) {
            val groupId = extractGroupId(u)
            val allowed = getWhitelistSet()
            if (groupId != null && !allowed.contains(groupId)) {
                runOnUiThread { Toast.makeText(this, "مجموعة غير مسموح بها. أضفها من الإعدادات إن أردت السماح", Toast.LENGTH_LONG).show() }
                incrementBlockedCount()
                return true
            }
        }

        return false
    }

    private fun redirectHomeForKeyword() {
        val now = System.currentTimeMillis()
        if (now - lastKeywordRedirect < 3000) return // guard against repeated triggers on the same page
        lastKeywordRedirect = now
        incrementBlockedCount()
        web.stopLoading()
        web.loadUrl(getHomeUrl())
        Toast.makeText(this, "تم إرجاعك للرئيسية (الصفحة تحتوي على كلمة محظورة)", Toast.LENGTH_SHORT).show()
    }

    private fun handleSpaNavigation(url: String) {
        if (isAuth(url)) return
        if (isPageLocked(url)) {
            val lockedUrl = prefs.getString("lock_page_url", "") ?: getHomeUrl()
            web.post { if (web.canGoBack()) web.goBack() else web.loadUrl(lockedUrl) }
            return
        }
        if (prefs.getBoolean("media_viewer", true)) {
            val media = detectMediaViewerUrl(url)
            if (media != null) {
                // SPA route already changed under us; snap back to where we were, then show
                // the media in our own viewer using the current (already-loaded) page's DOM.
                val extractJs = if (media == "video")
                    "(function(){var v=document.querySelector('video');if(v&&v.currentSrc)return v.currentSrc;if(v&&v.src)return v.src;var og=document.querySelector('meta[property=\"og:video\"],meta[property=\"og:video:secure_url\"]');return og?og.content:'';})();"
                else
                    "(function(){var og=document.querySelector('meta[property=\"og:image\"]');if(og&&og.content)return og.content;var img=document.querySelector('img[data-visualcompletion=\"media-vc-image\"]')||document.querySelector('[role=\"main\"] img');return img?img.src:'';})();"
                web.evaluateJavascript(extractJs) { result ->
                    val raw = result?.trim('"') ?: ""
                    val mediaUrl = raw.replace("\\u002F", "/").replace("\\/", "/")
                    if (web.canGoBack()) web.goBack()
                    if (mediaUrl.isNotEmpty() && mediaUrl.startsWith("http")) {
                        tapHandler.postDelayed({ showMediaViewer(media, mediaUrl) }, 150)
                    } else {
                        extractMediaViaHiddenWebView(url, media)
                    }
                }
                return
            }
        }
        if (handleNavigation(url)) {
            web.post {
                web.stopLoading()
                web.loadUrl("https://www.facebook.com/")
                Toast.makeText(this, "تم إرجاعك للصفحة الرئيسية (تنقّل ممنوع)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractGroupId(url: String): String? {
        val regex = Regex("facebook\\.com/groups/([^/?&#]+)")
        val match = regex.find(url)
        return match?.groupValues?.get(1)
    }

    private fun isWithinScheduledHours(): Boolean {
        if (!prefs.getBoolean("schedule_enabled", false)) return true
        if (scheduleBypassed) return true
        val start = prefs.getInt("schedule_start_hour", 0)
        val end = prefs.getInt("schedule_end_hour", 24)
        if (start == end) return true
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start < end) hour in start until end else (hour >= start || hour < end)
    }

    private fun showScheduleBlockedScreen() {
        val start = prefs.getInt("schedule_start_hour", 0)
        val end = prefs.getInt("schedule_end_hour", 24)
        AlertDialog.Builder(this)
            .setTitle("غير متاح الآن")
            .setMessage("التطبيق متاح فقط من الساعة %02d:00 إلى الساعة %02d:00.".format(start, end))
            .setCancelable(false)
            .setPositiveButton("إدخال كلمة المرور") { _, _ ->
                checkPasswordThen {
                    scheduleBypassed = true
                    usageRunning = true
                    usageHandler.post(usageTick)
                }
            }
            .setNegativeButton("إغلاق التطبيق") { _, _ -> finish() }
            .show()
    }

    private fun checkAndTickUsage() {
        val limit = prefs.getInt("daily_limit_minutes", 0)
        if (limit <= 0) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString("usage_date", "")
        var seconds = prefs.getInt("usage_seconds", 0)
        if (savedDate != today) { seconds = 0; prefs.edit().putString("usage_date", today).putInt("usage_seconds", 0).apply() }
        seconds += 60
        prefs.edit().putInt("usage_seconds", seconds).apply()
        if (seconds >= limit * 60) { usageRunning = false; showLimitReachedScreen() }
    }

    private fun checkDailyLimitExceeded(): Boolean {
        val limit = prefs.getInt("daily_limit_minutes", 0)
        if (limit <= 0) return false
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val savedDate = prefs.getString("usage_date", "")
        val seconds = if (savedDate == today) prefs.getInt("usage_seconds", 0) else 0
        return seconds >= limit * 60
    }

    private fun showLimitReachedScreen() {
        AlertDialog.Builder(this)
            .setTitle("انتهى الوقت المسموح لليوم")
            .setMessage("لقد استنفدت الوقت المخصص لاستخدام فيسبوك اليوم. يمكنك المحاولة غدًا، أو إدخال كلمة المرور لتمديد الوقت.")
            .setCancelable(false)
            .setPositiveButton("إدخال كلمة المرور") { _, _ ->
                checkPasswordThen {
                    prefs.edit().putInt("usage_seconds", 0).apply()
                    usageRunning = true
                    usageHandler.post(usageTick)
                }
            }
            .setNegativeButton("إغلاق التطبيق") { _, _ -> finish() }
            .show()
    }

    private fun applyScreenshotProtection() {
        if (prefs.getBoolean("block_screenshot", false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun applyCopyProtection() {
        if (prefs.getBoolean("block_copy", false)) {
            web.isLongClickable = false
            web.setOnLongClickListener { true }
        } else {
            web.isLongClickable = true
            web.setOnLongClickListener(null)
        }
    }

    private fun checkPasswordThen(action: () -> Unit) {
        val saved = prefs.getString("app_password", null)
        if (saved.isNullOrEmpty()) {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            input.hint = "اختر رمز PIN من 4 أرقام"
            AlertDialog.Builder(this)
                .setTitle("تعيين رمز الحماية")
                .setMessage("هذه أول مرة، اختر رمز PIN لحماية الإعدادات مستقبلاً")
                .setView(input)
                .setPositiveButton("حفظ") { _, _ ->
                    val pwd = input.text.toString()
                    if (pwd.length >= 4) { prefs.edit().putString("app_password", pwd).apply(); action() }
                    else Toast.makeText(this, "الرمز يجب أن يكون 4 أرقام على الأقل", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        } else {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            input.hint = "أدخل رمز PIN"
            AlertDialog.Builder(this)
                .setTitle("الإعدادات محمية")
                .setView(input)
                .setPositiveButton("دخول") { _, _ ->
                    if (input.text.toString() == saved) action()
                    else Toast.makeText(this, "رمز خاطئ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyControls() {
        if (!web.settings.javaScriptEnabled) return
        val js = StringBuilder("(function(){var s='';")
        val blockAllButtons = prefs.getBoolean("All buttons",false)
        if (blockAllButtons) js.append("s+='button,[role=\\\"button\\\"],input[type=button],input[type=submit]{visibility:hidden!important;pointer-events:none!important;}';")
        val map = mapOf("Like / Reactions" to "a[href*='/reaction/'],a[href*='/ufi/reaction'],[aria-label='Like' i],[aria-label='React' i]", "Comments" to "a[href*='comment'],[aria-label*='Comment' i]", "Share" to "a[href*='share'],[aria-label*='Share' i]", "Search" to "a[href*='search'],input[placeholder*='Search' i]", "Messenger" to "a[href*='messages'],a[href*='messenger']", "Stories" to "a[href*='stories']", "Reels / Watch" to "a[href*='reel'],a[href*='watch']", "Marketplace" to "a[href*='marketplace']", "Follow / Friends" to "a[href*='/friends/'],a[href*='add_friend'],a[href*='subscribe'],a[href*='unsubscribe'],[aria-label='Follow' i],[aria-label='Add Friend' i],[aria-label*='Follow' i],[aria-label*='متابعة'],[aria-label*='إضافة صديق']")
        map.forEach { (k,sel) -> if(prefs.getBoolean(k,false)) js.append("s+=`").append(sel).append("{display:none!important;pointer-events:none!important;}`;") }
        if (blockAllButtons) js.append("s+='textarea,[contenteditable=\\\"true\\\"],div[role=\\\"textbox\\\"]{pointer-events:none!important;opacity:0.5!important;caret-color:transparent!important;}';")
        if (prefs.getBoolean("dark_mode", false)) js.append("s+='html{filter:invert(1) hue-rotate(180deg) !important;} img,video,iframe{filter:invert(1) hue-rotate(180deg) !important;}';")
        if (prefs.getBoolean("block_images", false)) js.append("s+='img,svg image{visibility:hidden!important;}';")
        if (prefs.getBoolean("block_videos", false)) js.append("s+='video{visibility:hidden!important;}';")
        if (prefs.getBoolean("block_join_group", false)) js.append("s+=`[aria-label='Join Group' i],[aria-label*='Join Group' i],[aria-label*='انضمام'],div[role='button'][aria-label*='انضم']{display:none!important;pointer-events:none!important;}`;")
        if (prefs.getBoolean("block_video_swipe", false)) js.append("s+='video,[data-pagelet*=\\\"Reel\\\" i],[role=\\\"main\\\"] video{touch-action:none!important;}body.slim-reel-lock{touch-action:pan-x!important;overflow:hidden!important;}';")
        if (prefs.getBoolean("block_top_nav", false)) js.append("s+='[role=\\\"tablist\\\"],[role=\\\"tablist\\\"] *{visibility:hidden!important;pointer-events:none!important;}';")
        if (prefs.getBoolean("block_copy", false)) js.append("s+='*{-webkit-user-select:none!important;user-select:none!important;}';")
        val freezePage = prefs.getBoolean("freeze_page", false)
        val imagesBlocked = prefs.getBoolean("block_images", false)
        val videosBlocked = prefs.getBoolean("block_videos", false)
        if (freezePage) {
            js.append("s+='body *{pointer-events:none!important;}';")
            if (!imagesBlocked) js.append("s+='img,svg image{pointer-events:auto!important;}';")
            if (!videosBlocked) js.append("s+='video{pointer-events:auto!important;}';")
        }
        js.append("var st=document.getElementById('slimstyle-tag')||document.createElement('style');st.id='slimstyle-tag';st.textContent=s;document.head.appendChild(st);")
        js.append("if(/\\/(reel|watch)/i.test(location.pathname)){document.body.classList.add('slim-reel-lock');}else{document.body.classList.remove('slim-reel-lock');}")
        js.append("window.__slimVisitorMode=").append(blockAllButtons || freezePage).append(";")
        js.append("function __slimLockInputs(){if(!window.__slimVisitorMode)return;document.querySelectorAll('textarea,div[role=\\\"textbox\\\"]').forEach(function(el){try{el.setAttribute('readonly','readonly');el.setAttribute('disabled','disabled');}catch(e){}});document.querySelectorAll('[contenteditable=\\\"true\\\"]').forEach(function(el){try{el.setAttribute('contenteditable','false');}catch(e){}});}")
        js.append("__slimLockInputs();")
        js.append("if(!window.__slimInputGuard){window.__slimInputGuard=true;new MutationObserver(__slimLockInputs).observe(document.body,{childList:true,subtree:true});document.addEventListener('keydown',function(e){if(window.__slimVisitorMode&&e.key==='Enter'){var t=e.target;if(t&&(t.tagName==='TEXTAREA'||t.isContentEditable||t.getAttribute('role')==='textbox')){e.preventDefault();e.stopPropagation();}}},true);}")
        js.append("if(!window.__slimSwipeGuard){window.__slimSwipeGuard=true;document.addEventListener('touchmove',function(e){if(window.__slimBlockSwipe){var onReelPage=/\\/(reel|watch)/i.test(location.pathname);var t=e.target.closest('video,[data-pagelet*=\\\"Reel\\\" i],[aria-label*=\\\"Reel\\\" i],[role=\\\"main\\\"] video');if(t||onReelPage){e.preventDefault();}}},{passive:false});}")
        js.append("window.__slimBlockSwipe=").append(prefs.getBoolean("block_video_swipe", false)).append(";")
        js.append("if(!window.__slimVideoTracker){window.__slimVideoTracker=true;function slimHook(v){if(v.__slimHooked)return;v.__slimHooked=true;v.addEventListener('play',function(){if(window.SlimBridge)SlimBridge.setPlaying(true);window.__slimVideoPlaying=true;});v.addEventListener('pause',function(){if(window.SlimBridge)SlimBridge.setPlaying(false);window.__slimVideoPlaying=false;});v.addEventListener('ended',function(){if(window.SlimBridge)SlimBridge.setPlaying(false);window.__slimVideoPlaying=false;});}document.querySelectorAll('video').forEach(slimHook);new MutationObserver(function(){document.querySelectorAll('video').forEach(slimHook);}).observe(document.body,{childList:true,subtree:true});}")
        js.append("window.__slimBlockRefresh=").append(prefs.getBoolean("block_refresh", false)).append(";window.__slimBlockRefreshOnVideo=").append(prefs.getBoolean("block_refresh_on_video", false)).append(";")
        js.append("if(!window.__slimReloadGuard){window.__slimReloadGuard=true;try{var _rl=location.reload.bind(location);location.reload=function(){if(window.__slimBlockRefresh){if(window.SlimBridge)SlimBridge.checkNav(location.href);return;}_rl();};}catch(e){}try{var _go=history.go.bind(history);history.go=function(n){if((n===0||n===undefined)&&window.__slimBlockRefresh){return;}_go(n);};}catch(e){}}")
        js.append("if(!window.__slimPullGuard){window.__slimPullGuard=true;var __slimStartY=0;document.addEventListener('touchstart',function(e){__slimStartY=e.touches[0].clientY;},{passive:true,capture:true});document.addEventListener('touchmove',function(e){var blocked=window.__slimBlockRefresh||(window.__slimBlockRefreshOnVideo&&window.__slimVideoPlaying);if(blocked&&window.scrollY<=2&&e.touches[0].clientY>__slimStartY+3){e.preventDefault();}},{passive:false,capture:true});}")
        js.append("if(!window.__slimSpaGuard){window.__slimSpaGuard=true;window.__slimLastUrl=location.href;function slimCheckSpa(){if(location.href!==window.__slimLastUrl){window.__slimLastUrl=location.href;if(window.SlimBridge)SlimBridge.checkNav(location.href);}}var _ps=history.pushState;history.pushState=function(){_ps.apply(history,arguments);slimCheckSpa();};var _rs=history.replaceState;history.replaceState=function(){_rs.apply(history,arguments);slimCheckSpa();};window.addEventListener('popstate',slimCheckSpa);setInterval(slimCheckSpa,600);}")
        val keywords = getKeywordList()
        val kwJson = "[" + keywords.joinToString(",") { JSONObject.quote(it) } + "]"
        js.append("window.__slimKeywords=").append(kwJson).append(";")
        js.append("function __slimNormalizeAr(s){return (s||'').replace(/[\\u064B-\\u065F\\u0670]/g,'').replace(/[\\u0622\\u0623\\u0625]/g,'\\u0627').replace(/\\u0649/g,'\\u064A').replace(/\\u0629/g,'\\u0647').replace(/\\u0624/g,'\\u0648').replace(/\\u0626/g,'\\u064A').toLowerCase();}")
        js.append("function __slimScanKeywords(){if(!window.__slimKeywords||!window.__slimKeywords.length)return;var t=__slimNormalizeAr(document.body.innerText||'');for(var i=0;i<window.__slimKeywords.length;i++){var k=__slimNormalizeAr(window.__slimKeywords[i]);if(k&&t.indexOf(k)!==-1){if(window.SlimBridge)SlimBridge.keywordRedirect();return;}}}")
        js.append("if(!window.__slimKeywordGuard){window.__slimKeywordGuard=true;var __slimKwTimer=null;new MutationObserver(function(){clearTimeout(__slimKwTimer);__slimKwTimer=setTimeout(__slimScanKeywords,300);}).observe(document.body,{childList:true,subtree:true,characterData:true});}")
        js.append("__slimScanKeywords();")
        val btnWords = getButtonBlockWords() + getBuiltInButtonWords()
        val btnWordsJson = "[" + btnWords.joinToString(",") { JSONObject.quote(it) } + "]"
        js.append("window.__slimButtonWords=").append(btnWordsJson).append(";")
        js.append("function __slimBtnMatch(txt){if(!window.__slimButtonWords||!window.__slimButtonWords.length)return false;var t=__slimNormalizeAr((txt||'').trim());if(!t)return false;for(var i=0;i<window.__slimButtonWords.length;i++){var w=__slimNormalizeAr(window.__slimButtonWords[i]);if(w&&t.indexOf(w)!==-1)return true;}return false;}")
        js.append("function __slimScanButtonWords(){if(!window.__slimButtonWords||!window.__slimButtonWords.length)return;var els=document.querySelectorAll('a,button,div[role=\\\"button\\\"],span[role=\\\"button\\\"],div[role=\\\"link\\\"],span[role=\\\"link\\\"]');for(var i=0;i<els.length;i++){var el=els[i];if(el.__slimBtnHidden)continue;var txt=(el.textContent||'').trim();if(txt.length>0&&txt.length<40&&__slimBtnMatch(txt)){el.style.setProperty('display','none','important');el.style.setProperty('pointer-events','none','important');el.__slimBtnHidden=true;}}}")
        js.append("if(!window.__slimBtnGuard){window.__slimBtnGuard=true;var __slimBtnTimer=null;new MutationObserver(function(){clearTimeout(__slimBtnTimer);__slimBtnTimer=setTimeout(__slimScanButtonWords,300);}).observe(document.body,{childList:true,subtree:true,characterData:true});document.addEventListener('click',function(e){if(!window.__slimButtonWords||!window.__slimButtonWords.length)return;var el=e.target;for(var d=0;d<4&&el;d++){var txt=(el.textContent||'').trim();if(txt.length>0&&txt.length<40&&__slimBtnMatch(txt)){e.preventDefault();e.stopPropagation();el.style.setProperty('display','none','important');el.__slimBtnHidden=true;return;}el=el.parentElement;}},true);}")
        js.append("__slimScanButtonWords();")
        js.append("window.__slimBlockCopy=").append(prefs.getBoolean("block_copy", false)).append(";")
        js.append("if(!window.__slimCopyGuard){window.__slimCopyGuard=true;['copy','cut','contextmenu'].forEach(function(evt){document.addEventListener(evt,function(e){if(window.__slimBlockCopy){e.preventDefault();e.stopPropagation();}},true);});}")
        js.append("})();")
        web.evaluateJavascript(js.toString(),null)
    }

    private fun showControls() {
        val box=LinearLayout(this); box.orientation=LinearLayout.VERTICAL; box.setPadding(32,12,32,8)
        val title=TextView(this); title.text="سليم سوشيال • إعدادات فيسبوك"; title.textSize=20f; title.setTextColor(Color.DKGRAY); title.setPadding(0,8,0,18); box.addView(title)

        val counter = TextView(this); counter.text="المحاولات المحظورة: ${prefs.getInt("blocked_count",0)}"; counter.setPadding(0,0,0,16); box.addView(counter)

        blocks.forEach { name -> val sw=Switch(this); sw.text=arabicLabels[name] ?: name; sw.textSize=16f; sw.isChecked=prefs.getBoolean(name,false); sw.setPadding(0,10,0,10); sw.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean(name,v).apply(); if(!isAuth(web.url ?: "")) applyControls() }; box.addView(sw) }

        val swMessageBtn = Switch(this); swMessageBtn.text="مراسلة (زر مراسلة الصفحات)"; swMessageBtn.textSize=16f; swMessageBtn.isChecked=prefs.getBoolean("block_message_btn",false); swMessageBtn.setPadding(0,10,0,10)
        swMessageBtn.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_message_btn",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swMessageBtn)

        val sep1 = TextView(this); sep1.text="— خيارات إضافية —"; sep1.setPadding(0,20,0,10); sep1.setTextColor(Color.GRAY); box.addView(sep1)

        val swImages = Switch(this); swImages.text="منع عرض الصور"; swImages.isChecked=prefs.getBoolean("block_images",false)
        swImages.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_images",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swImages)

        val swVideos = Switch(this); swVideos.text="منع عرض الفيديوهات"; swVideos.isChecked=prefs.getBoolean("block_videos",false)
        swVideos.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_videos",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swVideos)

        val swSwipe = Switch(this); swSwipe.text="منع سحب الشاشة للتنقل بين الفيديوهات"; swSwipe.isChecked=prefs.getBoolean("block_video_swipe",false)
        swSwipe.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_video_swipe",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swSwipe)

        val swVideoSwipeCombo = Switch(this); swVideoSwipeCombo.text="منع الفيديو والسحب معًا (تفعيل الاثنين دفعة واحدة)"; swVideoSwipeCombo.isChecked = prefs.getBoolean("block_videos",false) && prefs.getBoolean("block_video_swipe",false)
        swVideoSwipeCombo.setOnCheckedChangeListener { _,v ->
            prefs.edit().putBoolean("block_videos",v).putBoolean("block_video_swipe",v).apply()
            swVideos.isChecked = v
            swSwipe.isChecked = v
            if(!isAuth(web.url ?: "")) applyControls()
        }
        box.addView(swVideoSwipeCombo)

        val swProfileNav = Switch(this); swProfileNav.text="منع زيارة أي بروفايل / صفحة / مجموعة"; swProfileNav.isChecked=prefs.getBoolean("block_profile_nav",false)
        swProfileNav.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_profile_nav",v).apply() }
        box.addView(swProfileNav)

        val swTopNav = Switch(this); swTopNav.text="إخفاء القائمة العلوية لفيسبوك (أينما كانت)"; swTopNav.isChecked=prefs.getBoolean("block_top_nav",false)
        swTopNav.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_top_nav",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swTopNav)

        val swFreeze = Switch(this); swFreeze.text="تجميد الصفحة بالكامل (تمرير فقط + فتح الصور/الفيديوهات المسموحة)"; swFreeze.isChecked=prefs.getBoolean("freeze_page",false)
        swFreeze.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("freeze_page",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swFreeze)

        val swMediaViewer = Switch(this); swMediaViewer.text="فتح الصور والفيديوهات داخل التطبيق (منع عارض فيسبوك الخارجي بأزراره)"; swMediaViewer.isChecked=prefs.getBoolean("media_viewer",true)
        swMediaViewer.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("media_viewer",v).apply() }
        box.addView(swMediaViewer)

        val swExternal = Switch(this); swExternal.text="منع الروابط الخارجية"; swExternal.isChecked=prefs.getBoolean("block_external",false)
        swExternal.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_external",v).apply() }
        box.addView(swExternal)

        val swGroups = Switch(this); swGroups.text="منع مجموعات غير مشترك فيها (استثناء يدوي)"; swGroups.isChecked=prefs.getBoolean("block_unjoined_groups",false)
        swGroups.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_unjoined_groups",v).apply() }
        box.addView(swGroups)

        val whitelistLabel = TextView(this); whitelistLabel.text="قائمة المجموعات/الصفحات المسموحة (افصل بفاصلة ,)"; whitelistLabel.setPadding(0,10,0,4); box.addView(whitelistLabel)
        val whitelistInput = EditText(this); whitelistInput.setText(prefs.getString("group_whitelist","")); box.addView(whitelistInput)

        val allowGroupBtn = TextView(this); allowGroupBtn.text="➕ إضافة المجموعة/الصفحة الحالية للقائمة أعلاه"; allowGroupBtn.setTextColor(Color.BLUE); allowGroupBtn.setPadding(0,6,0,10)
        allowGroupBtn.setOnClickListener {
            val currentUrl = web.url ?: ""
            val id = extractIdentifier(currentUrl.lowercase())
            if (id != null) {
                val current = whitelistInput.text.toString()
                val list = current.split(",").map{it.trim()}.filter{it.isNotEmpty()}.toMutableList()
                if (!list.contains(id)) list.add(id)
                whitelistInput.setText(list.joinToString(","))
                Toast.makeText(this,"أُضيفت للقائمة، لا تنسَ الضغط على حفظ",Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this,"لا يمكن التعرف على هذه الصفحة",Toast.LENGTH_SHORT).show()
        }
        box.addView(allowGroupBtn)

        val sepKeywords = TextView(this); sepKeywords.text="— كلمات تُعيد التوجيه للرئيسية فورًا —"; sepKeywords.setPadding(0,16,0,10); sepKeywords.setTextColor(Color.GRAY); box.addView(sepKeywords)
        val keywordsLabel = TextView(this); keywordsLabel.text="إذا ظهرت أي من هذه الكلمات في نص الصفحة، يتم الرجوع للرئيسية تلقائيًا (افصل بفاصلة ,)"; keywordsLabel.setPadding(0,0,0,4); box.addView(keywordsLabel)
        val keywordsInput = EditText(this); keywordsInput.hint="مثال: كلمة1, كلمة2"; keywordsInput.setText(prefs.getString("keyword_blocklist","")); box.addView(keywordsInput)

        val sepBtnWords = TextView(this); sepBtnWords.text="— حظر أزرار حسب نصّها بالضبط —"; sepBtnWords.setPadding(0,16,0,10); sepBtnWords.setTextColor(Color.GRAY); box.addView(sepBtnWords)
        val btnWordsLabel = TextView(this); btnWordsLabel.text="اكتب النص أو جزء منه كما يظهر على الزر (مثل: متابعة، انضمام) — أي عنصر نصّه يحتوي على هذه الكلمة يُخفى ويُمنع النقر عليه (افصل بفاصلة ,)"; btnWordsLabel.setPadding(0,0,0,4); box.addView(btnWordsLabel)
        val btnWordsInput = EditText(this); btnWordsInput.hint="مثال: متابعة, انضمام"; btnWordsInput.setText(prefs.getString("button_block_words","")); box.addView(btnWordsInput)

        val swRefresh = Switch(this); swRefresh.text="منع تحديث الصفحة بالكامل"; swRefresh.isChecked=prefs.getBoolean("block_refresh",false)
        swRefresh.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_refresh",v).apply() }
        box.addView(swRefresh)

        val swRefreshVideo = Switch(this); swRefreshVideo.text="منع التحديث أثناء تشغيل فيديو فقط"; swRefreshVideo.isChecked=prefs.getBoolean("block_refresh_on_video",false)
        swRefreshVideo.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_refresh_on_video",v).apply() }
        box.addView(swRefreshVideo)

        val swDark = Switch(this); swDark.text="وضع داكن إجباري"; swDark.isChecked=prefs.getBoolean("dark_mode",false)
        swDark.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("dark_mode",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swDark)

        val swHide = Switch(this); swHide.text="إخفاء شريط الأدوات (اضغط 3 مرات متتالية لإظهاره)"; swHide.isChecked=prefs.getBoolean("hide_toolbar",false)
        swHide.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("hide_toolbar",v).apply(); applyToolbarVisibility(findViewById(R.id.menu), findViewById(R.id.reload)) }
        box.addView(swHide)

        val limitLabel = TextView(this); limitLabel.text="الحد اليومي (بالدقائق، 0 = بلا حد)"; limitLabel.setPadding(0,16,0,4); box.addView(limitLabel)
        val limitInput = EditText(this); limitInput.inputType = InputType.TYPE_CLASS_NUMBER; limitInput.setText(prefs.getInt("daily_limit_minutes",0).toString()); box.addView(limitInput)

        val sepSchedule = TextView(this); sepSchedule.text="— جدولة أوقات الاستخدام —"; sepSchedule.setPadding(0,16,0,10); sepSchedule.setTextColor(Color.GRAY); box.addView(sepSchedule)
        val swSchedule = Switch(this); swSchedule.text="تفعيل الجدولة (السماح بالاستخدام في نطاق ساعات محدد فقط)"; swSchedule.isChecked=prefs.getBoolean("schedule_enabled",false)
        swSchedule.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("schedule_enabled",v).apply() }
        box.addView(swSchedule)
        val startLabel = TextView(this); startLabel.text="من الساعة (0-23)"; startLabel.setPadding(0,8,0,4); box.addView(startLabel)
        val startInput = EditText(this); startInput.inputType = InputType.TYPE_CLASS_NUMBER; startInput.setText(prefs.getInt("schedule_start_hour",8).toString()); box.addView(startInput)
        val endLabel = TextView(this); endLabel.text="إلى الساعة (0-24)"; endLabel.setPadding(0,8,0,4); box.addView(endLabel)
        val endInput = EditText(this); endInput.inputType = InputType.TYPE_CLASS_NUMBER; endInput.setText(prefs.getInt("schedule_end_hour",22).toString()); box.addView(endInput)

        val sepPrivacy = TextView(this); sepPrivacy.text="— خصوصية إضافية —"; sepPrivacy.setPadding(0,16,0,10); sepPrivacy.setTextColor(Color.GRAY); box.addView(sepPrivacy)
        val swCopy = Switch(this); swCopy.text="منع نسخ النصوص من الصفحة"; swCopy.isChecked=prefs.getBoolean("block_copy",false)
        swCopy.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_copy",v).apply(); applyCopyProtection(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swCopy)
        val swScreenshot = Switch(this); swScreenshot.text="منع لقطة الشاشة وتسجيل الشاشة"; swScreenshot.isChecked=prefs.getBoolean("block_screenshot",false)
        swScreenshot.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_screenshot",v).apply(); applyScreenshotProtection() }
        box.addView(swScreenshot)
        val swJoinGroup = Switch(this); swJoinGroup.text="منع الانضمام إلى مجموعات"; swJoinGroup.isChecked=prefs.getBoolean("block_join_group",false)
        swJoinGroup.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_join_group",v).apply(); if(!isAuth(web.url ?: "")) applyControls() }
        box.addView(swJoinGroup)
        val swLockPage = Switch(this); swLockPage.text="قفل الصفحة الحالية (منع أي تنقّل خارجها فورًا، بدون أي رسالة)"; swLockPage.isChecked=prefs.getBoolean("lock_page_enabled",false)
        swLockPage.setOnCheckedChangeListener { _,v ->
            if (v) prefs.edit().putBoolean("lock_page_enabled",true).putString("lock_page_url", web.url ?: getHomeUrl()).apply()
            else prefs.edit().putBoolean("lock_page_enabled",false).apply()
        }
        box.addView(swLockPage)

        val swJs = Switch(this); swJs.text="تعطيل JavaScript بالكامل ⚠️ (يعطّل باقي الخيارات ومعظم فيسبوك)"; swJs.isChecked=prefs.getBoolean("disable_js",false)
        swJs.setOnCheckedChangeListener { _,v ->
            if (v) {
                AlertDialog.Builder(this).setTitle("تحذير")
                    .setMessage("تعطيل JavaScript سيوقف كل خيارات الحظر الأخرى وقد يمنع فيسبوك من العمل نهائيًا. هل تريد المتابعة؟")
                    .setPositiveButton("نعم، عطّله"){_,_-> prefs.edit().putBoolean("disable_js",true).apply(); Toast.makeText(this,"أعد تشغيل التطبيق لتفعيل التغيير",Toast.LENGTH_LONG).show() }
                    .setNegativeButton("إلغاء"){_,_-> swJs.isChecked=false}.show()
            } else { prefs.edit().putBoolean("disable_js",false).apply(); Toast.makeText(this,"أعد تشغيل التطبيق لتفعيل التغيير",Toast.LENGTH_LONG).show() }
        }
        box.addView(swJs)

        val custom=EditText(this); custom.hint="CSS مخصص (اختياري)"; custom.setText(prefs.getString("css","")); box.addView(custom)
        val jsBox=EditText(this); jsBox.hint="JavaScript مخصص (اختياري)"; jsBox.setText(prefs.getString("js","")); box.addView(jsBox)

        val sepHome = TextView(this); sepHome.text="— الصفحة الرئيسية والصفحات —"; sepHome.setPadding(0,20,0,10); sepHome.setTextColor(Color.GRAY); box.addView(sepHome)

        val homeLabel = TextView(this); homeLabel.text="رابط الصفحة الرئيسية"; homeLabel.setPadding(0,4,0,4); box.addView(homeLabel)
        val homeInput = EditText(this); homeInput.setText(getHomeUrl()); box.addView(homeInput)

        val pagesLabel = TextView(this); pagesLabel.text="الصفحات الإضافية (اضغط 🏠 مطوّلاً في الشريط للتنقل بينها)"; pagesLabel.setPadding(0,16,0,4); box.addView(pagesLabel)
        val pagesContainer = LinearLayout(this); pagesContainer.orientation = LinearLayout.VERTICAL; box.addView(pagesContainer)

        val currentPages = getCustomPages()

        fun refreshPagesList() {
            pagesContainer.removeAllViews()
            currentPages.forEachIndexed { index, pair ->
                val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.setPadding(0,4,0,4)
                val label = TextView(this); label.text = "${pair.first}\n${pair.second}"; label.textSize = 13f
                label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(label)
                val delBtn = TextView(this); delBtn.text = "✕"; delBtn.setTextColor(Color.RED); delBtn.setPadding(20,0,10,0)
                delBtn.setOnClickListener { currentPages.removeAt(index); refreshPagesList() }
                row.addView(delBtn)
                pagesContainer.addView(row)
            }
        }
        refreshPagesList()

        val newNameInput = EditText(this); newNameInput.hint = "اسم الصفحة الجديدة"; box.addView(newNameInput)
        val newUrlInput = EditText(this); newUrlInput.hint = "رابط الصفحة الجديدة (https://...)"; box.addView(newUrlInput)
        val addPageBtn = TextView(this); addPageBtn.text = "➕ إضافة صفحة جديدة للقائمة"; addPageBtn.setTextColor(Color.BLUE); addPageBtn.setPadding(0,6,0,10)
        addPageBtn.setOnClickListener {
            val name = newNameInput.text.toString().trim()
            var url = newUrlInput.text.toString().trim()
            if (name.isEmpty() || url.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال الاسم والرابط", Toast.LENGTH_SHORT).show()
            } else {
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                currentPages.add(name to url)
                newNameInput.setText("")
                newUrlInput.setText("")
                refreshPagesList()
            }
        }
        box.addView(addPageBtn)

        val changePwd = TextView(this); changePwd.text="تغيير رمز PIN"; changePwd.setTextColor(Color.BLUE); changePwd.setPadding(0,20,0,0)
        changePwd.setOnClickListener {
            val newInput = EditText(this)
            newInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            newInput.hint = "رمز PIN الجديد"
            AlertDialog.Builder(this).setTitle("تغيير رمز PIN").setView(newInput)
                .setPositiveButton("حفظ"){_,_-> if(newInput.text.toString().length>=4) { prefs.edit().putString("app_password", newInput.text.toString()).apply(); Toast.makeText(this,"تم التغيير",Toast.LENGTH_SHORT).show() } else Toast.makeText(this,"4 أرقام على الأقل",Toast.LENGTH_SHORT).show() }
                .setNegativeButton("إلغاء",null).show()
        }
        box.addView(changePwd)

        AlertDialog.Builder(this).setView(ScrollView(this).apply{addView(box)}).setPositiveButton("حفظ"){_,_->
            var homeUrl = homeInput.text.toString().trim()
            if (homeUrl.isEmpty()) homeUrl = "https://www.facebook.com/"
            if (!homeUrl.startsWith("http://") && !homeUrl.startsWith("https://")) homeUrl = "https://$homeUrl"
            prefs.edit()
                .putString("css",custom.text.toString())
                .putString("js",jsBox.text.toString())
                .putString("group_whitelist", whitelistInput.text.toString())
                .putString("keyword_blocklist", keywordsInput.text.toString())
                .putString("button_block_words", btnWordsInput.text.toString())
                .putInt("daily_limit_minutes", limitInput.text.toString().toIntOrNull() ?: 0)
                .putInt("schedule_start_hour", (startInput.text.toString().toIntOrNull() ?: 0).coerceIn(0,23))
                .putInt("schedule_end_hour", (endInput.text.toString().toIntOrNull() ?: 24).coerceIn(0,24))
                .putString("home_url", homeUrl)
                .apply()
            saveCustomPages(currentPages)
            applyCustom()
        }.setNegativeButton("إغلاق",null).show()
    }

    // "image" or "video" if this URL looks like Facebook's own photo/video viewer, else null.
    private fun detectMediaViewerUrl(url: String): String? {
        val u = url.lowercase()
        if (!(u.contains("facebook.com") || u.contains("fbcdn.net"))) return null
        if (isAuth(u)) return null
        val photoPatterns = listOf("/photo.php", "/photo/", "/photos/", "fbid=")
        val videoPatterns = listOf("/videos/", "/video.php", "/reel/", "watch/?v=", "watch?v=")
        if (videoPatterns.any { u.contains(it) }) return "video"
        if (photoPatterns.any { u.contains(it) }) return "image"
        return null
    }

    // Loads the viewer URL in an off-screen WebView just long enough to pull the direct
    // media src out of the DOM, then shows it in our own minimal viewer. The hidden WebView
    // never becomes visible, so Facebook's like/comment/share/nav chrome is never shown.
    @SuppressLint("SetJavaScriptEnabled")
    private fun extractMediaViaHiddenWebView(url: String, type: String) {
        val hidden = WebView(this)
        hidden.settings.javaScriptEnabled = true
        hidden.settings.userAgentString = web.settings.userAgentString
        hidden.visibility = View.GONE
        (findViewById<View>(android.R.id.content) as ViewGroup).addView(hidden, 0, 0)

        var finished = false
        fun cleanup() { (hidden.parent as? ViewGroup)?.removeView(hidden); hidden.destroy() }
        val timeoutRunnable = Runnable {
            if (!finished) {
                finished = true
                cleanup()
                Toast.makeText(this, "تعذّر فتح هذه الصورة/الفيديو داخل التطبيق", Toast.LENGTH_SHORT).show()
            }
        }
        tapHandler.postDelayed(timeoutRunnable, 8000)

        hidden.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = false
            override fun onPageFinished(v: WebView, pageUrl: String) {
                if (finished) return
                val extractJs = if (type == "video")
                    "(function(){var v=document.querySelector('video');if(v&&v.currentSrc)return v.currentSrc;if(v&&v.src)return v.src;var og=document.querySelector('meta[property=\"og:video\"],meta[property=\"og:video:secure_url\"]');return og?og.content:'';})();"
                else
                    "(function(){var og=document.querySelector('meta[property=\"og:image\"]');if(og&&og.content)return og.content;var img=document.querySelector('img[data-visualcompletion=\"media-vc-image\"]')||document.querySelector('[role=\"main\"] img');return img?img.src:'';})();"
                v.evaluateJavascript(extractJs) { result ->
                    if (finished) return@evaluateJavascript
                    finished = true
                    tapHandler.removeCallbacks(timeoutRunnable)
                    val raw = result?.trim('"') ?: ""
                    val mediaUrl = raw.replace("\\u002F", "/").replace("\\/", "/")
                    cleanup()
                    if (mediaUrl.isNotEmpty() && mediaUrl.startsWith("http")) {
                        showMediaViewer(type, mediaUrl)
                    } else {
                        Toast.makeText(this@MainActivity, "تعذّر فتح هذه الصورة/الفيديو داخل التطبيق", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        hidden.loadUrl(url)
    }

    // Minimal in-app viewer: one media item, one close button. No like/comment/share/nav chrome.
    private fun showMediaViewer(type: String, url: String) {
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)

        if (type == "video") {
            val videoView = VideoView(this)
            val controller = MediaController(this)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setVideoURI(Uri.parse(url))
            videoView.setOnPreparedListener { it.start() }
            videoView.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "تعذّر تشغيل الفيديو", Toast.LENGTH_SHORT).show(); true
            }
            container.addView(videoView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        } else {
            @SuppressLint("SetJavaScriptEnabled")
            val imgView = WebView(this)
            imgView.settings.javaScriptEnabled = false
            imgView.settings.builtInZoomControls = true
            imgView.settings.displayZoomControls = false
            imgView.settings.useWideViewPort = true
            imgView.settings.loadWithOverviewMode = true
            imgView.setBackgroundColor(Color.BLACK)
            imgView.isLongClickable = false
            imgView.setOnLongClickListener { true } // block Facebook/Android's save/share context menu
            val safeUrl = JSONObject.quote(url)
            val html = "<html><head><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<style>html,body{margin:0;background:#000;height:100%;display:flex;align-items:center;justify-content:center;} img{max-width:100%;height:auto;}</style>" +
                "</head><body><img src=$safeUrl oncontextmenu='return false;'></body></html>"
            imgView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            container.addView(imgView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val closeBtn = TextView(this)
        closeBtn.text = "✕"
        closeBtn.setTextColor(Color.WHITE)
        closeBtn.textSize = 22f
        closeBtn.setPadding(28, 20, 28, 20)
        val closeParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        closeParams.topMargin = 24; closeParams.rightMargin = 24
        container.addView(closeBtn, closeParams)

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(container)
        closeBtn.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            (container.getChildAt(0) as? VideoView)?.stopPlayback()
        }
        dialog.show()
    }

    private fun applyCustom(){
        if(isAuth(web.url ?: "") || !web.settings.javaScriptEnabled) return
        val css=prefs.getString("css","")!!; val js=prefs.getString("js","")!!
        web.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};document.head.appendChild(s);try{${js}}catch(e){}})();",null)
        applyControls()
    }

    override fun onBackPressed(){ if(web.canGoBack()) web.goBack() else super.onBackPressed() }
}
