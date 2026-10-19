package app.railwaytime

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.view.View
import android.webkit.WebChromeClient
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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.railwaytime.core.AppSettings
import app.railwaytime.core.BoardParser
import app.railwaytime.core.PageText
import app.railwaytime.core.RailClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** On duty = nothing talks to CN; the widget only shows the clocks. */
object Duty {
    fun isWorking(c: app.railwaytime.core.CatsInfo) = c.status?.contains("WORK", ignoreCase = true) == true

    /**
     * When CATS changes to WORKING, switch to on duty by itself (CN: no use while on duty).
     * Only on the change, so switching back to off duty yourself sticks. Switching off is up to
     * you: on duty nothing is fetched, so the app can't see you've finished.
     */
    fun checkWorking(context: Context) {
        val store = Store(context)
        val working = store.snapshot()?.cats?.let(::isWorking) == true
        if (working && !store.lastWorking && !store.onDuty) {
            store.log("CATS says WORKING — switched to on duty")
            set(context, true)
        }
        store.lastWorking = working
    }

    fun set(context: Context, onDuty: Boolean) {
        Store(context).onDuty = onDuty
        if (onDuty) {
            RefreshScheduler.cancel(context)
        } else {
            RefreshScheduler.refreshNow(context)
            RefreshScheduler.scheduleNext(context)
        }
        Widget.updateAll(context)
    }

    fun toggle(context: Context) = set(context, !Store(context).onDuty)
}

/**
 * Every [AppSettings.refreshMin] minutes while off duty. An exact alarm (the kind clock apps use)
 * wakes the phone on time even when it's asleep and starts the refresh as expedited work, which
 * also runs while the phone sleeps. A 15-minute WorkManager check re-arms the alarm if it's ever
 * lost and catches up when a refresh is overdue.
 */
object RefreshScheduler {
    const val ACTION_REFRESH = "app.railwaytime.REFRESH"
    const val ACTION_RENDER = "app.railwaytime.RENDER"
    const val KEY_MANUAL = "manual"
    private const val WORK = "refresh"
    private const val WATCHDOG = "refresh-watchdog"

    private fun pending(context: Context, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(
        context, action.hashCode(),
        Intent(context, AlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or flags,
    )

    private fun everyMs(context: Context) = Store(context).settings.refreshMin.coerceAtLeast(5) * 60_000L

    /** Arms the next refresh one interval from now. Called when a refresh alarm fires. */
    fun scheduleNext(context: Context) {
        val store = Store(context)
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pending(context, ACTION_REFRESH))
        if (!store.onDuty) {
            val every = everyMs(context)
            val at = SystemClock.elapsedRealtime() + every
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context, ACTION_REFRESH))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context, ACTION_REFRESH))
            }
            store.nextRefreshAt = System.currentTimeMillis() + every
            startWatchdog(context)
        }
        scheduleClockSwitch(context)
    }

    /**
     * Makes sure a refresh is coming without pushing it back: opening the app or redrawing the
     * widget calls this, and it only arms an alarm when none is waiting.
     */
    fun ensure(context: Context) {
        val store = Store(context)
        if (store.onDuty) return
        val armed = pending(context, ACTION_REFRESH, PendingIntent.FLAG_NO_CREATE) != null &&
            store.nextRefreshAt > System.currentTimeMillis()
        if (!armed) scheduleNext(context)
        if (System.currentTimeMillis() - store.lastAttemptAt > everyMs(context) + 60_000) refreshNow(context)
        scheduleClockSwitch(context)
    }

    /** Redraw the widget clock when railtime changes offset (Nov 1), even on duty. */
    private fun scheduleClockSwitch(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        RailClock.nextSwitch(Store(context).settings, Instant.now())?.let {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.toEpochMilli() + 1_000, pending(context, ACTION_RENDER))
        }
    }

    private fun startWatchdog(context: Context) {
        val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WATCHDOG, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, ACTION_REFRESH))
        Store(context).nextRefreshAt = 0
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        WorkManager.getInstance(context).cancelUniqueWork(WATCHDOG)
    }

    /** [manual]: you asked for it (widget or app), so it also runs while signed out. */
    fun refreshNow(context: Context, manual: Boolean = false) {
        if (Store(context).onDuty) return
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            // Expedited: runs right away, even while the phone is asleep.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(KEY_MANUAL to manual))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}

/** Every 15 minutes: re-arms the 10-minute alarm if it was lost, and refreshes if one is overdue. */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RefreshScheduler.ensure(applicationContext)
        return Result.success()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            RefreshScheduler.ACTION_REFRESH -> {
                RefreshService.start(context)
                RefreshScheduler.scheduleNext(context)
            }
            RefreshScheduler.ACTION_RENDER -> RefreshScheduler.ensure(context)
            // Boot / app update / exact-alarm permission changed: alarms are gone, set them again.
            else -> {
                NapAlarm.restore(context)
                RefreshScheduler.refreshNow(context)
                RefreshScheduler.scheduleNext(context)
            }
        }
        Widget.updateAll(context)
    }
}

