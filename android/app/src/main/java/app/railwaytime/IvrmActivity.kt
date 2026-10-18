package app.railwaytime

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import app.railwaytime.core.BoardParser
import app.railwaytime.core.PageText

/**
 * You sign in to CN yourself here (Microsoft sign-in + Authenticator as usual).
 * When the IVRM board page shows, it's read and saved, and the widget refreshes.
 */
class IvrmActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ivrm)
        val store = Store(this)
        val settings = store.settings
        web = findViewById(R.id.web)
        status = findViewById(R.id.ivrm_status)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                status.text = if (IvrmFetcher.isSignInUrl(url)) "Sign in with your CN account" else "Loading…"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.postDelayed({ capture(view) }, 1_500)
            }
        }

        findViewById<Button>(R.id.btn_ivrm).setOnClickListener { web.loadUrl(settings.ivrmUrl) }
        findViewById<Button>(R.id.btn_sharepoint).setOnClickListener { web.loadUrl(settings.sharepointUrl) }
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }

        web.loadUrl(settings.ivrmUrl)
    }

    private fun capture(view: WebView) {
        if (isFinishing) return
        view.evaluateJavascript(PageText.EXTRACT_JS) { json ->
            val text = IvrmFetcher.decodeJs(json)
            if (!BoardParser.looksLikeBoard(text)) return@evaluateJavascript
            Store(this).saveBoard(text)
            CookieManager.getInstance().flush()
            Widget.updateAll(this)
            RefreshScheduler.schedule(this)
            status.text = "Board saved ✓ — widget updated. Tap Done."
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
