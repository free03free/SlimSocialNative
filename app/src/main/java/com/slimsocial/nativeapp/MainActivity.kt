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
    @Volatile private var blockedNavigationUrl: String? = null
    @Volatile private var blockedNavigationGeneration: Long = 0L
    private var blockedPageBaseUrl: String? = null
    private var restrictedCustomPageUrl: String? = null
    private var pendingMediaOnlyExceptionUrl: String? = null
    @Volatile private var lastRuleEngineReport: String = "لم يتم الفحص بعد"
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
        web.addJavascriptInterface(object {
            @JavascriptInterface fun report(data: String) {
                lastRuleEngineReport = data
            }
        }, "SlimRuleBridge")
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
            fun openMedia(type: String, url: String, origin: String): Boolean {
                // This bridge is the ONLY media-exception gate. It validates the exact
                // restricted-page origin and the native media classification before any
                // viewer is opened. Returning true lets the page's click handler cancel
                // the navigation; arbitrary SPA URL changes never receive this permission.
                val current = web.url ?: ""
                val media = detectMediaViewerUrl(url)
                val originOk = restrictedCustomPageUrl != null &&
                    normalizeUrl(origin) == restrictedCustomPageUrl &&
                    normalizeUrl(current) == restrictedCustomPageUrl
                val allowed = originOk && media == type && isMediaExceptionAllowed(origin, type)
                if (allowed) {
                    runOnUiThread { showMediaViewer(type, url) }
                }
                return allowed
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
                // IMPORTANT: a restricted page never grants navigation permission to a
                // media-looking URL. Media exceptions are opened only through the validated
                // SlimBridge.openMedia() path, so repeated taps cannot turn into navigation.
                if (restrictedCustomPageUrl == null && pendingMediaOnlyExceptionUrl == null && prefs.getBoolean("media_viewer", true)) {
                    val media = detectMediaViewerUrl(url)
                    if (media != null) {
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
                if (isAuth(url)) return
                if (!isWithinScheduledHours()) { usageRunning = false; v.stopLoading(); runOnUiThread { showScheduleBlockedScreen() }; return }
                if (checkDailyLimitExceeded()) { usageRunning = false; v.stopLoading(); runOnUiThread { showLimitReachedScreen() }; return }
                if (isPageLocked(url)) {
                    v.stopLoading()
                    val lockedUrl = prefs.getString("lock_page_url", "") ?: getHomeUrl()
                    v.post { v.loadUrl(lockedUrl) }
                    return
                }
                // A media-only exception is a one-time entry point. Lock the exact
                // configured origin as soon as navigation starts, even if Facebook first
                // redirects through a slightly different URL/query string.
                val pending = pendingMediaOnlyExceptionUrl
                if (pending != null && (normalizeUrl(url) == pending || isMediaOnlyCustomException(url))) {
                    restrictedCustomPageUrl = normalizeUrl(url)
                    blockedPageBaseUrl = normalizeUrl(url)
                    pendingMediaOnlyExceptionUrl = null
                    blockedNavigationUrl = null
                } else if (isMediaOnlyCustomException(url) && restrictedCustomPageUrl == null) {
                    restrictedCustomPageUrl = normalizeUrl(url)
                    blockedPageBaseUrl = normalizeUrl(url)
                    blockedNavigationUrl = null
                }
                if (handleNavigation(url)) {
                    v.stopLoading()
                    val generation = blockedNavigationGeneration
                    v.post {
                        if (generation == blockedNavigationGeneration) {
                            v.loadUrl(getHomeUrl())
                        }
                    }
                }
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(v, url, isReload)
                if (isAuth(url)) return
                applyCustomRulesDelayed()
                val restricted = restrictedCustomPageUrl
                if (restricted != null && normalizeUrl(url) != restricted) {
                    blockedNavigationUrl = normalizeUrl(url)
                    blockedNavigationGeneration++
                    v.stopLoading()
                    v.post {
                        if (restrictedCustomPageUrl == restricted && normalizeUrl(v.url ?: "") != restricted) {
                            v.loadUrl(restricted)
                        }
                    }
                    return
                }
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (!isAuth(url)) { applyControls(); applyCustomRulesDelayed() }
                val restricted = restrictedCustomPageUrl
                if (restricted != null && normalizeUrl(url) != restricted) {
                    v.stopLoading()
                    v.post { if (restrictedCustomPageUrl == restricted) v.loadUrl(restricted) }
                }
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
            setOnClickListener { clearRestrictedSession(); web.loadUrl(getHomeUrl()) }
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
            .setItems(names) { _, which -> clearRestrictedSession(); web.loadUrl(pages[which].second) }
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

    private fun isMediaOnlyCustomException(url: String): Boolean {
        if (!prefs.getBoolean("custom_block_enabled", false)) return false
        if (prefs.getBoolean("custom_block_full_exceptions", false)) return false
        if (!prefs.getBoolean("custom_block_allow_images", false) &&
            !prefs.getBoolean("custom_block_allow_videos", false)) return false
        return getListPref("custom_block_exceptions").any { matchesConfiguredUrlRule(url, it) }
    }

    private fun handleNavigation(url: String): Boolean {
        val u = normalizeUrl(url)
        if (u.isEmpty()) return true
        val isFacebookDomain = isFacebookHost(url)

        // ABSOLUTE RULE: once a destination is rejected, every repeated attempt to the
        // same destination is rejected again. Never rely on timing/debounce to enforce a block.
        val previouslyBlocked = blockedNavigationUrl
        if (previouslyBlocked != null && u == previouslyBlocked) {
            incrementBlockedCount()
            return true
        }

        // A restricted custom-block page is a media-only sandbox. It is never a general
        // navigation exception. Only the native, origin-validated media bridge can open media.
        val restricted = restrictedCustomPageUrl
        if (restricted != null && u != restricted) {
            blockedNavigationUrl = u
            blockedNavigationGeneration++
            notifyUserFromAnyThread("تم منع التنقل من الصفحة المحظورة")
            incrementBlockedCount()
            return true
        }

        if (prefs.getBoolean("custom_block_enabled", false)) {
            val exceptions = getListPref("custom_block_exceptions")
            val isException = exceptions.any { matchesConfiguredUrlRule(url, it) }
            val fullExceptions = prefs.getBoolean("custom_block_full_exceptions", false)
            val mediaOnlyException = isException && !fullExceptions &&
                (prefs.getBoolean("custom_block_allow_images", false) ||
                 prefs.getBoolean("custom_block_allow_videos", false))

            // A media-only exception is allowed ONLY when this exact destination is
            // present in the user's exception list. The image/video toggles never turn
            // arbitrary blocked pages into allowed pages.
            if (isException && !fullExceptions) {
                if (mediaOnlyException) {
                    // Mark the exact configured exception as a one-time media-only entry.
                    // Do NOT set restrictedCustomPageUrl here because this call is the
                    // navigation that must be allowed to load the exception itself.
                    pendingMediaOnlyExceptionUrl = u
                    return false
                }
                // Exception without an enabled media type is NOT a media permission.
                // It is blocked unless the user explicitly enables full exceptions.
                blockedNavigationUrl = u
                blockedNavigationGeneration++
                notifyUserFromAnyThread("الاستثناء لا يسمح بالتنقل؛ فعّل الصور أو الفيديو فقط")
                incrementBlockedCount()
                return true
            }

            val blocklist = getListPref("custom_block_domains")
            if (blocklist.any { it.isNotEmpty() && matchesConfiguredUrlRule(url, it) }) {
                // IMPORTANT: images/video are allowed only on URLs that are explicitly
                // listed in the exception field. A blocked URL that is not an exception
                // remains completely blocked, regardless of the media toggles.
                blockedNavigationUrl = u
                blockedNavigationGeneration++
                notifyUserFromAnyThread("تم منع هذا الرابط (قائمة حظر مخصصة)")
                incrementBlockedCount()
                return true
            }
        }

        if (prefs.getBoolean("block_external", false) && !isFacebookDomain) {
            blockedNavigationUrl = u
            blockedNavigationGeneration++
            notifyUserFromAnyThread("تم منع رابط خارجي")
            incrementBlockedCount()
            return true
        }

        if (prefs.getBoolean("block_profile_nav", false) &&
            isFacebookDomain && !isAuth(url) && isProfilePageOrGroupUrl(url)) {
            val id = extractIdentifier(url.lowercase())
            val whitelist = getWhitelistSet()
            if (id == null || !whitelist.contains(id)) {
                blockedNavigationUrl = u
                blockedNavigationGeneration++
                notifyUserFromAnyThread("تم منع زيارة هذا الملف الشخصي/الصفحة/المجموعة")
                incrementBlockedCount()
                return true
            }
        }

        if (prefs.getBoolean("block_unjoined_groups", false) && extractGroupId(url.lowercase()) != null) {
            val groupId = extractGroupId(url.lowercase())
            val allowed = getWhitelistSet()
            if (groupId != null && !allowed.contains(groupId)) {
                blockedNavigationUrl = u
                blockedNavigationGeneration++
                notifyUserFromAnyThread("مجموعة غير مسموح بها. أضفها من الإعدادات إن أردت السماح", Toast.LENGTH_LONG)
                incrementBlockedCount()
                return true
            }
        }

        // A permitted navigation starts a new route; don't carry a stale rejection into it.
        if (blockedNavigationUrl != u) blockedNavigationUrl = null
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

    // A media-exception page is a locked session. Navigation can leave it only through
    // explicit app UI (Home/pages chooser) or by closing/reloading the app. A WebView
    // history/SPA change must never be able to clear this state.
    private fun clearRestrictedSession() {
        restrictedCustomPageUrl = null
        blockedPageBaseUrl = null
        pendingMediaOnlyExceptionUrl = null
        blockedNavigationUrl = null
        blockedNavigationGeneration++
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
        lastBlockedRedirect = System.currentTimeMillis()
        blockedNavigationUrl = normalizeUrl(blockedUrl)
        blockedNavigationGeneration++
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

        // NEVER authorize a SPA route as a media exception. A SPA route is navigation,
        // not a trusted media request. Media exceptions are handled only by the native
        // bridge after it proves the exact restricted-page origin.

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
        if (restrictedCustomPageUrl != null) {
            // HARD SPA LOCK: a restricted page cannot change its history URL. The only
            // permitted media path is SlimBridge.openMedia(), which does not navigate
            // the Facebook WebView at all.
            js.append("if(!window.__slimSpaGuard){window.__slimSpaGuard=true;window.__slimLastUrl=location.href;var _ps=history.pushState;history.pushState=function(s,t,u){var h='';try{h=new URL(u,location.href).href;}catch(e){h=location.href;}if(window.SlimBridge)SlimBridge.checkNav(h);return;};var _rs=history.replaceState;history.replaceState=function(s,t,u){var h='';try{h=new URL(u,location.href).href;}catch(e){h=location.href;}if(window.SlimBridge)SlimBridge.checkNav(h);return;};window.addEventListener('popstate',function(){if(location.href!==window.__slimLastUrl&&window.SlimBridge)SlimBridge.checkNav(location.href);});setInterval(function(){if(location.href!==window.__slimLastUrl&&window.SlimBridge)SlimBridge.checkNav(location.href);},250);}")
        } else {
            js.append("if(!window.__slimSpaGuard){window.__slimSpaGuard=true;window.__slimLastUrl=location.href;function slimCheckSpa(){if(location.href!==window.__slimLastUrl){window.__slimLastUrl=location.href;if(window.SlimBridge)SlimBridge.checkNav(location.href);}}var _ps=history.pushState;history.pushState=function(){_ps.apply(history,arguments);slimCheckSpa();};var _rs=history.replaceState;history.replaceState=function(){_rs.apply(history,arguments);slimCheckSpa();};window.addEventListener('popstate',slimCheckSpa);setInterval(slimCheckSpa,600);}")
        }
        // PHOTO FEED MODE is explicitly controlled by the user setting.
        // When OFF, do not touch Facebook's normal photo layout at all.
        val verticalPhotoMode = prefs.getBoolean("vertical_photo_mode", false)
        if (verticalPhotoMode) {
            // ROBUST PHOTO VERTICAL MODE: Facebook frequently changes its DOM and may
            // lazy-load thumbnails. Do not depend on naturalWidth/naturalHeight and do
            // not assume a specific Facebook class name. Find the nearest common photo
            // container, then force its layout and every photo wrapper into one column.
            js.append("""if(!window.__slimVerticalPhotos){window.__slimVerticalPhotos=true;function __slimMedia(el){if(!el)return false;var r=el.getBoundingClientRect(),cs=getComputedStyle(el),bg=cs.backgroundImage||'';if(r.width<60||r.height<40)return false;if(el.tagName==='IMG')return !!((el.currentSrc||el.src||'').length>10);if(el.tagName==='VIDEO')return true;return bg.indexOf('url(')>=0;}function __slimMediaNodes(root){return Array.from(root.querySelectorAll('img,video,[style*="background-image" i]')).filter(__slimMedia);}function __slimForce(el,first){if(!el)return;el.style.setProperty('display','block','important');el.style.setProperty('width','100%','important');el.style.setProperty('max-width','100%','important');el.style.setProperty('height','auto','important');el.style.setProperty('min-height','0','important');el.style.setProperty('min-width','0','important');el.style.setProperty('float','none','important');el.style.setProperty('clear','both','important');el.style.setProperty('grid-column','1 / -1','important');el.style.setProperty('grid-row','auto','important');el.style.setProperty('flex','0 0 auto','important');el.style.setProperty('flex-basis','auto','important');el.style.setProperty('position','static','important');el.style.setProperty('transform','none','important');el.style.setProperty('inset','auto','important');el.style.setProperty('margin',first?'0':'0 0 8px 0','important');}function __slimPhotoPass(){var all=Array.from(document.querySelectorAll('img,video,[style*="background-image" i]')).filter(__slimMedia);var done=[];all.forEach(function(m){var g=null,n=m.parentElement;for(var d=0;n&&d<35;d++,n=n.parentElement){var q=__slimMediaNodes(n);if(q.length>=2&&q.length<=30){g=n;break;}}if(!g||done.indexOf(g)>=0)return;var media=__slimMediaNodes(g);if(media.length<2||media.length>30)return;done.push(g);g.classList.add('slim-vertical-photo-group');g.style.setProperty('display','block','important');g.style.setProperty('width','100%','important');g.style.setProperty('max-width','100%','important');g.style.setProperty('height','auto','important');g.style.setProperty('overflow','visible','important');g.style.setProperty('position','static','important');g.style.setProperty('grid-template-columns','none','important');g.style.setProperty('grid-template-rows','none','important');g.style.setProperty('flex-direction','column','important');g.style.setProperty('flex-wrap','nowrap','important');media.forEach(function(x,i){var chain=[],a=x;for(var z=0;a&&a!==g&&z<35;z++,a=a.parentElement)chain.push(a);chain.forEach(function(c){__slimForce(c,c.parentElement===g);});if(x.tagName==='IMG'){x.style.setProperty('object-fit','contain','important');x.style.setProperty('object-position','center center','important');x.style.setProperty('visibility','visible','important');x.style.setProperty('opacity','1','important');}if(x.tagName==='VIDEO'){x.style.setProperty('object-fit','contain','important');}x.classList.add('slim-vertical-photo');});});}__slimPhotoPass();if(!window.__slimVerticalPhotoObserver){window.__slimVerticalPhotoObserver=new MutationObserver(function(){clearTimeout(window.__slimVerticalPhotoTimer);window.__slimVerticalPhotoTimer=setTimeout(__slimPhotoPass,150);});window.__slimVerticalPhotoObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','src','srcset']});}window.addEventListener('load',__slimPhotoPass);window.addEventListener('resize',__slimPhotoPass);window.__slimPhotoTimer=setInterval(__slimPhotoPass,800);}""")
            js.append("s+='html,body{max-width:100%!important;overflow-x:hidden!important;} .slim-vertical-photo-group{display:block!important;width:100%!important;max-width:100%!important;height:auto!important;background:transparent!important;border:0!important;box-shadow:none!important;overflow:visible!important;position:static!important;} .slim-vertical-photo-group *{box-sizing:border-box!important;} .slim-vertical-photo-group img.slim-vertical-photo,.slim-vertical-photo-group video.slim-vertical-photo{display:block!important;float:none!important;clear:both!important;position:static!important;transform:none!important;inset:auto!important;width:100%!important;max-width:100%!important;height:auto!important;min-height:0!important;object-fit:contain!important;background:transparent!important;border:0!important;box-shadow:none!important;visibility:visible!important;opacity:1!important;}';")
        } else {
            // Remove any previous mode injected into the current SPA document when the
            // user switches the setting OFF.
            js.append("if(window.__slimVerticalPhotoObserver){window.__slimVerticalPhotoObserver.disconnect();window.__slimVerticalPhotoObserver=null;}document.querySelectorAll('.slim-vertical-photo-group').forEach(function(e){e.classList.remove('slim-vertical-photo-group');});document.querySelectorAll('.slim-vertical-photo').forEach(function(e){e.classList.remove('slim-vertical-photo');});")
        }
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
        if (restrictedCustomPageUrl != null) {
            // MEDIA-ONLY SANDBOX: every click that could navigate is blocked first.
            // The ONLY exception is an explicitly detected image/video link that the
            // native bridge approves. This prevents Facebook's SPA router from turning
            // a media exception into permission to visit profiles, pages, groups, posts,
            // search, reels, or any other destination.
            js.append("""if(!window.__slimRestrictedMediaGuard){window.__slimRestrictedMediaGuard=true;function __slimMediaType(h){try{var u=new URL(h,location.href),p=(u.pathname||'').toLowerCase(),q=(u.search||'').toLowerCase(),x=u.href.toLowerCase();if(p==='/photo.php'||p.indexOf('/photo/')===0||p.indexOf('/photos/')===0||p.indexOf('/permalink.php')===0||q.indexOf('fbid=')!==-1||q.indexOf('photo_id=')!==-1||q.indexOf('set=a.')!==-1)return 'image';if(p.indexOf('/videos/')===0||p==='/video.php'||p.indexOf('/video/')===0||p.indexOf('/reel/')===0||p==='/watch'||p.indexOf('/watch/')===0||q.indexOf('v=')!==-1||q.indexOf('video_id=')!==-1)return 'video';if((u.hostname||'').toLowerCase().indexOf('fbcdn.net')!==-1){if(/\.(mp4|webm|mov|m3u8)(?:[?#]|$)/i.test(x))return 'video';if(/\.(jpg|jpeg|png|webp|gif)(?:[?#]|$)/i.test(x))return 'image';}return null;}catch(x){return null;}}function __slimOpenMedia(e){var t=e.target;var v=t&&t.closest?t.closest('video'):null;if(v){var src=v.currentSrc||v.src||'';if(src&&window.SlimBridge){try{if(SlimBridge.openMedia('video',src,location.href))return true;}catch(x){}}}var img=t&&t.closest?t.closest('img'):null;if(img){var src=img.currentSrc||img.src||img.getAttribute('src')||'';if(src&&window.SlimBridge){try{if(SlimBridge.openMedia('image',src,location.href))return true;}catch(x){}}}return false;}document.addEventListener('click',function(e){if(__slimOpenMedia(e)){e.preventDefault();e.stopImmediatePropagation();return;}var a=e.target&&e.target.closest?e.target.closest('a[href],[role=\"link\"],[role=\"button\"]'):null;var h=a?(a.href||''):'';e.preventDefault();e.stopImmediatePropagation();var type=h?__slimMediaType(h):null;if(type&&window.SlimBridge){try{if(SlimBridge.openMedia(type,h,location.href))return;}catch(x){}}if(window.SlimBridge&&h){try{SlimBridge.checkNav(h);}catch(x){}}},true);document.addEventListener('pointerdown',function(e){if(e.target&&e.target.closest&&e.target.closest('img,video')){e.preventDefault();e.stopImmediatePropagation();return;}var a=e.target&&e.target.closest?e.target.closest('a[href],[role=\"link\"],[role=\"button\"]'):null;if(a){var h=a.href||'';if(h&&window.SlimBridge){try{var u=new URL(h,location.href);if(u.href!==location.href){e.preventDefault();e.stopImmediatePropagation();}}catch(x){e.preventDefault();e.stopImmediatePropagation();}}}},true);}""")
        }
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
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }

        val title = TextView(this).apply {
            text = "سليم سوشيال • إعدادات فيسبوك"
            textSize = 21f
            setTextColor(Color.DKGRAY)
            setPadding(0, 6, 0, 6)
        }
        box.addView(title)

        val counter = TextView(this).apply {
            text = "المحاولات المحظورة: ${prefs.getInt("blocked_count", 0)}"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 14)
        }
        box.addView(counter)

        // Collapsible sections keep the main settings screen short and readable.
        fun section(titleText: String, icon: String, open: Boolean = false): LinearLayout {
            val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 4, 10, 10)
                visibility = if (open) View.VISIBLE else View.GONE
            }
            val header = TextView(this).apply {
                text = if (open) "$icon  $titleText   ▲" else "$icon  $titleText   ▼"
                textSize = 17f
                setTextColor(Color.DKGRAY)
                setPadding(14, 15, 14, 15)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(245, 245, 245))
                    cornerRadius = 14f
                }
                setOnClickListener {
                    val show = content.visibility != View.VISIBLE
                    content.visibility = if (show) View.VISIBLE else View.GONE
                    text = if (show) "$icon  $titleText   ▲" else "$icon  $titleText   ▼"
                }
            }
            wrapper.addView(header)
            wrapper.addView(content)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 0, 0, 8)
            box.addView(wrapper, lp)
            return content
        }

        fun addSwitch(parent: LinearLayout, key: String, label: String, refresh: Boolean = true) {
            val sw = Switch(this).apply {
                text = label
                textSize = 16f
                isChecked = prefs.getBoolean(key, false)
                setPadding(4, 8, 4, 8)
            }
            sw.setOnCheckedChangeListener { _, value ->
                prefs.edit().putBoolean(key, value).apply()
                if (refresh && !isAuth(web.url ?: "")) applyControls()
            }
            parent.addView(sw)
        }

        val basic = section("الحظر الأساسي", "🛡️", true)
        blocks.forEach { name ->
            val sw = Switch(this).apply {
                text = arabicLabels[name] ?: name
                textSize = 16f
                isChecked = prefs.getBoolean(name, false)
                setPadding(4, 8, 4, 8)
            }
            sw.setOnCheckedChangeListener { _, value ->
                prefs.edit().putBoolean(name, value).apply()
                if (!isAuth(web.url ?: "")) applyControls()
            }
            basic.addView(sw)
        }
        addSwitch(basic, "block_message_btn", "مراسلة (زر مراسلة الصفحات)")

        val media = section("الصور والفيديو والوسائط", "🖼️")
        val swVerticalPhotos = Switch(this).apply {
            text = "عرض صور المنشورات عموديًا (صورة كاملة تحت صورة)"
            isChecked = prefs.getBoolean("vertical_photo_mode", false)
            setPadding(4, 8, 4, 8)
        }
        swVerticalPhotos.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("vertical_photo_mode", value).apply()
            if (!isAuth(web.url ?: "")) { applyControls(); web.reload() }
        }
        media.addView(swVerticalPhotos)
        media.addView(TextView(this).apply {
            text = "عند التفعيل تظهر الصور المتعددة صورة تحت صورة بدل شبكة الصور."
            setTextColor(Color.GRAY); textSize = 12f; setPadding(4, 0, 4, 8)
        })
        addSwitch(media, "block_images", "منع عرض الصور")
        addSwitch(media, "block_videos", "منع عرض الفيديوهات")
        addSwitch(media, "block_video_swipe", "منع سحب الشاشة للتنقل بين الفيديوهات")
        val swVideoSwipeCombo = Switch(this).apply {
            text = "منع الفيديو والسحب معًا"
            isChecked = prefs.getBoolean("block_videos", false) && prefs.getBoolean("block_video_swipe", false)
            setPadding(4, 8, 4, 8)
        }
        swVideoSwipeCombo.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("block_videos", value).putBoolean("block_video_swipe", value).apply()
            if (!isAuth(web.url ?: "")) applyControls()
        }
        media.addView(swVideoSwipeCombo)
        addSwitch(media, "media_viewer", "فتح الصور والفيديوهات داخل التطبيق", refresh = false)

        val navigation = section("التنقل والصفحات", "🧭")
        addSwitch(navigation, "block_profile_nav", "منع زيارة أي بروفايل / صفحة / مجموعة", refresh = false)
        addSwitch(navigation, "block_top_nav", "إخفاء القائمة العلوية لفيسبوك (أينما كانت)")
        addSwitch(navigation, "freeze_page", "تجميد الصفحة بالكامل (تمرير فقط + فتح الوسائط المسموحة)")
        addSwitch(navigation, "block_external", "منع الروابط الخارجية", refresh = false)
        addSwitch(navigation, "block_join_group", "منع الانضمام إلى مجموعات")
        addSwitch(navigation, "lock_page_enabled", "قفل الصفحة الحالية", refresh = false)

        val custom = section("قائمة الحظر والاستثناءات", "🚫")
        addSwitch(custom, "custom_block_enabled", "تفعيل قائمة الحظر المخصصة", refresh = false)
        val blockDomainsInput = EditText(this).apply {
            hint = "روابط / نطاقات للحظر — افصل بفاصلة"
            setText(prefs.getString("custom_block_domains", ""))
            setSingleLine(false)
        }
        custom.addView(blockDomainsInput)
        val exceptionsInput = EditText(this).apply {
            hint = "روابط الاستثناءات — افصل بفاصلة"
            setText(prefs.getString("custom_block_exceptions", ""))
            setSingleLine(false)
        }
        custom.addView(exceptionsInput)
        val swAllowImages = Switch(this).apply {
            text = "السماح بالصور فقط داخل رابط موجود في الاستثناءات"
            isChecked = prefs.getBoolean("custom_block_allow_images", false)
            setPadding(4, 8, 4, 8)
        }
        swAllowImages.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("custom_block_allow_images", value).apply()
            pendingMediaOnlyExceptionUrl = null
            if (!value && !prefs.getBoolean("custom_block_allow_videos", false)) clearRestrictedSession()
            if (!isAuth(web.url ?: "")) { applyControls(); web.reload() }
        }
        custom.addView(swAllowImages)
        val swAllowVideos = Switch(this).apply {
            text = "السماح بالفيديو فقط داخل رابط موجود في الاستثناءات"
            isChecked = prefs.getBoolean("custom_block_allow_videos", false)
            setPadding(4, 8, 4, 8)
        }
        swAllowVideos.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("custom_block_allow_videos", value).apply()
            pendingMediaOnlyExceptionUrl = null
            if (!value && !prefs.getBoolean("custom_block_allow_images", false)) clearRestrictedSession()
            if (!isAuth(web.url ?: "")) { applyControls(); web.reload() }
        }
        custom.addView(swAllowVideos)
        addSwitch(custom, "custom_block_full_exceptions", "السماح بالاستثناءات للوصول الكامل", refresh = false)
        custom.addView(TextView(this).apply {
            text = "إذا كان هذا الخيار مغلقًا، تبقى الاستثناءات مقيدة بالصور/الفيديو فقط عند تفعيلهما."
            setTextColor(Color.GRAY); textSize = 12f; setPadding(4, 0, 4, 8)
        })
        val addCurrentExceptionBtn = Button(this).apply { text = "➕ إضافة الرابط الحالي إلى الاستثناءات" }
        addCurrentExceptionBtn.setOnClickListener {
            val currentUrl = web.url?.lowercase() ?: ""
            if (currentUrl.isNotEmpty()) {
                val list = exceptionsInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (!list.contains(currentUrl)) list.add(currentUrl)
                exceptionsInput.setText(list.joinToString(","))
                notifyUser("أُضيف للاستثناءات، اضغط حفظ")
            }
        }
        custom.addView(addCurrentExceptionBtn)
        addSwitch(custom, "block_unjoined_groups", "منع مجموعات غير مشترك فيها")
        val whitelistInput = EditText(this).apply {
            hint = "قائمة المجموعات/الصفحات المسموحة — افصل بفاصلة"
            setText(prefs.getString("group_whitelist", ""))
        }
        custom.addView(whitelistInput)
        val allowGroupBtn = Button(this).apply { text = "➕ إضافة المجموعة/الصفحة الحالية" }
        allowGroupBtn.setOnClickListener {
            val id = extractIdentifier((web.url ?: "").lowercase())
            if (id != null) {
                val list = whitelistInput.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                if (!list.contains(id)) list.add(id)
                whitelistInput.setText(list.joinToString(","))
                notifyUser("أُضيفت للقائمة، اضغط حفظ")
            } else notifyUser("لا يمكن التعرف على هذه الصفحة")
        }
        custom.addView(allowGroupBtn)

        val textRules = section("الكلمات والأزرار", "🔤")
        val keywordsInput = EditText(this).apply {
            hint = "كلمات تعيد التوجيه للرئيسية — مثال: كلمة1, كلمة2"
            setText(prefs.getString("keyword_blocklist", ""))
        }
        textRules.addView(keywordsInput)
        val btnWordsInput = EditText(this).apply {
            hint = "نصوص الأزرار التي تريد إخفاءها — مثال: متابعة, انضمام"
            setText(prefs.getString("button_block_words", ""))
        }
        textRules.addView(btnWordsInput)

        val usage = section("الوقت والتحديث والمظهر", "⏱️")
        val limitInput = EditText(this).apply {
            hint = "الحد اليومي بالدقائق (0 = بلا حد)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("daily_limit_minutes", 0).toString())
        }
        usage.addView(limitInput)
        addSwitch(usage, "block_refresh", "منع تحديث الصفحة بالكامل", refresh = false)
        addSwitch(usage, "block_refresh_on_video", "منع التحديث أثناء تشغيل فيديو", refresh = false)
        addSwitch(usage, "dark_mode", "وضع داكن إجباري")
        addSwitch(usage, "hide_toolbar", "إخفاء شريط الأدوات (اضغط 3 مرات لإظهاره)", refresh = false)
        addSwitch(usage, "schedule_enabled", "تفعيل الجدولة")
        val startInput = EditText(this).apply {
            hint = "من الساعة (0-23)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("schedule_start_hour", 8).toString())
        }
        val endInput = EditText(this).apply {
            hint = "إلى الساعة (0-24)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("schedule_end_hour", 22).toString())
        }
        usage.addView(startInput); usage.addView(endInput)
        addSwitch(usage, "silent_notifications", "الوضع الصامت: إخفاء رسائل الحظر والتنبيهات", refresh = false)

        val privacy = section("الخصوصية والحماية", "🔐")
        addSwitch(privacy, "block_copy", "منع نسخ النصوص من الصفحة")
        addSwitch(privacy, "block_screenshot", "منع لقطة الشاشة وتسجيل الشاشة", refresh = false)
        val swJs = Switch(this).apply {
            text = "تعطيل JavaScript بالكامل ⚠️"
            isChecked = prefs.getBoolean("disable_js", false)
            setPadding(4, 8, 4, 8)
        }
        swJs.setOnCheckedChangeListener { _, value ->
            if (value) {
                AlertDialog.Builder(this).setTitle("تحذير")
                    .setMessage("تعطيل JavaScript سيوقف خيارات الحظر الأخرى وقد يمنع فيسبوك من العمل. هل تريد المتابعة؟")
                    .setPositiveButton("نعم، عطّله") { _, _ -> prefs.edit().putBoolean("disable_js", true).apply(); notifyUser("أعد تشغيل التطبيق لتفعيل التغيير", Toast.LENGTH_LONG) }
                    .setNegativeButton("إلغاء") { _, _ -> swJs.isChecked = false }.show()
            } else {
                prefs.edit().putBoolean("disable_js", false).apply()
                notifyUser("أعد تشغيل التطبيق لتفعيل التغيير", Toast.LENGTH_LONG)
            }
        }
        privacy.addView(swJs)
        val changePwd = Button(this).apply { text = "🔑 تغيير رمز PIN" }
        changePwd.setOnClickListener {
            val newInput = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = "رمز PIN الجديد"
            }
            AlertDialog.Builder(this).setTitle("تغيير رمز PIN").setView(newInput)
                .setPositiveButton("حفظ") { _, _ ->
                    if (newInput.text.toString().length >= 4) {
                        prefs.edit().putString("app_password", newInput.text.toString()).apply(); notifyUser("تم التغيير")
                    } else notifyUser("4 أرقام على الأقل")
                }.setNegativeButton("إلغاء", null).show()
        }
        privacy.addView(changePwd)

        val rules = section("JavaScript / CSS", "🧩")
        rules.addView(TextView(this).apply {
            text = "إدارة القواعد المستقلة — حفظ، تعديل، تفعيل، تعطيل، حذف وأولوية لكل قاعدة."
            setTextColor(Color.GRAY); textSize = 12f; setPadding(4, 0, 4, 8)
        })
        rules.addView(Button(this).apply {
            text = "⚙ إدارة قواعد JS / CSS"
            setOnClickListener { showCustomRulesManager() }
        })

        val redirect = section("وجهة الرجوع عند منع التنقل", "↩️")
        val swRedirectCustom = Switch(this).apply {
            text = "استخدام رابط محدد بدل الرجوع لنفس المكان"
            isChecked = prefs.getString("blocked_redirect_mode", "back") == "custom"
            setPadding(4, 8, 4, 8)
        }
        redirect.addView(swRedirectCustom)
        val redirectUrlInput = EditText(this).apply {
            hint = "الرابط المحدد"
            setText(prefs.getString("blocked_redirect_url", ""))
        }
        redirect.addView(redirectUrlInput)

        val pages = section("الصفحة الرئيسية والصفحات الإضافية", "🏠")
        val homeInput = EditText(this).apply { hint = "رابط الصفحة الرئيسية"; setText(getHomeUrl()) }
        pages.addView(homeInput)
        pages.addView(TextView(this).apply {
            text = "الصفحات الإضافية — تظهر عند الضغط مطولًا على زر 🏠"
            setTextColor(Color.GRAY); textSize = 12f; setPadding(4, 8, 4, 4)
        })
        val pagesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages.addView(pagesContainer)
        val currentPages = getCustomPages()
        fun refreshPagesList() {
            pagesContainer.removeAllViews()
            currentPages.forEachIndexed { index, pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 4) }
                val label = TextView(this).apply {
                    text = "${pair.first}\n${pair.second}"
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(label)
                val delBtn = Button(this).apply { text = "حذف" }
                delBtn.setOnClickListener { currentPages.removeAt(index); refreshPagesList() }
                row.addView(delBtn)
                pagesContainer.addView(row)
            }
        }
        refreshPagesList()
        val newNameInput = EditText(this).apply { hint = "اسم الصفحة الجديدة" }
        val newUrlInput = EditText(this).apply { hint = "رابط الصفحة الجديدة (https://...)" }
        pages.addView(newNameInput); pages.addView(newUrlInput)
        pages.addView(Button(this).apply {
            text = "➕ إضافة صفحة جديدة"
            setOnClickListener {
                val name = newNameInput.text.toString().trim()
                var url = newUrlInput.text.toString().trim()
                if (name.isEmpty() || url.isEmpty()) notifyUser("يرجى إدخال الاسم والرابط")
                else {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    currentPages.add(name to url); newNameInput.setText(""); newUrlInput.setText(""); refreshPagesList()
                }
            }
        })

        AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("حفظ") { _, _ ->
                var homeUrl = homeInput.text.toString().trim()
                if (homeUrl.isEmpty()) homeUrl = "https://www.facebook.com/"
                if (!homeUrl.startsWith("http://") && !homeUrl.startsWith("https://")) homeUrl = "https://$homeUrl"
                prefs.edit()
                    .putString("group_whitelist", whitelistInput.text.toString())
                    .putString("custom_block_domains", blockDomainsInput.text.toString())
                    .putString("custom_block_exceptions", exceptionsInput.text.toString())
                    .putString("keyword_blocklist", keywordsInput.text.toString())
                    .putString("button_block_words", btnWordsInput.text.toString())
                    .putInt("daily_limit_minutes", limitInput.text.toString().toIntOrNull() ?: 0)
                    .putInt("schedule_start_hour", (startInput.text.toString().toIntOrNull() ?: 0).coerceIn(0, 23))
                    .putInt("schedule_end_hour", (endInput.text.toString().toIntOrNull() ?: 24).coerceIn(0, 24))
                    .putString("home_url", homeUrl)
                    .putString("blocked_redirect_mode", if (swRedirectCustom.isChecked) "custom" else "back")
                    .putString("blocked_redirect_url", redirectUrlInput.text.toString().trim())
                    .apply()
                saveCustomPages(currentPages)
                applyCustom()
                val current = web.url ?: ""
                if (!isAuth(current)) {
                    if (isPageLocked(current)) web.loadUrl(prefs.getString("lock_page_url", "") ?: getHomeUrl())
                    else if (handleNavigation(current)) web.loadUrl(getHomeUrl())
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
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
            path.startsWith("/video/") || path.startsWith("/reel/") ||
            path == "/watch" || path.startsWith("/watch/") ||
            query.contains("v=") || query.contains("video_id=")) return "video"

        if (path == "/photo.php" || path.startsWith("/photo/") ||
            path.startsWith("/photos/") || path.startsWith("/photo") ||
            path.startsWith("/permalink.php") ||
            query.contains("fbid=") || query.contains("photo_id=") ||
            query.contains("set=a.")) return "image"

        // Facebook thumbnails frequently expose the actual CDN media URL directly.
        val lowerUrl = url.lowercase()
        if (host == "fbcdn.net" || host.endsWith(".fbcdn.net")) {
            if (Regex("\\.(mp4|webm|mov|m3u8)(?:[?#]|$)").containsMatchIn(lowerUrl)) return "video"
            if (Regex("\\.(jpg|jpeg|png|webp|gif)(?:[?#]|$)").containsMatchIn(lowerUrl)) return "image"
        }

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

    // ---------------- Custom JS/CSS rule engine ----------------
    // Each rule is independent and identified by a stable UUID. Rules are stored as JSON,
    // so adding/deleting/reordering one rule never changes the meaning of another rule.
    private fun customRulesJson(): org.json.JSONArray {
        val raw = prefs.getString("custom_rules_json", "[]") ?: "[]"
        return try { org.json.JSONArray(raw) } catch (_: Exception) { org.json.JSONArray() }
    }

    private fun saveCustomRulesJson(arr: org.json.JSONArray) {
        prefs.edit().putString("custom_rules_json", arr.toString()).apply()
    }

    private fun migrateLegacyCustomRuleIfNeeded() {
        val css = prefs.getString("css", "") ?: ""
        val js = prefs.getString("js", "") ?: ""
        if (css.isBlank() && js.isBlank()) return
        if (prefs.getBoolean("custom_rules_migrated", false)) return
        val arr = customRulesJson()
        val obj = JSONObject()
        obj.put("id", UUID.randomUUID().toString())
        obj.put("name", "القواعد القديمة")
        obj.put("enabled", true)
        obj.put("scope", "all")
        obj.put("url", "")
        obj.put("priority", 0)
        obj.put("css", css)
        obj.put("js", js)
        arr.put(obj)
        saveCustomRulesJson(arr)
        prefs.edit().putBoolean("custom_rules_migrated", true).apply()
    }

    private fun customRuleMatches(obj: JSONObject, url: String): Boolean {
        if (!obj.optBoolean("enabled", true)) return false
        if (isAuth(url) || url.isBlank()) return false
        return when (obj.optString("scope", "all")) {
            "url" -> {
                val ruleUrl = obj.optString("url", "").trim()
                ruleUrl.isNotEmpty() && matchesConfiguredUrlRule(url, ruleUrl)
            }
            "exceptions" -> {
                prefs.getBoolean("custom_block_enabled", false) &&
                    getListPref("custom_block_exceptions").any { matchesConfiguredUrlRule(url, it) }
            }
            else -> true
        }
    }

    private fun applyCustomRules() {
        if (isAuth(web.url ?: "") || !web.settings.javaScriptEnabled) return
        migrateLegacyCustomRuleIfNeeded()
        val currentUrl = web.url ?: return
        val arr = customRulesJson()
        val rules = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (customRuleMatches(o, currentUrl)) rules.add(o)
        }
        rules.sortWith(compareBy<JSONObject> { it.optInt("priority", 0) }.thenBy { it.optString("id", "") })

        val parts = StringBuilder("(function(){var R={url:location.href,matched:").append(rules.size).append(",css:0,js:0,errors:[]};try{")
        parts.append("window.__SlimRules=window.__SlimRules||{};")
        parts.append("document.querySelectorAll('style[data-slim-custom-rule]').forEach(function(e){e.remove();});")
        parts.append("Object.keys(window.__SlimRules).forEach(function(k){try{var c=window.__SlimRules[k];if(c&&typeof c.cleanup==='function')c.cleanup();}catch(e){}});window.__SlimRules={};")
        for (rule in rules) {
            val id = rule.optString("id", UUID.randomUUID().toString())
            val css = rule.optString("css", "")
            val js = rule.optString("js", "")
            val qid = JSONObject.quote(id)
            val qname = JSONObject.quote(rule.optString("name", id))
            if (css.isNotBlank()) {
                parts.append("try{var s=document.createElement('style');s.setAttribute('data-slim-custom-rule',").append(qid)
                    .append(");s.textContent=").append(JSONObject.quote(css))
                    .append(";(document.head||document.documentElement).appendChild(s);R.css++;}catch(e){R.errors.push('CSS [" ).append(qname).append("]: '+String(e));}")
            }
            if (js.isNotBlank()) {
                parts.append("try{(function(){").append(js).append("}).call(window);R.js++;}catch(e){R.errors.push('JS [").append(qname).append("]: '+String(e));}")
            }
        }
        parts.append("}catch(e){R.errors.push('ENGINE: '+String(e));}try{R.body=!!document.body;R.head=!!document.head;R.ready=document.readyState;R.engine='ok';window.SlimRuleBridge.report(JSON.stringify(R));}catch(e){}})();")
        web.evaluateJavascript(parts.toString()) { result -> }
    }

    private fun showRuleEngineReport() {
        val report = lastRuleEngineReport
        val box = EditText(this).apply { setText(report); isSingleLine=false; minLines=10; gravity=Gravity.TOP; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; setSelection(0) }
        AlertDialog.Builder(this).setTitle("تقرير محرك JS / CSS").setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("إغلاق", null)
            .setNeutralButton("نسخ الخطأ") { _, _ ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("SlimSocial JS/CSS Report", report))
                notifyUser("تم نسخ التقرير. أرسله هنا")
            }.show()
    }

    private fun applyCustomRulesDelayed() {
        if (!web.settings.javaScriptEnabled || isAuth(web.url ?: "")) return
        // Multiple passes are intentional: Facebook is an SPA and can render the target
        // element well after the first page-finished event.
        web.post { applyCustomRules() }
        web.postDelayed({ applyCustomRules() }, 250)
        web.postDelayed({ applyCustomRules() }, 700)
        web.postDelayed({ applyCustomRules() }, 1500)
        web.postDelayed({ applyCustomRules() }, 3000)
    }

    private fun scopeLabel(scope: String): String = when (scope) {
        "url" -> "رابط محدد"
        "exceptions" -> "روابط الاستثناءات"
        else -> "كل صفحات فيسبوك"
    }

    private fun showCustomRulesManager() {
        migrateLegacyCustomRuleIfNeeded()
        val list = mutableListOf<JSONObject>()
        val arr = customRulesJson()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val diagnose = Button(this).apply { text = "🔎 فحص القواعد الحالية + نسخ الأخطاء" }
diagnose.setOnClickListener { applyCustomRules(); web.postDelayed({ showRuleEngineReport() }, 500) }
container.addView(diagnose)
val likePreset = Button(this).apply { text = "👍 إضافة قاعدة اختبار: إخفاء زر إعجاب" }
likePreset.setOnClickListener {
    val a = customRulesJson()
    val o = JSONObject()
    o.put("id", UUID.randomUUID().toString())
    o.put("name", "اختبار - إخفاء زر إعجاب")
    o.put("enabled", true)
    o.put("scope", "all")
    o.put("url", "")
    o.put("priority", 10)
    o.put("css", "[aria-label=\"Like\" i],[aria-label*=\"Like\" i],[aria-label*=\"إعجاب\" i],[aria-label*=\"أعجبني\" i]{display:none!important;visibility:hidden!important;pointer-events:none!important;}")
    o.put("js", """(function(){
function hideLike(){
 document.querySelectorAll('button,a,div[role=\"button\"],span[role=\"button\"]').forEach(function(el){
  var text=(el.innerText||el.textContent||'').trim();
  var aria=el.getAttribute('aria-label')||'';
  var title=el.getAttribute('title')||'';
  if(/^like$/i.test(text)||/^like$/i.test(aria)||/إعجاب|أعجبني/i.test(text)||/إعجاب|أعجبني/i.test(aria)||/^react$/i.test(aria)||/like/i.test(title)){
   el.style.setProperty('display','none','important');
   el.style.setProperty('visibility','hidden','important');
   el.style.setProperty('pointer-events','none','important');
  }
 });
}
hideLike();
if(!window.__slimLikeTestObserver){
 window.__slimLikeTestObserver=new MutationObserver(hideLike);
 window.__slimLikeTestObserver.observe(document.documentElement,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['aria-label','title']});
}
})();""")
    a.put(o)
    saveCustomRulesJson(a)
    notifyUser("تمت إضافة قاعدة اختبار إخفاء الإعجاب")
    applyCustomRulesDelayed()
    refresh()
}
container.addView(likePreset)
val info = TextView(this).apply {
            text = "كل قاعدة مستقلة عن الأخرى. يمكنك إنشاء 10 قواعد أو أكثر. التفعيل والإيقاف والحذف لا يغيّر القواعد الأخرى. عند الخطأ في قاعدة واحدة تستمر بقية القواعد."
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 12)
        }
        container.addView(info)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listBox)

        fun editRule(existing: JSONObject?, onDone: () -> Unit) {
            val id = existing?.optString("id", UUID.randomUUID().toString()) ?: UUID.randomUUID().toString()
            val name = EditText(this).apply { hint = "اسم القاعدة"; setText(existing?.optString("name", "") ?: "") }
            val enabled = Switch(this).apply { text = "تفعيل القاعدة"; isChecked = existing?.optBoolean("enabled", true) ?: true }
            val scopeSpinner = Spinner(this)
            val scopes = arrayOf("كل صفحات فيسبوك", "رابط محدد", "روابط الاستثناءات")
            scopeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scopes)
            val currentScope = existing?.optString("scope", "all") ?: "all"
            scopeSpinner.setSelection(if (currentScope == "url") 1 else if (currentScope == "exceptions") 2 else 0)
            val urlInput = EditText(this).apply { hint = "الرابط (إذا اخترت رابط محدد)"; setText(existing?.optString("url", "") ?: "") }
            val priority = EditText(this).apply { hint = "الأولوية (رقم أكبر = يطبق لاحقًا)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setText((existing?.optInt("priority", 0) ?: 0).toString()) }
            val css = EditText(this).apply { hint = "CSS"; setText(existing?.optString("css", "") ?: ""); minLines = 5; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            val js = EditText(this).apply { hint = "JavaScript"; setText(existing?.optString("js", "") ?: ""); minLines = 7; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 0, 12, 0) }
            form.addView(name); form.addView(enabled); form.addView(scopeSpinner); form.addView(urlInput); form.addView(priority)
            val cssLabel = TextView(this).apply { text = "CSS"; setPadding(0, 10, 0, 2) }
            val jsLabel = TextView(this).apply { text = "JavaScript"; setPadding(0, 10, 0, 2) }
            form.addView(cssLabel); form.addView(css); form.addView(jsLabel); form.addView(js)
            val scroll = ScrollView(this).apply { addView(form) }

            val dialog = AlertDialog.Builder(this).setTitle(if (existing == null) "إضافة قاعدة JS/CSS" else "تعديل قاعدة JS/CSS")
                .setView(scroll)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", null)
            if (existing != null) dialog.setNeutralButton("حذف", null)
            val d = dialog.create()
            d.setOnShowListener {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val ruleName = name.text.toString().trim()
                    val selectedScope = when (scopeSpinner.selectedItemPosition) { 1 -> "url"; 2 -> "exceptions"; else -> "all" }
                    val targetUrl = urlInput.text.toString().trim()
                    if (ruleName.isEmpty()) { notifyUser("اكتب اسمًا للقاعدة"); return@setOnClickListener }
                    if (selectedScope == "url" && targetUrl.isEmpty()) { notifyUser("اكتب الرابط المحدد"); return@setOnClickListener }
                    val o = JSONObject()
                    o.put("id", id); o.put("name", ruleName); o.put("enabled", enabled.isChecked)
                    o.put("scope", selectedScope); o.put("url", targetUrl)
                    o.put("priority", priority.text.toString().toIntOrNull() ?: 0)
                    o.put("css", css.text.toString()); o.put("js", js.text.toString())
                    val a = customRulesJson()
                    var replaced = false
                    for (i in 0 until a.length()) {
                        val old = a.optJSONObject(i)
                        if (old?.optString("id") == id) { a.put(i, o); replaced = true; break }
                    }
                    if (!replaced) a.put(o)
                    saveCustomRulesJson(a)
                    prefs.edit().putBoolean("custom_rules_migrated", true).apply()
                    d.dismiss(); onDone(); applyCustomRulesDelayed()
                }
                if (existing != null) {
                    d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val a = customRulesJson(); val out = org.json.JSONArray()
                        for (i in 0 until a.length()) { val o = a.optJSONObject(i); if (o != null && o.optString("id") != id) out.put(o) }
                        saveCustomRulesJson(out); d.dismiss(); onDone(); applyCustomRulesDelayed()
                    }
                }
            }
            d.show()
        }

        fun refresh() {
            listBox.removeAllViews()
            val a = customRulesJson()
            if (a.length() == 0) {
                listBox.addView(TextView(this).apply { text = "لا توجد قواعد بعد. اضغط + لإضافة أول قاعدة."; setPadding(0, 12, 0, 12) })
                return
            }
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6) }
                val ruleText = TextView(this).apply {
                    text = "${if (o.optBoolean("enabled", true)) "✓" else "○"} ${o.optString("name", "بدون اسم")}\n${scopeLabel(o.optString("scope", "all"))} • أولوية ${o.optInt("priority", 0)}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val edit = Button(this).apply { text = "تعديل" }
                edit.setOnClickListener { editRule(o) { refresh() } }
                row.addView(ruleText); row.addView(edit); listBox.addView(row)
            }
        }
        refresh()
        val add = Button(this).apply { text = "＋ إضافة قاعدة جديدة" }
        add.setOnClickListener { editRule(null) { refresh() } }
        container.addView(add)
        AlertDialog.Builder(this).setTitle("إدارة قواعد JS / CSS").setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("إغلاق", null).show()
    }

    private fun applyCustom(){
        if (isAuth(web.url ?: "") || !web.settings.javaScriptEnabled) return
        applyCustomRules()
        applyControls()
    }

    override fun onBackPressed(){ if(web.canGoBack()) web.goBack() else super.onBackPressed() }
}
