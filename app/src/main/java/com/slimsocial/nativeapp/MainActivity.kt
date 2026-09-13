package com.slimsocial.nativeapp

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
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
    @Volatile private var videoPlaying = false
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
        "All buttons" to "كل الأزرار"
    )

    private val tapHandler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private val usageHandler = Handler(Looper.getMainLooper())
    private var usageRunning = false

    private val usageTick = object : Runnable {
        override fun run() {
            checkAndTickUsage()
            if (usageRunning) usageHandler.postDelayed(this, 60000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = !prefs.getBoolean("disable_js", false)
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = WebSettings.getDefaultUserAgent(this)
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun setPlaying(playing: Boolean) { videoPlaying = playing }
            @android.webkit.JavascriptInterface
            fun checkNav(url: String) { runOnUiThread { handleSpaNavigation(url) } }
        }, "SlimBridge")
        web.webViewClient = object: WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val url = r.url.toString()
                if (isRefreshBlocked(url)) return true
                return handleNavigation(url)
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (!isAuth(url)) { applyControls(); applyCustom() }
            }
        }
        web.loadUrl("https://www.facebook.com/")

        val menuBtn = findViewById<View>(R.id.menu)
        val reloadBtn = findViewById<View>(R.id.reload)
        menuBtn.setOnClickListener { checkPasswordThen { showControls() } }
        reloadBtn.setOnClickListener {
            if (prefs.getBoolean("block_refresh", false)) Toast.makeText(this, "تحديث الصفحة معطّل من الإعدادات", Toast.LENGTH_SHORT).show()
            else web.reload()
        }
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
        if (checkDailyLimitExceeded()) { showLimitReachedScreen(); return }
        usageRunning = true
        usageHandler.post(usageTick)
    }

    override fun onPause() {
        super.onPause()
        usageRunning = false
        usageHandler.removeCallbacksAndMessages(null)
    }

    private fun applyToolbarVisibility(menuBtn: View, reloadBtn: View) {
        val hide = prefs.getBoolean("hide_toolbar", false)
        menuBtn.visibility = if (hide) View.GONE else View.VISIBLE
        reloadBtn.visibility = if (hide) View.GONE else View.VISIBLE
    }

    private fun isAuth(url: String): Boolean {
        val u=url.lowercase(); return listOf("/login","/checkpoint","/recover","/reg","/registration").any { u.contains("facebook.com$it") || u.contains("facebook.com$it/") }
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

    private fun handleSpaNavigation(url: String) {
        if (isAuth(url)) return
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
        if (prefs.getBoolean("All buttons",false)) js.append("s+='button,[role=\\\"button\\\"],input[type=button],input[type=submit]{visibility:hidden!important;pointer-events:none!important;}';")
        val map = mapOf("Like / Reactions" to "a[href*='/reaction/'],a[href*='/ufi/reaction'],[aria-label='Like' i],[aria-label='React' i]", "Comments" to "a[href*='comment'],[aria-label*='Comment' i]", "Share" to "a[href*='share'],[aria-label*='Share' i]", "Search" to "a[href*='search'],input[placeholder*='Search' i]", "Messenger" to "a[href*='messages'],a[href*='messenger']", "Stories" to "a[href*='stories']", "Reels / Watch" to "a[href*='reel'],a[href*='watch']", "Marketplace" to "a[href*='marketplace']", "Follow / Friends" to "a[href*='/friends/'],a[href*='add_friend'],a[href*='subscribe'],a[href*='unsubscribe'],[aria-label='Follow' i],[aria-label='Add Friend' i],[aria-label*='Follow' i],[aria-label*='متابعة'],[aria-label*='إضافة صديق']")
        map.forEach { (k,sel) -> if(prefs.getBoolean(k,false)) js.append("s+=`").append(sel).append("{display:none!important;pointer-events:none!important;}`;") }
        if (prefs.getBoolean("dark_mode", false)) js.append("s+='html{filter:invert(1) hue-rotate(180deg) !important;} img,video,iframe{filter:invert(1) hue-rotate(180deg) !important;}';")
        if (prefs.getBoolean("block_images", false)) js.append("s+='img,svg image{visibility:hidden!important;}';")
        if (prefs.getBoolean("block_videos", false)) js.append("s+='video{visibility:hidden!important;}';")
        if (prefs.getBoolean("block_video_swipe", false)) js.append("s+='video,[data-pagelet*=\\\"Reel\\\" i],[role=\\\"main\\\"] video{touch-action:none!important;}body.slim-reel-lock{touch-action:pan-x!important;overflow:hidden!important;}';")
        if (prefs.getBoolean("block_top_nav", false)) js.append("s+='[role=\\\"tablist\\\"],[role=\\\"tablist\\\"] *{visibility:hidden!important;pointer-events:none!important;}';")
        js.append("var st=document.getElementById('slimstyle-tag')||document.createElement('style');st.id='slimstyle-tag';st.textContent=s;document.head.appendChild(st);")
        js.append("if(/\\/(reel|watch)/i.test(location.pathname)){document.body.classList.add('slim-reel-lock');}else{document.body.classList.remove('slim-reel-lock');}")
        js.append("if(!window.__slimSwipeGuard){window.__slimSwipeGuard=true;document.addEventListener('touchmove',function(e){if(window.__slimBlockSwipe){var onReelPage=/\\/(reel|watch)/i.test(location.pathname);var t=e.target.closest('video,[data-pagelet*=\\\"Reel\\\" i],[aria-label*=\\\"Reel\\\" i],[role=\\\"main\\\"] video');if(t||onReelPage){e.preventDefault();}}},{passive:false});}")
        js.append("window.__slimBlockSwipe=").append(prefs.getBoolean("block_video_swipe", false)).append(";")
        js.append("if(!window.__slimVideoTracker){window.__slimVideoTracker=true;function slimHook(v){if(v.__slimHooked)return;v.__slimHooked=true;v.addEventListener('play',function(){if(window.SlimBridge)SlimBridge.setPlaying(true);window.__slimVideoPlaying=true;});v.addEventListener('pause',function(){if(window.SlimBridge)SlimBridge.setPlaying(false);window.__slimVideoPlaying=false;});v.addEventListener('ended',function(){if(window.SlimBridge)SlimBridge.setPlaying(false);window.__slimVideoPlaying=false;});}document.querySelectorAll('video').forEach(slimHook);new MutationObserver(function(){document.querySelectorAll('video').forEach(slimHook);}).observe(document.body,{childList:true,subtree:true});}")
        js.append("window.__slimBlockRefresh=").append(prefs.getBoolean("block_refresh", false)).append(";window.__slimBlockRefreshOnVideo=").append(prefs.getBoolean("block_refresh_on_video", false)).append(";")
        js.append("if(!window.__slimReloadGuard){window.__slimReloadGuard=true;try{var _rl=location.reload.bind(location);location.reload=function(){if(window.__slimBlockRefresh){if(window.SlimBridge)SlimBridge.checkNav(location.href);return;}_rl();};}catch(e){}try{var _go=history.go.bind(history);history.go=function(n){if((n===0||n===undefined)&&window.__slimBlockRefresh){return;}_go(n);};}catch(e){}}")
        js.append("if(!window.__slimPullGuard){window.__slimPullGuard=true;var __slimStartY=0;document.addEventListener('touchstart',function(e){__slimStartY=e.touches[0].clientY;},{passive:true,capture:true});document.addEventListener('touchmove',function(e){var blocked=window.__slimBlockRefresh||(window.__slimBlockRefreshOnVideo&&window.__slimVideoPlaying);if(blocked&&window.scrollY<=2&&e.touches[0].clientY>__slimStartY+3){e.preventDefault();}},{passive:false,capture:true});}")
        js.append("if(!window.__slimSpaGuard){window.__slimSpaGuard=true;window.__slimLastUrl=location.href;function slimCheckSpa(){if(location.href!==window.__slimLastUrl){window.__slimLastUrl=location.href;if(window.SlimBridge)SlimBridge.checkNav(location.href);}}var _ps=history.pushState;history.pushState=function(){_ps.apply(history,arguments);slimCheckSpa();};var _rs=history.replaceState;history.replaceState=function(){_rs.apply(history,arguments);slimCheckSpa();};window.addEventListener('popstate',slimCheckSpa);setInterval(slimCheckSpa,600);}")
        js.append("})();")
        web.evaluateJavascript(js.toString(),null)
    }

    private fun showControls() {
        val box=LinearLayout(this); box.orientation=LinearLayout.VERTICAL; box.setPadding(32,12,32,8)
        val title=TextView(this); title.text="سليم سوشيال • إعدادات فيسبوك"; title.textSize=20f; title.setTextColor(Color.DKGRAY); title.setPadding(0,8,0,18); box.addView(title)

        val counter = TextView(this); counter.text="المحاولات المحظورة: ${prefs.getInt("blocked_count",0)}"; counter.setPadding(0,0,0,16); box.addView(counter)

        blocks.forEach { name -> val sw=Switch(this); sw.text=arabicLabels[name] ?: name; sw.textSize=16f; sw.isChecked=prefs.getBoolean(name,false); sw.setPadding(0,10,0,10); sw.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean(name,v).apply(); if(!isAuth(web.url ?: "")) applyControls() }; box.addView(sw) }

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
            prefs.edit()
                .putString("css",custom.text.toString())
                .putString("js",jsBox.text.toString())
                .putString("group_whitelist", whitelistInput.text.toString())
                .putInt("daily_limit_minutes", limitInput.text.toString().toIntOrNull() ?: 0)
                .apply()
            applyCustom()
        }.setNegativeButton("إغلاق",null).show()
    }

    private fun applyCustom(){
        if(isAuth(web.url ?: "") || !web.settings.javaScriptEnabled) return
        val css=prefs.getString("css","")!!; val js=prefs.getString("js","")!!
        web.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};document.head.appendChild(s);try{${js}}catch(e){}})();",null)
        applyControls()
    }

    override fun onBackPressed(){ if(web.canGoBack()) web.goBack() else super.onBackPressed() }
}
