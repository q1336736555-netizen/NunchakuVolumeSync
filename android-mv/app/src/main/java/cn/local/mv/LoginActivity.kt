package cn.local.mv

import android.app.Activity
import android.os.Bundle
import android.webkit.*
import android.widget.*

class LoginActivity : Activity() {
    private lateinit var web: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(Button(this).apply { text = "返回我的收藏夹"; setOnClickListener { finish() } })
        web = WebView(this)
        layout.addView(web, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(layout)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                return request.url.scheme != "https" || !(host == "bilibili.com" || host.endsWith(".bilibili.com"))
            }
            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (CookieManager.getInstance().getCookie(Bili.site).orEmpty().split(';').any { it.trim().startsWith("SESSDATA=") }) finish()
            }
        }
        web.loadUrl("https://passport.bilibili.com/h5-app/passport/login")
    }
    override fun onDestroy() {
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}

