package app.railwaytime

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import app.railwaytime.core.DayTime
import app.railwaytime.core.Prediction
import app.railwaytime.core.Predictor
import app.railwaytime.core.RailClock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What the widget and the top of the app show, already formatted. */
data class Summary(
    val hasBoard: Boolean,
    val position: String = "—",
    val ahead: String = "—",
    val aheadSub: String = "",
    val beforeYou: String = "—",
    val rest: String = "—",
    val restSub: String = "",
    val next: String = "—",
    val nextSub: String = "",
    val train: String = "No board yet",
    val trainSub: String = "Tap to sign in to IVRM",
    val updated: String,
    val needsAttention: Boolean,
    val prediction: Prediction? = null,
) {
    companion object {
        private const val STALE_MS = 60 * 60_000L

        fun build(context: Context): Summary {
            val store = Store(context)
            val settings = store.settings
            val instant = Instant.now()
            val now = RailClock.boardNow(settings, instant)
            val p = store.snapshot()?.let { Predictor.predict(it, now, settings.callLeadMin.toLong()) }

            val updatedAt = store.fetchedAt.takeIf { it > 0 }?.let {
                DayTime.clock(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), settings.homeZone))
            }
            val stale = store.fetchedAt > 0 && System.currentTimeMillis() - store.fetchedAt > STALE_MS
            val updated = if (store.refreshing) "Refreshing…" else when (store.status) {
                Store.Status.SIGN_IN -> "Signed out of CN · tap to sign in"
                Store.Status.ERROR -> "Couldn't refresh · last ${updatedAt ?: "never"}"
                else -> if (updatedAt == null) "Not loaded yet" else "Updated $updatedAt" + if (stale) " · old" else ""
            } + if (store.refreshing) "" else {
                val next = store.nextRefreshAt.takeIf { it > System.currentTimeMillis() }?.let {
                    " · next " + DayTime.clock(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), settings.homeZone))
                }.orEmpty()
                "$next  ⟳"
            }
            val attention = store.status == Store.Status.SIGN_IN || store.status == Store.Status.ERROR || updatedAt == null || stale
            if (p == null) return Summary(hasBoard = false, updated = updated, needsAttention = attention)

            fun whenSub(t: LocalDateTime) = "${DayTime.dayWord(now, t)} · ${DayTime.until(now, t)}"
            val rest = p.myRestUntil?.takeIf { it.isAfter(now) }
            val next = p.nextBack
            val mine = p.myTrain
            return Summary(
                hasBoard = true,
                position = p.myPosition?.let { "%02d".format(it) } ?: "?",
                ahead = p.ahead.size.toString(),
                aheadSub = listOfNotNull(
                    "${p.availableAhead} A0",
                    p.heldAhead.takeIf { it > 0 }?.let { "$it HT" },
                    p.spareAhead.takeIf { it > 0 }?.let { "$it spare" },
                ).joinToString(" · "),
                beforeYou = if (mine != null) p.goBeforeYou.toString() else "${p.goBeforeYou}+",
                rest = rest?.let { DayTime.clock(it) } ?: "Rested",
                restSub = rest?.let { whenSub(it) } ?: "ready to go",
                next = next?.let { "${it.init} · ${DayTime.clock(it.returnAt!!)}" } ?: "—",
                nextSub = next?.let { whenSub(it.returnAt!!) } ?: "nobody ahead resting",
                train = mine?.let { "${it.train.symbol} · ${DayTime.clock(it.train.planned)}" } ?: "Past the lineup",
                trainSub = mine?.let {
                    "${DayTime.dayWord(now, it.train.planned)} · ordered ~${DayTime.clock(it.orderAt)} · ${DayTime.until(now, it.orderAt)}"
                } ?: "${p.trainsBeyondLineup} more train(s) needed to reach you",
                updated = updated,
                needsAttention = attention,
                prediction = p,
            )
        }
    }
}

class RailWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widget.updateAll(context)
        RefreshScheduler.ensure(context)
    }

    override fun onEnabled(context: Context) {
        RefreshScheduler.refreshNow(context)
        RefreshScheduler.scheduleNext(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Widget.ACTION_TOGGLE_DUTY -> Duty.toggle(context)
            Widget.ACTION_REFRESH -> {
                Store(context).refreshing = true
                Widget.updateAll(context)
                RefreshScheduler.refreshNow(context, manual = true)
            }
            else -> super.onReceive(context, intent)
        }
    }
}

object Widget {
    const val ACTION_TOGGLE_DUTY = "app.railwaytime.TOGGLE_DUTY"
    const val ACTION_REFRESH = "app.railwaytime.REFRESH_NOW"
    private const val ORANGE = 0xFFFF9F1C.toInt()
    private const val PINK = 0xFFFF5D8F.toInt()
    private const val MUTED = 0xFF9A97B8.toInt()

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, RailWidget::class.java))
        if (ids.isEmpty()) return
        val views = render(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    private fun render(context: Context): RemoteViews {
        val store = Store(context)
        val settings = store.settings
        val v = RemoteViews(context.packageName, R.layout.widget)

        v.setString(R.id.rail_clock, "setTimeZone", RailClock.railZoneId(settings, Instant.now()))
        v.setString(R.id.local_clock, "setTimeZone", settings.homeZone.id)
        v.setTextViewText(R.id.local_label, "LOCAL · " + settings.homeZone.shortName())

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_root, open)
        val toggle = PendingIntent.getBroadcast(
            context, 1, Intent(context, RailWidget::class.java).setAction(ACTION_TOGGLE_DUTY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.duty_button, toggle)
        // Tap "Updated … ⟳" to refresh now.
        val refresh = PendingIntent.getBroadcast(
            context, 2, Intent(context, RailWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.line_updated, refresh)

        if (store.onDuty) {
            v.setTextViewText(R.id.duty_button, "ON DUTY")
            v.setInt(R.id.duty_button, "setBackgroundResource", R.drawable.duty_on)
            v.setViewVisibility(R.id.board_block, View.GONE)
            v.setViewVisibility(R.id.on_duty_note, View.VISIBLE)
            return v
        }

        val s = Summary.build(context)
        v.setTextViewText(R.id.duty_button, "OFF DUTY")
        v.setInt(R.id.duty_button, "setBackgroundResource", R.drawable.duty_off)
        v.setViewVisibility(R.id.board_block, View.VISIBLE)
        v.setViewVisibility(R.id.on_duty_note, View.GONE)
        v.setTextViewText(R.id.v_position, s.position)
        v.setTextViewText(R.id.v_ahead, s.ahead)
        v.setTextViewText(R.id.v_ahead_sub, s.aheadSub)
        v.setTextViewText(R.id.v_before, s.beforeYou)
        v.setTextViewText(R.id.v_rest, s.rest)
        v.setTextViewText(R.id.v_rest_sub, s.restSub)
        v.setTextViewText(R.id.v_next, s.next)
        v.setTextViewText(R.id.v_next_sub, s.nextSub)
        v.setTextViewText(R.id.v_train, s.train)
        v.setTextViewText(R.id.v_train_sub, s.trainSub)
        v.setTextColor(R.id.v_train, if (s.prediction?.myTrain != null) ORANGE else Color.WHITE)
        v.setTextViewText(R.id.line_updated, s.updated)
        v.setTextColor(R.id.line_updated, if (s.needsAttention) PINK else MUTED)
        return v
    }

    private fun ZoneId.shortName(): String =
        java.time.format.DateTimeFormatter.ofPattern("zzz").format(java.time.ZonedDateTime.now(this))
}
