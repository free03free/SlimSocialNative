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
        val u = url.lowercase()
        val isFacebookDomain = u.contains("facebook.com") || u.contains("fbcdn.net")

        if (prefs.getBoolean("custom_block_enabled", false)) {
            val exceptions = getListPref("custom_block_exceptions")
            val isException = exceptions.any { u.contains(it) }
            if (!isException) {
                val blocklist = getListPref("custom_block_domains")
                if (blocklist.any { it.isNotEmpty() && u.contains(it) }) {
                    runOnUiThread { Toast.makeText(this, "تم منع هذا الرابط (قائمة حظر مخصصة)", Toast.LENGTH_SHORT).show() }
                    incrementBlockedCount()
                    return true
                }
            }
        }

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

    // Where to send the user when a navigation attempt gets blocked.
    // "back"   -> use WebView's own history to snap back to the exact page/scroll spot.
    // "custom" -> always go to a fixed, admin-chosen URL, no exceptions.
    private fun goToBlockedDestination() {
        val mode = prefs.getString("blocked_redirect_mode", "back") ?: "back"
        if (mode == "custom") {
            val customUrl = prefs.getString("blocked_redirect_url", "") ?: ""
            web.loadUrl(if (customUrl.isNotEmpty()) customUrl else getHomeUrl())
        } else {
            if (web.canGoBack()) web.goBack() else web.loadUrl(getHomeUrl())
        }
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
                    val raw = result?.trim('
