package app.railwaytime

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.view.ViewGroup
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * You sign in to CN yourself here (Microsoft sign-in + Authenticator as usual).
 * Starts at SharePoint; tap Mobile Crew, which opens as a second page on top (like a new
 * Chrome tab). When the crew board shows, it's read and saved, and that page's address is
 * remembered for background refreshes. Back closes the second page.
 */
class IvrmActivity : Activity() {
    private lateinit var frame: FrameLayout
    private lateinit var status: TextView
    /** Open pages, bottom first: SharePoint, then any page it opened (Mobile Crew). */
    private val pages = mutableListOf<WebView>()
    private val top: WebView get() = pages.last()
    private val followedFrames = mutableSetOf<String>()
    /** Bumped on every page load so only each page's latest load is read. */
    private val loads = HashMap<WebView, Int>()
    /** Open Mobile Crew by itself once SharePoint is up (start, or after IVRM answers with an error). */
    private var autoCrew = true
    /** Already fell back from a failed IVRM load to SharePoint (don't loop). */
    private var fellBack = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var sharepointUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ivrm)
        val store = Store(this)
        val settings = store.settings
        frame = findViewById(R.id.web_frame)
        status = findViewById(R.id.ivrm_status)

        val main = findViewById<WebView>(R.id.web)
        setUp(main)
        pages += main

        sharepointUrl = settings.sharepointUrl
        findViewById<Button>(R.id.btn_ivrm).setOnClickListener {
            closeSecondPages()
            autoCrew = false
            fellBack = false
            main.loadUrl(settings.ivrmUrl)
        }
        findViewById<Button>(R.id.btn_sharepoint).setOnClickListener {
            closeSecondPages()
            autoCrew = false
            main.loadUrl(settings.sharepointUrl)
        }
        findViewById<Button>(R.id.btn_done).setOnClickListener { finish() }
        // Backup: open the same page in Chrome, then share a screenshot back to this app.
        findViewById<Button>(R.id.btn_chrome).setOnClickListener {
            val url = top.url?.takeIf(CnWeb::isWebUrl) ?: settings.ivrmUrl
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.android.chrome"))
            }.onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        // The way that works: SharePoint (sign in if asked), then Mobile Crew, opened for you.
        // Going straight to a saved IVRM address fails once that IVRM visit has expired.
        main.loadUrl(settings.sharepointUrl)
    }

    private fun setUp(view: WebView) {
        CnWeb.configure(view)
        view.settings.javaScriptCanOpenWindowsAutomatically = true
        view.settings.setSupportMultipleWindows(true)
        view.webChromeClient = chrome
        view.webViewClient = client
    }

    private val chrome = object : WebChromeClient() {
        // Mobile Crew opens in a new window: give it a real second page on top.
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val page = WebView(this@IvrmActivity)
            setUp(page)
            frame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            pages += page
            log("second page opened")
            (resultMsg.obj as WebView.WebViewTransport).webView = page
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) = closePage(window)
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            !CnWeb.isWebUrl(request.url.toString()) // ignore app links (intent:, ms-…:)

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            log("open ${CnWeb.safeUrl(url)}")
            status.text = if (IvrmFetcher.isSignInUrl(url)) "Sign in with your CN account" else "Loading ${hostOf(url)}…"
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view == top && !IvrmFetcher.isSignInUrl(url) && status.text.startsWith("Loading")) {
                status.text = "${hostOf(url)} · open Mobile Crew to load your board"
            }
            capture(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                log("error ${CnWeb.safeUrl(request.url.toString())}: ${error.description} (${error.errorCode})")
                status.text = "Couldn't load ${request.url.host}: ${error.description} (${error.errorCode})"
                ivrmFailed(request.url.host)
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) {
                log("HTTP ${response.statusCode} ${CnWeb.safeUrl(request.url.toString())}")
                status.text = "${request.url.host} answered HTTP ${response.statusCode} ${response.reasonPhrase.orEmpty()}"
                ivrmFailed(request.url.host)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            log("SSL error ${error.primaryError} ${CnWeb.safeUrl(error.url)}")
            if (!CnTls.handle(this@IvrmActivity, error, handler, ::log)) {
                status.text = "Security certificate problem on ${hostOf(error.url)} (${error.primaryError}) — tap Chrome, or share debug info"
            }
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

    private fun capture(view: WebView) {
        val mine = (loads[view] ?: 0) + 1
        loads[view] = mine
        CnWeb.readBoard(view, stillWanted = { loads[view] == mine && view in pages && !isFinishing }, done = { text ->
            if (text != null) {
                Store(this).saveBoard(text, view.url)
                Duty.checkWorking(this)
                log("board found at ${CnWeb.safeUrl(view.url)}")
                CookieManager.getInstance().flush()
                Widget.updateAll(this)
                RefreshScheduler.scheduleNext(this)
                status.text = "Board saved ✓ — widget updated. Tap Done."
                return@readBoard
            }
            // Board shown inside the page from another site: open it directly.
            view.evaluateJavascript(CnWeb.CREW_FRAMES_JS) { frames ->
                CnWeb.crewFrames(frames).firstOrNull { followedFrames.add(it) }?.let {
                    status.text = "Opening the crew page…"
                    view.loadUrl(it)
                }
            }
            if (autoCrew && hostOf(view.url).contains("sharepoint", ignoreCase = true)) openMobileCrew(view)
        }, forMs = 3_000)
    }

    /** Taps SharePoint's Mobile Crew link for you, waiting up to 20 s for the page to draw it. */
    private fun openMobileCrew(view: WebView, triesLeft: Int = 40) {
        if (!autoCrew || isFinishing || view !in pages || triesLeft == 0) return
        status.text = "Opening Mobile Crew…"
        view.evaluateJavascript(CnWeb.MOBILE_CREW_JS) { json ->
            val found = runCatching { org.json.JSONObject(IvrmFetcher.decodeJs(json)) }.getOrNull()
            when {
                found?.has("href") == true -> {
                    autoCrew = false
                    log("opened Mobile Crew")
                    view.loadUrl(found.getString("href"))
                }
                found?.has("clicked") == true -> {
                    autoCrew = false
                    log("clicked Mobile Crew")
                }
                else -> handler.postDelayed({ openMobileCrew(view, triesLeft - 1) }, 500)
            }
        }
    }

    /**
     * IVRM answered with an error (seen as "Web page not available … ERR_HTTP_RESPONSE_CODE_FAILURE"):
     * the saved IVRM visit has expired. Start a fresh one the way Chrome effectively does —
     * SharePoint → Mobile Crew — once, so it can't loop.
     */
    private fun ivrmFailed(host: String?) {
        if (host?.contains("ivrm", ignoreCase = true) != true) return
        if (fellBack) {
            // Even a fresh visit from SharePoint is refused: CN's security doesn't let this browser in.
            status.text = "CN is blocking this in-app browser (403). Use UPDATE FROM CHROME on the main screen."
            return
        }
        fellBack = true
        log("IVRM answered with an error — going via SharePoint → Mobile Crew")
        status.text = "IVRM visit expired — going via SharePoint → Mobile Crew…"
        handler.post {
            closeSecondPages()
            autoCrew = true
            pages.first().loadUrl(sharepointUrl)
        }
    }

    private fun closePage(view: WebView) {
        if (view == pages.first() || !pages.remove(view)) return
        loads.remove(view)
        frame.removeView(view)
        view.destroy()
        status.text = "${hostOf(top.url)}"
    }

    private fun closeSecondPages() = pages.drop(1).forEach(::closePage)

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

    private fun hostOf(url: String?) = url?.let { Uri.parse(it).host } ?: "page"

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            top.canGoBack() -> top.goBack()
            pages.size > 1 -> closePage(top)
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        pages.forEach { it.destroy() }
        super.onDestroy()
    }
}
