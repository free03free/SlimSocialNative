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
        web.webViewClient = object: WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val url = r.url.toString()
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
        reloadBtn.setOnClickListener { web.reload() }
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

    private fun handleNavigation(url: String): Boolean {
        val u = url.lowercase()
        val isFacebookDomain = u.contains("facebook.com") || u.contains("fbcdn.net")

        if (prefs.getBoolean("block_external", false) && !isFacebookDomain) {
            runOnUiThread { Toast.makeText(this, "تم منع رابط خارجي", Toast.LENGTH_SHORT).show() }
            incrementBlockedCount()
            return true
        }

        if (prefs.getBoolean("block_unjoined_groups", false) && u.contains("/groups/")) {
            val groupId = extractGroupId(u)
            val whitelist = prefs.getString("group_whitelist", "") ?: ""
            val allowed = whitelist.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (groupId != null && !allowed.contains(groupId)) {
                runOnUiThread { Toast.makeText(this, "مجموعة غير مسموح بها. أضفها من الإعدادات إن أردت السماح", Toast.LENGTH_LONG).show() }
                incrementBlockedCount()
                return true
            }
        }

        return false
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
        val map = mapOf("Like / Reactions" to "a[href*='/reaction/'],a[href*='/ufi/reaction'],[aria-label='Like' i],[aria-label='React' i]", "Comments" to "a[href*='comment'],[aria-label*='Comment' i]", "Share" to "a[href*='share'],[aria-label*='Share' i]", "Search" to "a[href*='search'],input[placeholder*='Search' i]", "Messenger" to "a[href*='messages'],a[href*='messenger']", "Stories" to "a[href*='stories']", "Reels / Watch" to "a[href*='reel'],a[href*='watch']", "Marketplace" to "a[href*='marketplace']")
        map.forEach { (k,sel) -> if(prefs.getBoolean(k,false)) js.append("s+=`").append(sel).append("{display:none!important;pointer-events:none!important;}`;") }
        if (prefs.getBoolean("dark_mode", false)) js.append("s+='html{filter:invert(1) hue-rotate(180deg) !important;} img,video,iframe{filter:invert(1) hue-rotate(180deg) !important;}';")
        js.append("var st=document.getElementById('slimstyle-tag')||document.createElement('style');st.id='slimstyle-tag';st.textContent=s;document.head.appendChild(st);})();")
        web.evaluateJavascript(js.toString(),null)
    }

    private fun showControls() {
        val box=LinearLayout(this); box.orientation=LinearLayout.VERTICAL; box.setPadding(32,12,32,8)
        val title=TextView(this); title.text="سليم سوشيال • إعدادات فيسبوك"; title.textSize=20f; title.setTextColor(Color.DKGRAY); title.setPadding(0,8,0,18); box.addView(title)

        val counter = TextView(this); counter.text="المحاولات المحظورة: ${prefs.getInt("blocked_count",0)}"; counter.setPadding(0,0,0,16); box.addView(counter)

        blocks.forEach { name -> val sw=Switch(this); sw.text=arabicLabels[name] ?: name; sw.textSize=16f; sw.isChecked=prefs.getBoolean(name,false); sw.setPadding(0,10,0,10); sw.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean(name,v).apply(); if(!isAuth(web.url ?: "")) applyControls() }; box.addView(sw) }

        val sep1 = TextView(this); sep1.text="— خيارات إضافية —"; sep1.setPadding(0,20,0,10); sep1.setTextColor(Color.GRAY); box.addView(sep1)

        val swExternal = Switch(this); swExternal.text="منع الروابط الخارجية"; swExternal.isChecked=prefs.getBoolean("block_external",false)
        swExternal.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_external",v).apply() }
        box.addView(swExternal)

        val swGroups = Switch(this); swGroups.text="منع مجموعات غير مشترك فيها"; swGroups.isChecked=prefs.getBoolean("block_unjoined_groups",false)
        swGroups.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("block_unjoined_groups",v).apply() }
        box.addView(swGroups)

        val allowGroupBtn = TextView(this); allowGroupBtn.text="➕ سماح بالمجموعة الحالية"; allowGroupBtn.setTextColor(Color.BLUE); allowGroupBtn.setPadding(0,10,0,10)
        allowGroupBtn.setOnClickListener {
            val currentUrl = web.url ?: ""
            val gid = extractGroupId(currentUrl.lowercase())
            if (gid != null) {
                val current = prefs.getString("group_whitelist","") ?: ""
                val list = current.split(",").map{it.trim()}.filter{it.isNotEmpty()}.toMutableList()
                if (!list.contains(gid)) list.add(gid)
                prefs.edit().putString("group_whitelist", list.joinToString(",")).apply()
                Toast.makeText(this,"تمت الإضافة للمسموح بها",Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this,"أنت لست في صفحة مجموعة حاليًا",Toast.LENGTH_SHORT).show()
        }
        box.addView(allowGroupBtn)

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
