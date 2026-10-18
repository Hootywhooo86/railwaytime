package app.railwaytime.core

/** One line of text found in a screenshot, with its box in pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = bottom - top
}

/**
 * Text recognition returns the IVRM page column by column ("Name", "Terminal"… then the values).
 * This puts it back into screen rows, left to right, one row per line ("Name HOUTSTRA DJ DAN"),
 * which [BoardParser] reads like a plain-text page.
 */
object OcrRows {
    fun toText(lines: List<OcrLine>): String {
        val sorted = lines.filter { it.text.isNotBlank() && it.height > 0 }.sortedBy { it.centerY }
        if (sorted.isEmpty()) return ""
        val typical = sorted.map { it.height }.sorted()[sorted.size / 2]
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            val rowCenter = row?.let { r -> r.sumOf { it.centerY } / r.size }
            if (row != null && rowCenter != null && kotlin.math.abs(line.centerY - rowCenter) <= typical / 2) {
                row += line
            } else {
                rows += mutableListOf(line)
            }
        }
        return rows.joinToString("\n") { r -> r.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }
}