/** One refresh: load the board and save it, or record why not. Shared by the service and the worker. */
object Refresher {
    private const val SIGNED_OUT_RETRY_MS = 30 * 60_000L

    suspend fun run(context: Context, manual: Boolean, via: String) {
        val store = Store(context)
        if (store.onDuty) return
        // Signed out: only look again every half hour (or when you tap refresh) until you sign in.
        val signedOutRecently = store.status == Store.Status.SIGN_IN &&
            System.currentTimeMillis() - store.statusAt < SIGNED_OUT_RETRY_MS
        if (signedOutRecently && !manual) {
            store.log("refresh skipped ($via): signed out, retrying every 30 min")
            return
        }
        store.lastAttemptAt = System.currentTimeMillis()
        store.refreshing = true
        Widget.updateAll(context)
        store.log("refresh ($via): ${CnWeb.safeUrl(store.boardUrl ?: store.settings.sharepointUrl)}")
        try {
            when (val r = IvrmFetcher.fetch(context, store.boardUrl, store.settings)) {
                is IvrmFetcher.Result.Board -> {
                    store.saveBoard(r.text, r.url)
                    Duty.checkWorking(context)
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
            Widget.updateAll(context)
        }
    }

    const val CHANNEL = "refresh"
    const val NOTIFICATION_ID = 10

    /** The quiet notification Android requires while a refresh runs in the foreground. */
    fun notification(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Board refresh", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown for a few seconds while the crew board refreshes"
                setShowBadge(false)
            },
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_refresh)
            .setContentTitle("Checking the crew board…")
            .setOngoing(true)
            .build()
    }
}

/**
 * The timed refresh runs as a short foreground service: with the phone asleep, Android cuts
 * background apps off the network and stalls hidden web pages, but a foreground service gets
 * both. The exact alarm is what allows it to start from the background. It stops itself when done.
 */
class RefreshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Refresher.notification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Refresher.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Refresher.NOTIFICATION_ID, n)
        }
        if (job?.isActive != true) {
            job = scope.launch {
                try {
                    Refresher.run(this@RefreshService, manual = false, via = "timer")
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            if (Store(context).onDuty) return
            runCatching { context.startForegroundService(Intent(context, RefreshService::class.java)) }
                .onFailure {
                    // Not allowed right now (e.g. no exact-alarm permission): queue it instead.
                    Store(context).log("refresh: foreground start refused (${it.javaClass.simpleName}); queued instead")
                    RefreshScheduler.refreshNow(context)
                }
        }
    }
}

