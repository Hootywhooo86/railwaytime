package app.railwaytime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.railwaytime.core.BoardParser
import app.railwaytime.core.PageText
import app.railwaytime.core.RailClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.coroutines.resume

/** On duty = nothing talks to CN; the widget only shows the clocks. */
object Duty {
    fun set(context: Context, onDuty: Boolean) {
        Store(context).onDuty = onDuty
        if (onDuty) {
            RefreshScheduler.cancel(context)
        } else {
            RefreshScheduler.refreshNow(context)
            RefreshScheduler.schedule(context)
        }
        Widget.updateAll(context)
    }

    fun toggle(context: Context) = set(context, !Store(context).onDuty)
}

/** Every [AppSettings.refreshMin] minutes while off duty, via inexact alarms (WorkManager's floor is 15). */
object RefreshScheduler {
    const val ACTION_REFRESH = "app.railwaytime.REFRESH"
    const val ACTION_RENDER = "app.railwaytime.RENDER"
    private const val WORK = "refresh"

    private fun pending(context: Context, action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context) {
        val store = Store(context)
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, ACTION_REFRESH))
        if (!store.onDuty) {
            val every = store.settings.refreshMin.coerceAtLeast(5) * 60_000L
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + every, pending(context, ACTION_REFRESH))
        }
        // Redraw the widget clock when railtime changes offset (Nov 1), even on duty.
        RailClock.nextSwitch(store.settings, Instant.now())?.let {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.toEpochMilli() + 1_000, pending(context, ACTION_RENDER))
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, ACTION_REFRESH))
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    const val KEY_MANUAL = "manual"

    /** [manual]: you asked for it (widget or app), so it also runs while signed out. */
    fun refreshNow(context: Context, manual: Boolean = false) {
        if (Store(context).onDuty) return
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(KEY_MANUAL to manual))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RefreshScheduler.ACTION_REFRESH -> RefreshScheduler.refreshNow(context)
            RefreshScheduler.ACTION_RENDER -> Unit
            // Boot / app update: alarms are gone, set them again.
            else -> RefreshScheduler.refreshNow(context)
        }
        RefreshScheduler.schedule(context)
        Widget.updateAll(context)
    }
}

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        if (store.onDuty) return Result.success()
        val manual = inputData.getBoolean(RefreshScheduler.KEY_MANUAL, false)
        // Signed out: only look again every half hour (or when you tap refresh) until you sign in.
        val signedOutRecently = store.status == Store.Status.SIGN_IN &&
            System.currentTimeMillis() - store.statusAt < SIGNED_OUT_RETRY_MS
        if (signedOutRecently && !manual) return Result.success()

        val url = store.boardUrl ?: store.settings.ivrmUrl
        store.refreshing = true
        Widget.updateAll(applicationContext)
        store.log("refresh${if (manual) " (tapped)" else ""}: ${CnWeb.safeUrl(url)}")
        try {
            when (val r = IvrmFetcher.fetch(applicationContext, url)) {
                is IvrmFetcher.Result.Board -> {
                    store.saveBoard(r.text, r.url)
                    store.log("refresh: board updated")
                }
                IvrmFetcher.Result.SignIn -> {
                    store.setStatus(Store.Status.SIGN_IN)
                    store.log("refresh: CN sign-in page stayed up — signed out")
                }
                is IvrmFetcher.Result.Failed -> {
                    store.setStatus(Store.Status.ERROR, r.reason)
                    store.log("refresh failed: ${r.reason}")
                }
            }
        } finally {
            store.refreshing = false
            Widget.updateAll(applicationContext)
        }
        return Result.success()
    }

    private companion object {
        const val SIGNED_OUT_RETRY_MS = 30 * 60_000L
    }
}

/**
 * Loads IVRM in an off-screen WebView with the cookies from your own sign-in
 * (see [IvrmActivity]) and reads the page. Nothing is sent anywhere else.
 */
