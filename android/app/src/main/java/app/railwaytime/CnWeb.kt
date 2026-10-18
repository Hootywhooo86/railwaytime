package app.railwaytime

import android.annotation.SuppressLint
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
    ) {
        val until = SystemClock.uptimeMillis() + forMs
        fun attempt() {
            if (!stillWanted()) return
            view.evaluateJavascript(PageText.EXTRACT_JS) { json ->
                if (!stillWanted()) return@evaluateJavascript
                val text = IvrmFetcher.decodeJs(json)
                when {
                    BoardParser.looksLikeBoard(text) -> done(text)
                    SystemClock.uptimeMillis() >= until -> done(null)
                    else -> view.postDelayed(::attempt, everyMs)
                }
            }
        }
        attempt()
    }

    fun isWebUrl(url: String?): Boolean = url != null && (url.startsWith("https://") || url.startsWith("http://"))

    /** Parses the [CREW_FRAMES_JS] result. */
    fun crewFrames(json: String?): List<String> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { a.getString(it) }.filter(::isWebUrl)
    }.getOrDefault(emptyList())
}