/** Taps on ⟳ / Refresh now, the watchdog and boot catch-up run here. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(RefreshScheduler.KEY_MANUAL, false)
        Refresher.run(applicationContext, manual, via = if (manual) "tapped" else "catch-up")
        return Result.success()
    }

    /** Only used before Android 12, where expedited work runs as a short foreground job. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Refresher.NOTIFICATION_ID, Refresher.notification(applicationContext))
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

    /**
     * 1. The saved IVRM page (fast, but IVRM's visit can expire).
     * 2. Otherwise the way you'd do it: SharePoint → Mobile Crew → read the board.
     */
    suspend fun fetch(context: Context, boardUrl: String?, settings: AppSettings): Result {
        val store = Store(context)
        if (boardUrl != null) {
            quickFetch(context, boardUrl)?.let { return it }
            val direct = browse(context, boardUrl, followMobileCrew = false, timeoutMs = 30_000)
            if (direct !is Result.Failed) return direct
            store.log("refresh: saved IVRM page didn't show the board (${direct.reason}); going via SharePoint")
        }
        return browse(context, settings.sharepointUrl, followMobileCrew = true, timeoutMs = 75_000)
    }

    /** Loads [startUrl] in hidden WebViews (new windows included) until a page shows the board. */
    private suspend fun browse(context: Context, startUrl: String, followMobileCrew: Boolean, timeoutMs: Long): Result =
        withContext(Dispatchers.Main) {
            val main = Handler(Looper.getMainLooper())
            val views = mutableListOf<WebView>()
            val loads = HashMap<WebView, Int>()
            val followedFrames = mutableSetOf<String>()
            var followedCrew = false
            var lastUrl: String? = null
            var lastError: String? = null
            var lastText = ""
            try {
                val result = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        lateinit var newView: () -> WebView

                        // Look for SharePoint's Mobile Crew link for up to 20 s while the page draws.
                        fun openMobileCrew(view: WebView, triesLeft: Int = 40) {
                            if (followedCrew || !cont.isActive || triesLeft == 0) return
                            view.evaluateJavascript(CnWeb.MOBILE_CREW_JS) { json ->
                                val found = runCatching { JSONObject(decodeJs(json)) }.getOrNull()
                                when {
                                    found?.has("href") == true -> {
                                        followedCrew = true
                                        Store(context).log("refresh: opening Mobile Crew")
                                        view.loadUrl(found.getString("href"))
                                    }
                                    found?.has("clicked") == true -> {
                                        followedCrew = true
                                        Store(context).log("refresh: clicked Mobile Crew")
                                    }
                                    else -> main.postDelayed({ openMobileCrew(view, triesLeft - 1) }, 500)
                                }
                            }
                        }

                        val chrome = object : WebChromeClient() {
                            // Mobile Crew opens a new window: give it its own hidden page.
                            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                (resultMsg.obj as WebView.WebViewTransport).webView = newView()
                                resultMsg.sendToTarget()
                                return true
                            }
                        }
                        val client = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                                lastUrl = pageUrl
                                loads[view] = (loads[view] ?: 0) + 1
                            }

                            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                                handler.cancel()
                                if (cont.isActive) cont.resume(Result.SignIn)
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) lastError = "${request.url.host}: ${error.description}"
                            }

                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                if (!CnTls.handle(context, error, handler) { Store(context).log("refresh: $it") }) {
                                    lastError = "certificate problem on ${CnWeb.safeUrl(error.url)} (${error.primaryError})"
                                }
                            }

                            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                                CnWeb.answerClientCert(context, request, activity = null)
                            }

                            override fun onPageFinished(view: WebView, pageUrl: String?) {
                                lastUrl = pageUrl
                                val mine = (loads[view] ?: 0) + 1
                                loads[view] = mine
                                if (isSignInUrl(pageUrl)) {
                                    // Signed in, Microsoft's page just passes us on. Only if it's still
                                    // sitting there a few seconds later are you really signed out.
                                    main.postDelayed({
                                        if (loads[view] == mine && cont.isActive) cont.resume(Result.SignIn)
                                    }, SIGN_IN_SETTLE_MS)
                                    return
                                }
                                val onSharePoint = followMobileCrew && !followedCrew
                                CnWeb.readBoard(
                                    view,
                                    stillWanted = { loads[view] == mine && cont.isActive },
                                    done = { text ->
                                        if (text != null) {
                                            cont.resume(Result.Board(text, view.url))
                                            return@readBoard
                                        }
                                        // Board embedded from another site: open it directly.
                                        view.evaluateJavascript(CnWeb.CREW_FRAMES_JS) { frames ->
                                            CnWeb.crewFrames(frames).firstOrNull { followedFrames.add(it) }?.let(view::loadUrl)
                                        }
                                        if (onSharePoint) openMobileCrew(view)
                                    },
                                    forMs = if (onSharePoint) 1_500 else 8_000,
                                    onText = { lastText = it },
                                )
                            }
                        }
                        newView = {
                            WebView(context.applicationContext).also { w ->
                                CnWeb.configure(w)
                                // The widget doesn't need pictures.
                                w.settings.loadsImagesAutomatically = false
                                w.settings.blockNetworkImage = true
                                w.settings.javaScriptCanOpenWindowsAutomatically = true
                                w.settings.setSupportMultipleWindows(true)
                                // A phone-sized page: SharePoint only draws what's "on screen", and a
                                // hidden WebView is otherwise 0×0.
                                w.measure(
                                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
                                )
                                w.layout(0, 0, 1080, 2340)
                                w.webChromeClient = chrome
                                w.webViewClient = client
                                views += w
                            }
                        }
                        newView().loadUrl(startUrl)
                    }
                }
                result ?: when {
                    isSignInUrl(lastUrl) -> Result.SignIn
                    else -> Result.Failed(
                        lastError ?: buildString {
                            append(if (followMobileCrew && !followedCrew) "Mobile Crew link not found" else "no board")
                            append(" at ${CnWeb.safeUrl(lastUrl)}")
                            val snippet = lastText.replace(Regex("""\s+"""), " ").trim().take(120)
                            if (snippet.isNotEmpty()) append(" — page said: \"$snippet\"")
                        },
                    )
                }
            } finally {
                views.forEach {
                    it.stopLoading()
                    it.destroy()
                }
            }
        }
}
