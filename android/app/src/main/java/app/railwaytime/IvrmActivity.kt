package app.railwaytime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * You sign in to CN yourself here (Microsoft sign-in + Authenticator as usual).
 * Starts at SharePoint; tap Mobile Crew. When the crew board shows, it's read and
 * saved, and that page's address is remembered for background refreshes.
 */
class IvrmActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private val followedFrames = mutableSetOf<String>()
    /** Bumped on every page load so only the latest page is read. */
    private var page = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ivrm)
        val store = Store(this)
        val settings = store.settings
        web = findViewById(R.id.web)
        status = findViewById(R.id.ivrm_status)

        CnWeb.configure(web)
        // SharePoint tiles often open links in a new window: show them here instead.
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.settings.setSupportMultipleWindows(true)
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val popup = WebView(this@IvrmActivity)
                popup.webViewClient = object : WebViewClient() {
                    private var handed = false
                    private fun handOff(v: WebView, url: String?): Boolean {
                        if (handed || !CnWeb.isWebUrl(url)) return false
                        handed = true
                        web.loadUrl(url!!)
                        v.post { v.destroy() }
                        return true
                    }

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) =
                        handOff(v, request.url.toString())

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (handOff(v, url)) v.stopLoading()
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !CnWeb.isWebUrl(request.url.toString()) // ignore app links (intent:, ms-…:)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                log("open ${CnWeb.safeUrl(url)}")
                status.text = if (IvrmFetcher.isSignInUrl(url)) "Sign in with your CN account" else "Loading ${hostOf(url)}…"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!IvrmFetcher.isSignInUrl(url) && status.text.startsWith("Loading")) {
                    status.text = "${hostOf(url)} · open Mobile Crew to load your board"
                }
                capture(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    log("error ${CnWeb.safeUrl(request.url.toString())}: ${error.description} (${error.errorCode})")
                    status.text = "Couldn't load ${request.url.host}: ${error.description} (${error.errorCode})"
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) {
                    log("HTTP ${response.statusCode} ${CnWeb.safeUrl(request.url.toString())}")
                    status.text = "${request.url.host} answered HTTP ${response.statusCode} ${response.reasonPhrase.orEmpty()}"
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                log("SSL error ${error.primaryError} ${CnWeb.safeUrl(error.url)}")
                status.text = "Security certificate problem on ${hostOf(error.url)} — not loading it"
                handler.cancel()
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                log("password prompt from $host")
                askLogin(handler, host)
            }

            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                status.text = "${request.host} wants your device certificate — pick the CN one"
                CnWeb.answerClientCert(this@IvrmActivity, request, this@IvrmActivity)
            }
        }

        findViewById<Button>(R.id.btn_ivrm).setOnClickListener { web.loadUrl(settings.ivrmUrl) }
        findViewById<Button>(R.id.btn_sharepoint).setOnClickListener { web.loadUrl(settings.sharepointUrl) }
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
        // Backup: open the same page in Chrome, then share a screenshot back to this app.
        findViewById<Button>(R.id.btn_chrome).setOnClickListener {
            val url = web.url?.takeIf(CnWeb::isWebUrl) ?: settings.ivrmUrl
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome"))
            }.onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        web.loadUrl(store.boardUrl ?: settings.sharepointUrl)
    }

    private fun capture(view: WebView) {
        val mine = ++page
        CnWeb.readBoard(view, stillWanted = { mine == page && !isFinishing }, done = { text ->
            if (text != null) {
                Store(this).saveBoard(text, view.url)
                log("board found at ${CnWeb.safeUrl(view.url)}")
                CookieManager.getInstance().flush()
                Widget.updateAll(this)
                RefreshScheduler.schedule(this)
                status.text = "Board saved ✓ — widget updated. Tap Done."
                return@readBoard
            }
            // Board shown inside the SharePoint page from another site: open it directly.
            view.evaluateJavascript(CnWeb.CREW_FRAMES_JS) { frames ->
                CnWeb.crewFrames(frames).firstOrNull { followedFrames.add(it) }?.let {
                    status.text = "Opening the crew page…"
                    view.loadUrl(it)
                }
            }
        }, forMs = 3_000)
    }

    /** Same username/password prompt Chrome shows for servers that ask for one. Not saved. */
    private fun askLogin(handler: HttpAuthHandler, host: String) {
        val user = EditText(this).apply { hint = "Username (e.g. CN ID)"; inputType = InputType.TYPE_CLASS_TEXT }
        val pass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(user)
            addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle("Sign in to $host")
            .setView(form)
            .setPositiveButton("Sign in") { _, _ -> handler.proceed(user.text.toString(), pass.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    private fun log(line: String) = Store(this).log(line)

    private fun hostOf(url: String?) = url?.let { android.net.Uri.parse(it).host } ?: "page"

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
