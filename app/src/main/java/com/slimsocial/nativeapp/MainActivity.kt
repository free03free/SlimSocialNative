package com.slimsocial.nativeapp

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject

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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = WebSettings.getDefaultUserAgent(this)
        web.webViewClient = object: WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = false
            override fun onPageFinished(v: WebView, url: String) { if (!isAuth(url)) applyControls() }
        }
        web.loadUrl("https://www.facebook.com/")
        findViewById<View>(R.id.menu).setOnClickListener { checkPasswordThen { showControls() } }
        findViewById<View>(R.id.reload).setOnClickListener { web.reload() }
    }

    private fun isAuth(url: String): Boolean {
        val u=url.lowercase(); return listOf("/login","/checkpoint","/recover","/reg","/registration").any { u.contains("facebook.com$it") || u.contains("facebook.com$it/") }
    }

    private fun checkPasswordThen(action: () -> Unit) {
        val saved = prefs.getString("app_password", null)
        if (saved.isNullOrEmpty()) {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            input.hint = "اختر كلمة مرور للإعدادات"
            AlertDialog.Builder(this)
                .setTitle("تعيين كلمة مرور")
                .setMessage("هذه أول مرة، اختر كلمة مرور لحماية الإعدادات مستقبلاً")
                .setView(input)
                .setPositiveButton("حفظ") { _, _ ->
                    val pwd = input.text.toString()
                    if (pwd.isNotBlank()) {
                        prefs.edit().putString("app_password", pwd).apply()
                        action()
                    } else {
                        Toast.makeText(this, "كلمة المرور لا يمكن أن تكون فارغة", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        } else {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            input.hint = "أدخل كلمة المرور"
            AlertDialog.Builder(this)
                .setTitle("الإعدادات محمية")
                .setView(input)
                .setPositiveButton("دخول") { _, _ ->
                    if (input.text.toString() == saved) {
                        action()
                    } else {
                        Toast.makeText(this, "كلمة مرور خاطئة", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun applyControls() {
        val js = StringBuilder("(function(){var s='';")
        if (prefs.getBoolean("All buttons",false)) js.append("s+='button,[role=\\\"button\\\"],input[type=button],input[type=submit]{visibility:hidden!important;pointer-events:none!important;}';")
        val map = mapOf("Like / Reactions" to "a[href*='/reaction/'],a[href*='/ufi/reaction'],[aria-label='Like' i],[aria-label='React' i]", "Comments" to "a[href*='comment'],[aria-label*='Comment' i]", "Share" to "a[href*='share'],[aria-label*='Share' i]", "Search" to "a[href*='search'],input[placeholder*='Search' i]", "Messenger" to "a[href*='messages'],a[href*='messenger']", "Stories" to "a[href*='stories']", "Reels / Watch" to "a[href*='reel'],a[href*='watch']", "Marketplace" to "a[href*='marketplace']")
        map.forEach { (k,sel) -> if(prefs.getBoolean(k,false)) js.append("s+=`").append(sel).append("{display:none!important;pointer-events:none!important;}`;") }
        js.append("var st=document.createElement('style');st.textContent=s;document.head.appendChild(st);document.addEventListener('click',function(e){var t=e.target.closest('a,button,[role=button]');if(t&&t.dataset.blocked==='1'){e.preventDefault();e.stopImmediatePropagation();}},true);document.querySelectorAll('a,button,[role=button]').forEach(function(x){if(x.closest('style'))return;});})();")
        web.evaluateJavascript(js.toString(),null)
    }

    private fun showControls() {
        val box=LinearLayout(this); box.orientation=LinearLayout.VERTICAL; box.setPadding(32,12,32,8)
        val title=TextView(this); title.text="سليم سوشيال • إعدادات فيسبوك"; title.textSize=20f; title.setTextColor(Color.DKGRAY); title.setPadding(0,8,0,18); box.addView(title)
        blocks.forEach { name -> val sw=Switch(this); sw.text=arabicLabels[name] ?: name; sw.textSize=16f; sw.isChecked=prefs.getBoolean(name,false); sw.setPadding(0,10,0,10); sw.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean(name,v).apply(); if(!isAuth(web.url ?: "")) applyControls() }; box.addView(sw) }
        val custom=EditText(this); custom.hint="CSS مخصص (اختياري)"; custom.setText(prefs.getString("css","")); box.addView(custom)
        val js=EditText(this); js.hint="JavaScript مخصص (اختياري)"; js.setText(prefs.getString("js","")); box.addView(js)
        val changePwd = TextView(this); changePwd.text="تغيير كلمة المرور"; changePwd.setTextColor(Color.BLUE); changePwd.setPadding(0,20,0,0)
        changePwd.setOnClickListener {
            val newInput = EditText(this)
            newInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            newInput.hint = "كلمة المرور الجديدة"
            AlertDialog.Builder(this).setTitle("تغيير كلمة المرور").setView(newInput)
                .setPositiveButton("حفظ"){_,_-> if(newInput.text.toString().isNotBlank()) { prefs.edit().putString("app_password", newInput.text.toString()).apply(); Toast.makeText(this,"تم التغيير",Toast.LENGTH_SHORT).show() } }
                .setNegativeButton("إلغاء",null).show()
        }
        box.addView(changePwd)
        AlertDialog.Builder(this).setView(ScrollView(this).apply{addView(box)}).setPositiveButton("حفظ"){_,_-> prefs.edit().putString("css",custom.text.toString()).putString("js",js.text.toString()).apply(); applyCustom() }.setNegativeButton("إغلاق",null).show()
    }
    private fun applyCustom(){ if(isAuth(web.url ?: "")) return; val css=prefs.getString("css","")!!; val js=prefs.getString("js","")!!; web.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};document.head.appendChild(s);try{${js}}catch(e){}})();",null); applyControls() }
    override fun onBackPressed(){ if(web.canGoBack()) web.goBack() else super.onBackPressed() }
}
