package app.railwaytime

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.security.KeyChain
import android.webkit.ClientCertRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import app.railwaytime.core.BoardParser
import app.railwaytime.core.PageText
import org.json.JSONArray

/** WebView setup shared by the sign-in screen and background refresh. */
object CnWeb {
    /** Finds embedded IVRM / crew pages (SharePoint can show them in an iframe we can't read across sites). */
    const val CREW_FRAMES_JS = """
(function(){
  return Array.prototype.map.call(document.querySelectorAll('iframe'), function(f){ return f.src || ''; })
    .filter(function(s){ return /ivrm|crew/i.test(s); });
})()
"""

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(web: WebView) {
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        // Look like Chrome: some sign-in pages turn away in-app browsers ("; wv").
        web.settings.userAgentString = WebSettings.getDefaultUserAgent(web.context)
            .replace("; wv", "")
            .replace(Regex("""\sVersion/[\d.]+"""), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
    }

    /**
     * Reads the page every [everyMs] until it shows a crew board, then calls [done] with the text;
     * after [forMs] without one, calls [done] with null. Stops quietly once [stillWanted] is false.
     */
    fun readBoard(
        view: WebView,
        stillWanted: () -> Boolean,
        done: (String?) -> Unit,
        everyMs: Long = 300,
        forMs: Long = 6_000,
        onText: (String) -> Unit = {},
    ) {
        val until = SystemClock.uptimeMillis() + forMs
        // Not view.postDelayed: a WebView that isn't on screen (background refresh) never runs those.
        val main = Handler(Looper.getMainLooper())
        fun attempt() {
            if (!stillWanted()) return
            view.evaluateJavascript(PageText.EXTRACT_JS) { json ->
                if (!stillWanted()) return@evaluateJavascript
                val text = IvrmFetcher.decodeJs(json)
                onText(text)
                when {
                    BoardParser.looksLikeBoard(text) -> done(text)
                    SystemClock.uptimeMillis() >= until -> done(null)
                    else -> main.postDelayed(::attempt, everyMs)
                }
            }
        }
        attempt()
    }

    /** Scheme, host and path only: no query strings (they can carry sign-in tokens). */
    fun safeUrl(url: String?): String = url?.let {
        runCatching { Uri.parse(it).let { u -> "${u.scheme}://${u.host}${u.path.orEmpty()}" } }.getOrNull()
    } ?: "?"

    /**
     * A site asked for a device certificate (company phones often have one from Intune).
     * Chrome offers it automatically; WebView refuses unless we answer. With [activity] you get
     * Android's certificate picker; the choice is remembered for background refreshes.
     */
    fun answerClientCert(context: Context, request: ClientCertRequest, activity: Activity?) {
        val store = Store(context)
        store.log("certificate requested by ${request.host}")
        fun proceedWith(alias: String) = Thread {
            val key = runCatching { KeyChain.getPrivateKey(context, alias) }.getOrNull()
            val chain = runCatching { KeyChain.getCertificateChain(context, alias) }.getOrNull()
            if (key != null && chain != null) request.proceed(key, chain) else {
                store.log("certificate $alias not usable")
                request.cancel()
            }
        }.start()
        val saved = store.certAlias
        when {
            activity != null -> KeyChain.choosePrivateKeyAlias(
                activity, { alias ->
                    if (alias == null) {
                        store.log("no certificate chosen")
                        request.cancel()
                    } else {
                        store.certAlias = alias
                        proceedWith(alias)
                    }
                },
                request.keyTypes, request.principals, request.host, request.port, saved,
            )
            saved != null -> proceedWith(saved)
            else -> request.cancel()
        }
    }

    /**
     * Finds SharePoint's "Mobile Crew Inquiry" link. Returns {"href": …} when it's a plain link,
     * {"clicked": true} when it had to be clicked (it opens a new window), or {} if not drawn yet.
     */
    const val MOBILE_CREW_JS = """
(function(){
  var re=/mobile\s*crew/i;
  var els=document.querySelectorAll('a,button,[role=link],[role=button],[aria-label],[title]');
  for(var i=0;i<els.length;i++){
    var e=els[i];
    var t=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.textContent||'');
    if(!re.test(t))continue;
    var a=e.closest('a');
    if(a&&a.href&&/^https?:/.test(a.href)&&a.target!=='_blank')return JSON.stringify({href:a.href});
    e.click();
    return JSON.stringify({clicked:true});
  }
  return '{}';
})()
"""

    fun isWebUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

    /** Parses the [CREW_FRAMES_JS] result. */
    fun crewFrames(json: String?): List<String> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getString(it) }.filter(::isWebUrl)
    }.getOrDefault(emptyList())
}
