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
    private var lastBlockedRedirect = 0L
    private var blockedPageBaseUrl: String? = null
    private var restrictedCustomPageUrl: String? = null
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
            fun openMedia(type: String, url: String, origin: String) {
                runOnUiThread {
                    // Never trust JavaScript alone. Media exceptions are valid only when
                    // the request originates from the exact restricted page and the
                    // requested media type is explicitly enabled.
                    val current = web.url ?: ""
                    val media = detectMediaViewerUrl(url)
                    val originOk = restrictedCustomPageUrl != null &&
                        normalizeUrl(origin) == restrictedCustomPageUrl &&
                        normalizeUrl(current) == restrictedCustomPageUrl
                    if (originOk && media == type && isMediaExceptionAllowed(origin, type)) {
                        showMediaViewer(type, url)
                    }
                }
            }
            @android.webkit.JavascriptInterface
            fun keywordRedirect() { runOnUiThread { redirectHomeForKeyword() } }
        }, "SlimBridge")
        // Never allow window.open()/target=_blank/JS popups to spawn a second WebView —
        // every link must flow through this WebView's own shouldOverrideUrlLoading, or it
        // completely bypasses every rule below. Explicit, not relying on WebView defaults.
        web.settings.setSupportMultipleWindows(false)
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
        web.webViewClient = object: WebViewClient() {
            // Re-checked on every navigation attempt (link tap, JS redirect, form submit,
            // back/forward, and — via the SlimBridge.checkNav hooks — SPA route changes,
            // location.reload(), history.go(0)). This is the single choke point for links.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val url = r.url.toString()
                val scheme = r.url.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") {
                    // Ignore app-deeplink / unsupported schemes (fb://, intent://, tel:, mailto:, etc.)
                    // so the WebView doesn't try to load them and show ERR_UNKNOWN_URL_SCHEME.
                    return true
                }
                if (!isAuth(url)) {
                    // Time/usage limits are enforced on EVERY link, not just the 60s background
                    // tick — otherwise a tap right as the window closes can slip through for up
                    // to a minute.
                    if (!isWithinScheduledHours()) { usageRunning = false; runOnUiThread { showScheduleBlockedScreen() }; return true }
                    if (checkDailyLimitExceeded()) { usageRunning = false; runOnUiThread { showLimitReachedScreen() }; return true }
                }
                if (isPageLocked(url)) return true
                if (isRefreshBlocked(url)) return true
                if (prefs.getBoolean("media_viewer", true)) {
                    val media = detectMediaViewerUrl(url)
                    if (media != null && isMediaExceptionAllowed(web.url ?: "", media)) {
                        extractMediaViaHiddenWebView(url, media)
                        return true
                    }
                }
                return handleNavigation(url)
            }
            // Defense-in-depth: if any navigation ever reaches the page-start stage without
            // going through shouldOverrideUrlLoading above (e.g. a server-side redirect chain
            // or a WebView-version quirk), catch it here too before content renders.
            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (restrictedCustomPageUrl != null && normalizeUrl(url) != restrictedCustomPageUrl) {
                    // Keep the origin only for a media route; any other destination ends
                    // the media-only session immediately.
                    if (detectMediaViewerUrl(url) == null) {
                        restrictedCustomPageUrl = null
                        blockedPageBaseUrl = null
                    }
                }
                if (isAuth(url)) return
                if (!isWithinScheduledHours()) { usageRunning = false; v.stopLoading(); runOnUiThread { showScheduleBlockedScreen() }; return }
                if (checkDailyLimitExceeded()) { usageRunning = false; v.stopLoading(); runOnUiThread { showLimitReachedScreen() }; return }
                if (isPageLocked(url)) {
                    v.stopLoading()
                    val lockedUrl = prefs.getString("lock_page_url", "") ?: getHomeUrl()
                    v.post { v.loadUrl(lockedUrl) }
                    return
                }
                if (handleNavigation(url)) {
                    v.stopLoading()
                    v.post { v.loadUrl(getHomeUrl()) }
                }
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
            // web.reload() re-fetches whatever is CURRENTLY loaded without ever calling
            // shouldOverrideUrlLoading — so it used to skip every rule below entirely,
            // including rules the admin may have just turned on while this page was open.
            val current = web.url ?: getHomeUrl()
            when {
                !isWithinScheduledHours() -> showScheduleBlockedScreen()
                checkDailyLimitExceeded() -> showLimitReachedScreen()
                prefs.getBoolean("block_refresh", false) -> notifyUser("تحديث الصفحة معطّل من الإعدادات")
                prefs.getBoolean("block_refresh_on_video", false) && videoPlaying -> notifyUser("تم منع التحديث أثناء تشغيل فيديو")
                isPageLocked(current) -> web.loadUrl(prefs.getString("lock_page_url", "") ?: getHomeUrl())
                handleNavigation(current) -> web.loadUrl(getHomeUrl())
                else -> web.reload()
            }
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
            notifyUser("لا توجد صفحات إضافية بعد. أضِفها من الإعدادات")
        }
        val names = pages.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("انتقل إلى صفحة")
            .setItems(names) { _, which -> web.loadUrl(pages[which].second) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun facebookHost(url: String): String? {
        return try {
            Uri.parse(url).host?.lowercase()?.trimEnd('.')
        } catch (_: Exception) {
            null
        }
    }

    private fun isFacebookHost(url: String): Boolean {
        val host = facebookHost(url) ?: return false
        return host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "fbcdn.net" || host.endsWith(".fbcdn.net")
    }

    private fun isAuth(url: String): Boolean {
        val host = facebookHost(url) ?: return false
        if (!(host == "facebook.com" || host.endsWith(".facebook.com"))) return false
        val path = try { Uri.parse(url).path?.lowercase() ?: "" } catch (_: Exception) { "" }
        return path == "/login" || path.startsWith("/login/") ||
            path == "/checkpoint" || path.startsWith("/checkpoint/") ||
            path == "/recover" || path.startsWith("/recover/") ||
            path == "/reg" || path.startsWith("/reg/") ||
            path == "/registration" || path.startsWith("/registration/")
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
        val host = facebookHost(u) ?: return false
        if (!(host == "facebook.com" || host.endsWith(".facebook.com"))) return false

        val path = try { Uri.parse(u).path?.lowercase() ?: "/" } catch (_: Exception) { return false }
        val segments = path.split("/").filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false

        if (segments[0] == "groups" || segments[0] == "pages" ||
            segments[0] == "people" || segments[0] == "profile.php") return true

        val reserved = setOf(
            "home.php","login.php","checkpoint","help","settings","notifications",
            "friends","photo.php","photos.php","story.php","permalink.php","sharer.php",
            "privacy","dialog","unified","l.php","messages","messenger","watch","reel",
            "marketplace","search","www","m","mbasic","touch","login","recover","reg",
            "registration"
        )
        return segments.size == 1 && segments[0] !in reserved
    }

    private fun normalizeUrl(u: String): String {
        return u.lowercase().substringBefore("?").substringBefore("#").trimEnd('/')
    }

    private fun isRefreshBlocked(url: String): Boolean {
        val isReload = normalizeUrl(url) == normalizeUrl(web.url ?: "")
        if (!isReload) return false
        if (prefs.getBoolean("block_refresh", false)) {
            runOnUiThread { notifyUser("تحديث الصفحة معطّل من الإعدادات") }
            incrementBlockedCount()
            return true
        }
        if (prefs.getBoolean("block_refresh_on_video", false) && videoPlaying) {
            runOnUiThread { notifyUser("تم منع التحديث أثناء تشغيل فيديو") }
            incrementBlockedCount()
            return true
        }
        return false
    }

    private fun getWhitelistSet(): List<String> {
        val raw = prefs.getString("group_whitelist", "") ?: ""
        return raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    private fun getListPref(key: String): List<String> {
        val raw = prefs.getString(key, "") ?: ""
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
        val u = normalizeUrl(url)
        val isFacebookDomain = isFacebookHost(url)

        // A restricted custom-block page is a media-only sandbox.
        // Only the explicitly enabled media route may leave it; every other route,
        // including profiles, people, pages and groups, is still checked/blocked.
        val restricted = restrictedCustomPageUrl
        if (restricted != null && normalizeUrl(web.url ?: "") == restricted &&
            normalizeUrl(url) != restricted) {
            val media = detectMediaViewerUrl(url)
            if (media != null && isMediaExceptionAllowed(web.url ?: "", media)) return false
            notifyUserFromAnyThread("تم منع التنقل من الصفحة المحظورة")
            incrementBlockedCount()
            return true
        }

        if (prefs.getBoolean("custom_block_enabled", false)) {
            val exceptions = getListPref("custom_block_exceptions")
            val isException = exceptions.any { matchesConfiguredUrlRule(url, it) }
            if (!isException) {
                val blocklist = getListPref("custom_block_domains")
                if (blocklist.any { it.isNotEmpty() && matchesConfiguredUrlRule(url, it) }) {
                    val allowImages = prefs.getBoolean("custom_block_allow_images", false)
                    val allowVideos = prefs.getBoolean("custom_block_allow_videos", false)
                    if (allowImages || allowVideos) {
                        restrictedCustomPageUrl = normalizeUrl(url)
                        blockedPageBaseUrl = normalizeUrl(url)
                    } else {
                        blockedPageBaseUrl = normalizeUrl(url)
                        notifyUserFromAnyThread("تم منع هذا الرابط (قائمة حظر مخصصة)")
                        incrementBlockedCount()
                        return true
                    }
                }
            }
        }

        if (prefs.getBoolean("block_external", false) && !isFacebookDomain) {
            notifyUserFromAnyThread("تم منع رابط خارجي")
            incrementBlockedCount()
            return true
        }

        if (prefs.getBoolean("block_profile_nav", false) &&
            isFacebookDomain && !isAuth(url) && isProfilePageOrGroupUrl(url)) {
            val id = extractIdentifier(url.lowercase())
            val whitelist = getWhitelistSet()
            if (id == null || !whitelist.contains(id)) {
                notifyUserFromAnyThread("تم منع زيارة هذا الملف الشخصي/الصفحة/المجموعة")
                incrementBlockedCount()
                return true
            }
        }

        if (prefs.getBoolean("block_unjoined_groups", false) && extractGroupId(url.lowercase()) != null) {
            val groupId = extractGroupId(url.lowercase())
            val allowed = getWhitelistSet()
            if (groupId != null && !allowed.contains(groupId)) {
                notifyUserFromAnyThread("مجموعة غير مسموح بها. أضفها من الإعدادات إن أردت السماح", Toast.LENGTH_LONG)
                incrementBlockedCount()
                return true
            }
        }

        return false
    }

    private fun matchesConfiguredUrlRule(url: String, rule: String): Boolean {
        val raw = rule.trim().lowercase()
        if (raw.isEmpty()) return false

        val candidate = try { Uri.parse(url) } catch (_: Exception) { return false }
        val candidateHost = candidate.host?.lowercase()?.trimEnd('.') ?: return false
        val candidatePath = (candidate.path ?: "/").lowercase().trimEnd('/').ifEmpty { "/" }

        // Exact URL/path rules are supported without allowing one arbitrary substring
        // to match unrelated Facebook routes.
        val normalizedRule = raw.removePrefix("https://").removePrefix("http://")
            .substringBefore("#").trimEnd('/')
        val ruleUri = try { Uri.parse("https://$normalizedRule") } catch (_: Exception) { null }
        val ruleHost = ruleUri?.host?.lowercase()?.trimEnd('.')
        val rulePath = (ruleUri?.path ?: "/").lowercase().trimEnd('/').ifEmpty { "/" }

        if (ruleHost != null) {
            if (candidateHost != ruleHost && !candidateHost.endsWith(".$ruleHost")) return false
            if (rulePath == "/") return true
            return candidatePath == rulePath || candidatePath.startsWith("$rulePath/")
        }

        // Fallback for simple legacy entries such as "/groups/123".
        return normalizedRule.startsWith("/") &&
            (candidatePath == normalizedRule || candidatePath.startsWith("$normalizedRule/"))
    }

    private fun isMediaExceptionAllowed(pageUrl: String, type: String): Boolean {
        val blocked = blockedPageBaseUrl ?: return false
        if (normalizeUrl(pageUrl) != blocked) return false
        return if (type == "image") prefs.getBoolean("custom_block_allow_images", false)
        else prefs.getBoolean("custom_block_allow_videos", false)
    }

    private fun redirectHomeForKeyword() {
        val now = System.currentTimeMillis()
        if (now - lastKeywordRedirect < 3000) return // guard against repeated triggers on the same page
        lastKeywordRedirect = now
        incrementBlockedCount()
        web.stopLoading()
        web.loadUrl(getHomeUrl())
        notifyUser("تم إرجاعك للرئيسية (الصفحة تحتوي على كلمة محظورة)")
    }

    // Guarantees a blocked SPA route is actually hidden and eventually cleared:
    // 1) blanks the page immediately (don't wait on the SPA to re-render correctly),
    // 2) attempts the configured redirect,
    // 3) verifies after a short delay — if the blocked URL is still current, forces a
    //    real page load (hard block) instead of leaving the content visible.
    // Debounced so a misbehaving SPA polling the same blocked URL doesn't spam toasts.
    private fun enforceLeave(blockedUrl: String, useBack: Boolean, fallbackUrl: String, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBlockedRedirect < 1500) return
        lastBlockedRedirect = now
        web.evaluateJavascript(
            "(function(){var o=document.getElementById('slim-block-overlay')||document.createElement('div');o.id='slim-block-overlay';o.style.cssText='position:fixed;inset:0;background:#000;z-index:2147483647;';document.documentElement.appendChild(o);})();",
            null
        )
        web.post {
            web.stopLoading()
            if (useBack && web.canGoBack()) web.goBack() else web.loadUrl(fallbackUrl)
            notifyUser(message)
            tapHandler.postDelayed({
                web.evaluateJavascript("location.href") { current ->
                    val cur = current?.trim('"') ?: ""
                    if (normalizeUrl(cur) == normalizeUrl(blockedUrl)) {
                        web.loadUrl(getHomeUrl())
                    } else {
                        web.evaluateJavascript("(function(){var o=document.getElementById('slim-block-overlay');if(o)o.remove();})();", null)
                    }
                }
            }, 500)
        }
    }

    private fun handleSpaNavigation(url: String) {
        if (isAuth(url)) return
        if (!isWithinScheduledHours()) { usageRunning = false; runOnUiThread { showScheduleBlockedScreen() }; return }
        if (checkDailyLimitExceeded()) { usageRunning = false; runOnUiThread { showLimitReachedScreen() }; return }

        // Do not let a media-looking SPA route bypass the navigation firewall.
        // It is handled only as a media exception when the previous state proves that
        // this route came from the exact restricted page.
        val media = if (prefs.getBoolean("media_viewer", true)) detectMediaViewerUrl(url) else null
        val restricted = restrictedCustomPageUrl
        if (media != null && restricted != null) {
            val previousRestrictedPage = restricted
            val allowedType = if (media == "image")
                prefs.getBoolean("custom_block_allow_images", false)
            else
                prefs.getBoolean("custom_block_allow_videos", false)

            if (allowedType) {
                // The SPA URL is already current, so extract media from the DOM only;
                // do not grant any navigation permission to the new route.
                val extractJs = if (media == "video")
                    "(function(){var v=document.querySelector('video');if(v&&v.currentSrc)return v.currentSrc;if(v&&v.src)return v.src;var og=document.querySelector('meta[property="og:video"],meta[property="og:video:secure_url"]');return og?og.content:'';})();"
                else
                    "(function(){var og=document.querySelector('meta[property="og:image"]');if(og&&og.content)return og.content;var img=document.querySelector('img[data-visualcompletion="media-vc-image"]')||document.querySelector('[role="main"] img');return img?img.src:'';})();"

                web.evaluateJavascript(extractJs) { result ->
                    val raw = result?.trim('"') ?: ""
                    val mediaUrl = raw.replace("\\u002F", "/").replace("\\/", "/")
                    if (normalizeUrl(web.url ?: "") != previousRestrictedPage && web.canGoBack()) {
                        web.goBack()
                    }
                    if (mediaUrl.isNotEmpty() && mediaUrl.startsWith("http")) {
                        tapHandler.postDelayed({ showMediaViewer(media, mediaUrl) }, 150)
                    } else {
                        extractMediaViaHiddenWebView(url, media)
                    }
                }
                return
            }
        }

        // Every non-media SPA route goes through the same firewall as normal links.
        if (isPageLocked(url)) {
            val lockedUrl = prefs.getString("lock_page_url", "") ?: getHomeUrl()
            incrementBlockedCount()
            enforceLeave(url, true, lockedUrl, "تم إرجاعك (الصفحة مقفلة)")
            return
        }

        if (handleNavigation(url)) {
            val mode = prefs.getString("blocked_redirect_mode", "back") ?: "back"
            val customUrl = prefs.getString("blocked_redirect_url", "") ?: ""
            val useBack = mode != "custom"
            val fallback = if (mode == "custom" && customUrl.isNotEmpty()) customUrl else getHomeUrl()
            enforceLeave(url, useBack, fallback, "تم إرجاعك (تنقّل ممنوع)")
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

    // Silent mode hides non-critical Toast messages without disabling or changing
    // any blocking rule, navigation decision, or security check.
    private fun notifyUser(message: String, duration: Int = Toast.LENGTH_SHORT, critical: Boolean = false) {
        if (critical || !prefs.getBoolean("silent_notifications", false)) {
            Toast.makeText(this, message, duration).show()
        }
    }

    private fun notifyUserFromAnyThread(message: String, duration: Int = Toast.LENGTH_SHORT) {
        runOnUiThread { notifyUser(message, duration) }
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
        swProfileNav.setOnCheckedChangeListener { _,v ->
            prefs.edit().putBoolean("block_profile_nav",v).apply()
            if (!isAuth(web.url ?: "")) {
                if (handleNavigation(web.url ?: "")) web.loadUrl(getHomeUrl())
                else applyControls()
            }
        }
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

        val swSilent = Switch(this)
        swSilent.text = "الوضع الصامت: إخفاء رسائل الحظر والتنبيهات"
        swSilent.isChecked = prefs.getBoolean("silent_notifications", false)
        swSilent.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("silent_notifications", v).apply()
        }
        box.addView(swSilent)

        val sepCustomBlock = TextView(this); sepCustomBlock.text="— قائمة حظر مخصصة —"; sepCustomBlock.setPadding(0,16,0,6); sepCustomBlock.setTextColor(Color.GRAY); box.addView(sepCustomBlock)

        val swCustomBlock = Switch(this); swCustomBlock.text="تفعيل قائمة الحظر المخصصة"; swCustomBlock.isChecked=prefs.getBoolean("custom_block_enabled",false)
        swCustomBlock.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("custom_block_enabled",v).apply() }
        box.addView(swCustomBlock)

        val blockDomainsLabel = TextView(this); blockDomainsLabel.text="روابط/نطاقات للحظر (افصل بفاصلة ,) — مثال: www.facebook.com,m.facebook.com"; blockDomainsLabel.setPadding(0,8,0,4); box.addView(blockDomainsLabel)
        val blockDomainsInput = EditText(this); blockDomainsInput.setText(prefs.getString("custom_block_domains","")); box.addView(blockDomainsInput)

        val exceptionsLabel = TextView(this); exceptionsLabel.text="استثناءات مسموحة رغم الحظر أعلاه (افصل بفاصلة ,) — مثال: facebook.com/groups/113344129011322"; exceptionsLabel.setPadding(0,10,0,4); box.addView(exceptionsLabel)
        val exceptionsInput = EditText(this); exceptionsInput.setText(prefs.getString("custom_block_exceptions","")); box.addView(exceptionsInput)

        val swAllowImages = Switch(this)
        swAllowImages.text = "استثناء الصور داخل الصفحة المحظورة فقط"
        swAllowImages.isChecked = prefs.getBoolean("custom_block_allow_images", false)
        swAllowImages.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("custom_block_allow_images", v).apply()
        }
        box.addView(swAllowImages)

        val swAllowVideos = Switch(this)
        swAllowVideos.text = "استثناء الفيديو داخل الصفحة المحظورة فقط"
        swAllowVideos.isChecked = prefs.getBoolean("custom_block_allow_videos", false)
        swAllowVideos.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("custom_block_allow_videos", v).apply()
        }
        box.addView(swAllowVideos)

        val addCurrentExceptionBtn = TextView(this); addCurrentExceptionBtn.text="➕ إضافة الرابط الحالي إلى الاستثناءات"; addCurrentExceptionBtn.setTextColor(Color.BLUE); addCurrentExceptionBtn.setPadding(0,6,0,10)
        addCurrentExceptionBtn.setOnClickListener {
            val currentUrl = (web.url ?: "").lowercase()
            if (currentUrl.isNotEmpty()) {
                val current = exceptionsInput.text.toString()
                val list = current.split(",").map{it.trim()}.filter{it.isNotEmpty()}.toMutableList()
                if (!list.contains(currentUrl)) list.add(currentUrl)
                exceptionsInput.setText(list.joinToString(","))
                notifyUser("أُضيف للاستثناءات، لا تنسَ الضغط على حفظ")
            }
        }
        box.addView(addCurrentExceptionBtn)

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
                notifyUser("أُضيفت للقائمة، لا تنسَ الضغط على حفظ")
            } else notifyUser("لا يمكن التعرف على هذه الصفحة")
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
                    .setPositiveButton("نعم، عطّله"){_,_-> prefs.edit().putBoolean("disable_js",true).apply(); notifyUser("أعد تشغيل التطبيق لتفعيل التغيير",Toast.LENGTH_LONG) }
                    .setNegativeButton("إلغاء"){_,_-> swJs.isChecked=false}.show()
            } else { prefs.edit().putBoolean("disable_js",false).apply(); notifyUser("أعد تشغيل التطبيق لتفعيل التغيير",Toast.LENGTH_LONG) }
        }
        box.addView(swJs)

        val custom=EditText(this); custom.hint="CSS مخصص (اختياري)"; custom.setText(prefs.getString("css","")); box.addView(custom)
        val jsBox=EditText(this); jsBox.hint="JavaScript مخصص (اختياري)"; jsBox.setText(prefs.getString("js","")); box.addView(jsBox)

        val sepRedirect = TextView(this); sepRedirect.text="— وجهة الرجوع عند منع تنقّل —"; sepRedirect.setPadding(0,16,0,10); sepRedirect.setTextColor(Color.GRAY); box.addView(sepRedirect)
        val swRedirectCustom = Switch(this); swRedirectCustom.text="استخدام رابط محدد بدل الرجوع لنفس المكان"; swRedirectCustom.isChecked = prefs.getString("blocked_redirect_mode","back") == "custom"
        box.addView(swRedirectCustom)
        val redirectUrlLabel = TextView(this); redirectUrlLabel.text="الرابط المحدد (يُستخدم فقط إذا فعّلت الخيار أعلاه)"; redirectUrlLabel.setPadding(0,8,0,4); box.addView(redirectUrlLabel)
        val redirectUrlInput = EditText(this); redirectUrlInput.setText(prefs.getString("blocked_redirect_url","")); box.addView(redirectUrlInput)

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
                notifyUser("يرجى إدخال الاسم والرابط")
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
                .setPositiveButton("حفظ"){_,_-> if(newInput.text.toString().length>=4) { prefs.edit().putString("app_password", newInput.text.toString()).apply(); notifyUser("تم التغيير") } else notifyUser("4 أرقام على الأقل") }
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
                .putString("custom_block_domains", blockDomainsInput.text.toString())
                .putString("custom_block_exceptions", exceptionsInput.text.toString())
                .putString("keyword_blocklist", keywordsInput.text.toString())
                .putString("button_block_words", btnWordsInput.text.toString())
                .putInt("daily_limit_minutes", limitInput.text.toString().toIntOrNull() ?: 0)
                .putInt("schedule_start_hour", (startInput.text.toString().toIntOrNull() ?: 0).coerceIn(0,23))
                .putInt("schedule_end_hour", (endInput.text.toString().toIntOrNull() ?: 24).coerceIn(0,24))
                .putString("home_url", homeUrl)
                .putString("blocked_redirect_mode", if (swRedirectCustom.isChecked) "custom" else "back")
                .putString("blocked_redirect_url", redirectUrlInput.text.toString().trim())
                .apply()
            saveCustomPages(currentPages)
            applyCustom()
            // Rules just changed — re-check the page already open instead of waiting for
            // the next tap or reload to discover it's no longer allowed.
            val current = web.url ?: ""
            if (!isAuth(current)) {
                if (isPageLocked(current)) web.loadUrl(prefs.getString("lock_page_url", "") ?: getHomeUrl())
                else if (handleNavigation(current)) web.loadUrl(getHomeUrl())
            }
        }.setNegativeButton("إغلاق",null).show()
    }

    // "image" or "video" if this URL looks like Facebook's own photo/video viewer, else null.
    private fun detectMediaViewerUrl(url: String): String? {
        val host = facebookHost(url) ?: return null
        if (!(host == "facebook.com" || host.endsWith(".facebook.com") ||
              host == "fbcdn.net" || host.endsWith(".fbcdn.net"))) return null
        if (isAuth(url)) return null

        val uri = try { Uri.parse(url) } catch (_: Exception) { return null }
        val path = uri.path?.lowercase() ?: ""
        val query = uri.query?.lowercase() ?: ""

        if (path.startsWith("/videos/") || path == "/video.php" ||
            path.startsWith("/reel/") || path == "/watch" ||
            query.contains("v=") && (path == "/watch" || path == "/video.php")) return "video"

        if (path == "/photo.php" || path.startsWith("/photo/") ||
            path.startsWith("/photos/") || query.contains("fbid=")) return "image"

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
                notifyUser("تعذّر فتح هذه الصورة/الفيديو داخل التطبيق")
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
                        notifyUser("تعذّر فتح هذه الصورة/الفيديو داخل التطبيق")
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
                notifyUser("تعذّر تشغيل الفيديو"); true
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