object IvrmFetcher {
    sealed interface Result {
        data class Board(val text: String, val url: String?) : Result
        data object SignIn : Result
        data class Failed(val reason: String) : Result
    }

    private val SIGN_IN_HOSTS = listOf("login.microsoftonline.com", "login.microsoft.com", "login.live.com", "adfs", "sts.", "okta")

    fun isSignInUrl(url: String?): Boolean = url != null && SIGN_IN_HOSTS.any { url.contains(it, ignoreCase = true) }

    /**
     * Fast path: one plain request with your saved cookies. Works when the server sends the
     * board in the page itself (no scripts needed); otherwise null and the WebView takes over.
     * Redirects aren't followed, so a sign-in redirect also falls back to the WebView.
     */
    private suspend fun quickFetch(context: Context, url: String): Result? {
        val cookies = CookieManager.getInstance().getCookie(url) ?: return null
        val agent = WebSettings.getDefaultUserAgent(context).replace("; wv", "")
        return withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 8_000
                    c.readTimeout = 10_000
                    c.setRequestProperty("Cookie", cookies)
                    c.setRequestProperty("User-Agent", agent)
                    if (c.responseCode != 200) return@runCatching null
                    val text = PageText.fromHtml(c.inputStream.bufferedReader().use { it.readText() })
                    if (BoardParser.looksLikeBoard(text)) Result.Board(text, url) else null
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
        }
    }

    /** Decodes evaluateJavascript's JSON string result. */
    fun decodeJs(json: String?): String = runCatching { JSONTokener(json).nextValue() as? String }.getOrNull() ?: ""

    /** How long a sign-in page may stay up before we decide you're signed out (SSO passes through faster). */
    private const val SIGN_IN_SETTLE_MS = 8_000L

    suspend fun fetch(context: Context, url: String, timeoutMs: Long = 60_000): Result = withContext(Dispatchers.Main) {
        quickFetch(context, url)?.let { return@withContext it }
        val main = Handler(Looper.getMainLooper())
        val web = WebView(context.applicationContext)
        CnWeb.configure(web)
        // The widget doesn't need pictures.
        web.settings.loadsImagesAutomatically = false
        web.settings.blockNetworkImage = true
        var page = 0
        var lastUrl: String? = null
        var lastError: String? = null
        val followedFrames = mutableSetOf<String>()
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            lastUrl = pageUrl
                            page++
                        }

                        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                            handler.cancel()
                            if (cont.isActive) cont.resume(Result.SignIn)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) lastError = "${request.url.host}: ${error.description}"
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            if (!CnTls.handle(context, error, handler) { Store(context).log("background: $it") }) {
                                lastError = "certificate problem on ${CnWeb.safeUrl(error.url)} (${error.primaryError})"
                            }
                        }

                        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                            CnWeb.answerClientCert(context, request, activity = null)
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            lastUrl = pageUrl
                            val mine = ++page
                            if (isSignInUrl(pageUrl)) {
                                // Signed in, Microsoft's page just passes us on. Only if it's still
                                // sitting there a few seconds later are you really signed out.
                                main.postDelayed({
                                    if (mine == page && cont.isActive) cont.resume(Result.SignIn)
                                }, SIGN_IN_SETTLE_MS)
                                return
                            }
                            CnWeb.readBoard(view, stillWanted = { mine == page && cont.isActive }, done = { text ->
                                if (text != null) {
                                    cont.resume(Result.Board(text, view.url))
                                    return@readBoard
                                }
                                // Board embedded from another site: open it directly.
                                view.evaluateJavascript(CnWeb.CREW_FRAMES_JS) { frames ->
                                    CnWeb.crewFrames(frames).firstOrNull { followedFrames.add(it) }?.let(view::loadUrl)
                                }
                            })
                        }
                    }
                    web.loadUrl(url)
                }
            }
            result ?: if (isSignInUrl(lastUrl)) Result.SignIn else Result.Failed(lastError ?: "crew page didn't show a board")
        } finally {
            web.stopLoading()
            web.destroy()
        }
    }
}
