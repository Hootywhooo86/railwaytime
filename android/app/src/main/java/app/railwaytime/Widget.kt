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

/** The short lines shown on the widget (and at the top of the app). */
data class Summary(
    val board: String,
    val rest: String,
    val nextBack: String,
    val train: String,
    val updated: String,
    val needsAttention: Boolean,
    val prediction: Prediction?,
    val now: LocalDateTime,
) {
    companion object {
        private const val STALE_MS = 60 * 60_000L

        fun build(context: Context): Summary {
            val store = Store(context)
            val settings = store.settings
            val instant = Instant.now()
            val now = RailClock.boardNow(settings, instant)
            val snapshot = store.snapshot()
            val p = snapshot?.let { Predictor.predict(it, now, settings.callLeadMin.toLong()) }

            val updatedAt = store.fetchedAt.takeIf { it > 0 }?.let {
                DayTime.clock(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), settings.homeZone))
            }
            val stale = store.fetchedAt > 0 && System.currentTimeMillis() - store.fetchedAt > STALE_MS
            val updated = when (store.status) {
                Store.Status.SIGN_IN -> "Signed out of CN · tap to sign in"
                Store.Status.ERROR -> "Couldn't refresh · last ${updatedAt ?: "never"}"
                else -> if (updatedAt == null) "Tap to sign in to IVRM" else "Updated $updatedAt" + if (stale) " · old" else ""
            }
            val attention = store.status == Store.Status.SIGN_IN || store.status == Store.Status.ERROR || updatedAt == null || stale

            if (p == null) return Summary("No board yet", "", "", "", updated, attention, null, now)

            val board = buildString {
                append("POS ${p.myPosition?.let { "%02d".format(it) } ?: "?"} · ${p.ahead.size} ahead")
                append(" (${p.availableAhead} A0 · ${p.spareAhead} spare")
                if (p.heldAhead > 0) append(" · ${p.heldAhead} HT")
                append(")")
            }
            val rest = p.myRestUntil?.let {
                if (it.isAfter(now)) "Rested ${DayTime.format(it)} (${DayTime.until(now, it)})" else "Rested ✓ since ${DayTime.format(it)}"
            } ?: "Rested ✓"
            val nextBack = p.nextBack?.let { "Next off rest: ${it.init} ${DayTime.format(it.returnAt!!)} (${DayTime.until(now, it.returnAt)})" }
                ?: "Next off rest: nobody ahead on rest"
            val train = p.myTrain?.let {
                "Likely ${it.train.label} · ${DayTime.format(it.train.planned)} · order ~${DayTime.clock(it.orderAt)}"
            } ?: "Past the lineup · ${p.trainsBeyondLineup} more train(s) to you"
            return Summary(board, rest, nextBack, train, updated, attention, p, now)
        }
    }
}

class RailWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Widget.updateAll(context)
        RefreshScheduler.schedule(context)
    }

    override fun onEnabled(context: Context) {
        RefreshScheduler.refreshNow(context)
        RefreshScheduler.schedule(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Widget.ACTION_TOGGLE_DUTY) Duty.toggle(context) else super.onReceive(context, intent)
    }
}

object Widget {
    const val ACTION_TOGGLE_DUTY = "app.railwaytime.TOGGLE_DUTY"
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
        v.setTextViewText(R.id.local_label, "LOCAL " + settings.homeZone.shortName())

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
        v.setTextViewText(R.id.line_board, s.board)
        v.setTextViewText(R.id.line_rest, s.rest)
        v.setTextViewText(R.id.line_next, s.nextBack)
        v.setTextViewText(R.id.line_train, s.train)
        v.setTextViewText(R.id.line_updated, s.updated)
        v.setTextColor(R.id.line_updated, if (s.needsAttention) PINK else MUTED)
        v.setTextColor(R.id.line_train, if (s.prediction?.myTrain != null) ORANGE else Color.WHITE)
        return v
    }

    private fun ZoneId.shortName(): String =
        java.time.format.DateTimeFormatter.ofPattern("zzz").format(java.time.ZonedDateTime.now(this))
}
