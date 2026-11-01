package app.railwaytime

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.railwaytime.core.Palette
import app.railwaytime.core.Themes

/**
 * Applies your colours. Layouts are drawn in the 128bit default palette; this swaps each of
 * those colours for its counterpart in your palette (remembering the original, so changing
 * theme again works). Pixel buttons take your accent; panels tagged "accent_stroke" get an
 * accent outline.
 */
object Themer {
    fun current(context: Context): Palette {
        val s = Store(context)
        return Themes.resolve(s.themeId, s.themeAccent, s.themeAccent2)
    }

    fun apply(activity: Activity) {
        val p = current(activity)
        activity.window.statusBarColor = p.bg
        activity.window.navigationBarColor = p.bg
        activity.window.decorView.setBackgroundColor(p.bg)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        recolor(root, map(p), p)
    }

    private fun map(p: Palette): Map<Int, Int> {
        val d = Themes.DEFAULT
        return mapOf(
            d.bg to p.bg, d.panel to p.panel, d.ink to p.ink, d.muted to p.muted,
            d.accent to p.accent, d.accent2 to p.accent2, d.alert to p.alert,
        )
    }

    private fun recolor(v: View, m: Map<Int, Int>, p: Palette) {
        if (v is TextView) {
            val orig = (v.getTag(R.id.theme_orig_text) as? Int) ?: v.currentTextColor.also { v.setTag(R.id.theme_orig_text, it) }
            m[orig]?.let(v::setTextColor)
        }
        when (val bg = v.background) {
            is ColorDrawable -> {
                val orig = (v.getTag(R.id.theme_orig_bg) as? Int) ?: bg.color.also { v.setTag(R.id.theme_orig_bg, it) }
                m[orig]?.let(v::setBackgroundColor)
            }
            is GradientDrawable -> {
                val orig = (v.getTag(R.id.theme_orig_bg) as? Int)
                    ?: (bg.color?.defaultColor ?: 0).also { v.setTag(R.id.theme_orig_bg, it) }
                val g = bg.mutate() as GradientDrawable
                m[orig]?.let(g::setColor)
                if (v.tag == "accent_stroke") g.setStroke((3 * v.resources.displayMetrics.density).toInt(), p.accent)
            }
            is StateListDrawable, is LayerDrawable -> when (v.tag) {
                // Pixel buttons: one solid block of your accent.
                "accent" -> v.backgroundTintList = ColorStateList.valueOf(p.accent)
                "accent2" -> v.backgroundTintList = ColorStateList.valueOf(p.accent2)
            }
        }
        if (v is PixelClockView) v.palette = p
        if (v is ViewGroup) for (i in 0 until v.childCount) recolor(v.getChildAt(i), m, p)
    }

    /** The widget's background card: your base colour with an accent outline, at the widget's size. */
    fun widgetBackground(p: Palette, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val radius = 22 * density
        val stroke = 1.5f * density
        val r = RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = (p.bg and 0x00FFFFFF) or 0xF2000000.toInt()
        canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = (p.accent and 0x00FFFFFF) or 0x99000000.toInt()
        canvas.drawRoundRect(r, radius, radius, paint)
        return bmp
    }

    /** [c] at [alpha] (0–255). */
    fun withAlpha(c: Int, alpha: Int) = (c and 0x00FFFFFF) or (alpha shl 24)
}
