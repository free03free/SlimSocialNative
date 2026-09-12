package com.slimsocial.nativeapp

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.*
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("controls", MODE_PRIVATE) }
    private val blocks = listOf("Like / Reactions","Comments","Share","Follow / Friends","Profiles","Messenger","Stories","Reels / Watch","Search","Post creation","Upload","Marketplace","Group interactions","All buttons")

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
        findViewById<View>(R.id.menu).setOnClickListener { showControls() }
        findViewById<View>(R.id.reload).setOnClickListener { web.reload() }
    }

    private fun isAuth(url: String): Boolean {
        val u=url.lowercase(); return listOf("/login","/checkpoint","/recover","/reg","/registration").any { u.contains("facebook.com$it") || u.contains("facebook.com$it/") }
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
        val title=TextView(this); title.text="SlimSocial • Facebook Controls"; title.textSize=20f; title.setTextColor(Color.DKGRAY); title.setPadding(0,8,0,18); box.addView(title)
        blocks.forEach { name -> val sw=Switch(this); sw.text=name; sw.textSize=16f; sw.isChecked=prefs.getBoolean(name,false); sw.setPadding(0,10,0,10); sw.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean(name,v).apply(); if(!isAuth(web.url ?: "")) applyControls() }; box.addView(sw) }
        val custom=EditText(this); custom.hint="Custom CSS (optional)"; custom.setText(prefs.getString("css","")); box.addView(custom)
        val js=EditText(this); js.hint="Custom JavaScript (optional)"; js.setText(prefs.getString("js","")); box.addView(js)
        AlertDialog.Builder(this).setView(ScrollView(this).apply{addView(box)}).setPositiveButton("حفظ"){_,_-> prefs.edit().putString("css",custom.text.toString()).putString("js",js.text.toString()).apply(); applyCustom() }.setNegativeButton("إغلاق",null).show()
    }
    private fun applyCustom(){ if(isAuth(web.url ?: "")) return; val css=prefs.getString("css","")!!; val js=prefs.getString("js","")!!; web.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=${JSONObject.quote(css)};document.head.appendChild(s);try{${js}}catch(e){}})();",null); applyControls() }
    override fun onBackPressed(){ if(web.canGoBack()) web.goBack() else super.onBackPressed() }
}
