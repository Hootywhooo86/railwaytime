package app.railwaytime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.floor
import kotlin.math.min

/**
 * A pixel-art station clock: big blocky digits inside a frame, with a loop of track around it
 * and a little train running laps. While an alarm rings ([ringing]) the train speeds up and the
 * digits flash.
 */
class PixelClockView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    /** What the clock shows, e.g. "21:42". Read every frame. */
    var timeText: () -> String = { "00:00" }
    var ringing = false

    private val paint = Paint()

    private val bg = 0xFF0B0B16.toInt()
    private val panel = 0xFF141428.toInt()
    private val orange = 0xFFFF9F1C.toInt()
    private val pink = 0xFFFF5D8F.toInt()
    private val teal = 0xFF2DD4BF.toInt()
    private val rail = 0xFF5A5A7A.toInt()
    private val tie = 0xFF6B4A2A.toInt()
    private val light = 0xFFFFE066.toInt()
    private val ink = 0xFFF4F1FF.toInt()
    private val smoke = 0x99B8B5D0.toInt()

    /** Track loop, one cell inset from the 2-cell border, clockwise. */
    private var loop: List<Pair<Int, Int>> = emptyList()
    private var loopCols = 0
    private var loopRows = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            w * ROWS / COLS
        }
        setMeasuredDimension(w, h)
    }

    private fun buildLoop(cols: Int, rows: Int) {
        if (cols == loopCols && rows == loopRows) return
        val l = 2
        val t = 2
        val r = cols - 3
        val b = rows - 3
        val cells = mutableListOf<Pair<Int, Int>>()
        for (x in l until r) cells += x to t
        for (y in t until b) cells += r to y
        for (x in r downTo l + 1) cells += x to b
        for (y in b downTo t + 1) cells += l to y
        loop = cells
        loopCols = cols
        loopRows = rows
    }

    override fun onDraw(canvas: Canvas) {
        val p = floor(min(width.toFloat() / COLS, height.toFloat() / ROWS))
        if (p <= 0f) return
        val ox = (width - p * COLS) / 2
        val oy = (height - p * ROWS) / 2
        buildLoop(COLS, ROWS)
        val now = SystemClock.uptimeMillis()

        fun cell(x: Int, y: Int, color: Int, grow: Float = 1f) {
            paint.color = color
            val g = (grow - 1f) * p / 2
            canvas.drawRect(ox + x * p - g, oy + y * p - g, ox + (x + 1) * p + g, oy + (y + 1) * p + g, paint)
        }

        canvas.drawColor(bg)

        // Track: ties under the centre line, rails either side.
        loop.forEachIndexed { i, (x, y) -> if (i % 2 == 0) cell(x, y, tie) }
        for (x in 1 until COLS - 1) {
            cell(x, 1, rail); cell(x, ROWS - 2, rail)
            if (x in 3 until COLS - 3) { cell(x, 3, rail); cell(x, ROWS - 4, rail) }
        }
        for (y in 1 until ROWS - 1) {
            cell(1, y, rail); cell(COLS - 2, y, rail)
            if (y in 3 until ROWS - 3) { cell(3, y, rail); cell(COLS - 4, y, rail) }
        }

        // Station clock panel with an orange frame.
        val fl = 5
        val ft = 5
        val fr = COLS - 6
        val fb = ROWS - 6
        for (y in ft..fb) for (x in fl..fr) {
            val edge = x == fl || x == fr || y == ft || y == fb
            cell(x, y, if (edge) orange else panel)
        }

        // Digits.
        val text = timeText()
        val flash = ringing && (now / 400) % 2 == 0L
        val digitColor = if (flash) pink else orange
        val blinkColon = (now / 500) % 2 == 0L
        val glyphs = text.map { PixelFont.glyph(it) }
        val textW = glyphs.sumOf { it.width } + (glyphs.size - 1)
        var cx = (COLS - textW) / 2
        val cy = (ROWS - PixelFont.HEIGHT) / 2
        glyphs.forEachIndexed { gi, g ->
            val isColon = text[gi] == ':'
            for (row in 0 until PixelFont.HEIGHT) for (col in 0 until g.width) {
                if (g.on(col, row) && !(isColon && !blinkColon)) cell(cx + col, cy + row, digitColor)
            }
            cx += g.width + 1
        }

        // The train: headlight, engine, cars, caboose — running clockwise round the loop.
        val speed = if (ringing) 18f else 5f // cells per second
        val head = (now / 1000f * speed) % loop.size
        val consist = listOf(light, orange, orange, orange, 0, teal, teal, 0, pink, pink, 0, teal, teal, 0, ink, pink)
        consist.forEachIndexed { k, color ->
            if (color == 0) return@forEachIndexed
            val idx = Math.floorMod((head - k).toInt(), loop.size)
            val (x, y) = loop[idx]
            cell(x, y, color, grow = 1.7f)
        }
        // A puff of smoke drifting off the engine.
        val puffAge = (now % 900) / 900f
        val (sx, sy) = loop[Math.floorMod((head - 2 - puffAge * 3).toInt(), loop.size)]
        paint.alpha = ((1f - puffAge) * 255).toInt()
        cell(sx, sy - 1 - (puffAge * 2).toInt(), smoke)
        paint.alpha = 255

        postInvalidateOnAnimation()
    }

    private companion object {
        const val COLS = 40
        const val ROWS = 22
    }
}

/** A 5×7 pixel font for the clock: digits, colon, dash and space. */
object PixelFont {
    const val HEIGHT = 7

    class Glyph(private val rows: List<String>) {
        val width = rows.first().length
        fun on(col: Int, row: Int) = rows[row][col] == '#'
    }

    private val GLYPHS = mapOf(
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ':' to listOf(".", ".", "#", ".", "#", ".", "."),
        '-' to listOf(".....", ".....", ".....", "#####", ".....", ".....", "....."),
        ' ' to listOf("..", "..", "..", "..", "..", "..", ".."),
    )

    fun glyph(c: Char): Glyph = Glyph(GLYPHS[c] ?: GLYPHS.getValue(' '))
}
