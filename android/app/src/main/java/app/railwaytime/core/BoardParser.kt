package app.railwaytime.core

import java.time.LocalDate
import java.time.Month

/** Reads the IVRM "Mobile Crew Inquiry" page text (see [PageText]). */
object BoardParser {
    private val LABELS = listOf(
        "Name", "Terminal", "CATS Status", "Rest until",
        "Current Assignment", "Assignment Location", "Board Position",
    )
    private val PAGE_DATE = Regex(
        """(January|February|March|April|May|June|July|August|September|October|November|December)\s+(\d{1,2}),\s*(\d{4})""",
        RegexOption.IGNORE_CASE,
    )
    private val POS = Regex("""^\d{1,3}$""")
    private val STATUS = Regex("""^(\*\*)?\s*([A-Z][A-Z0-9]{1,3})$""")
    private val ROW_LINE = Regex(
        """^(\d{1,3})\s+(.+?)\s+(?:(\d{1,2}\s+\d{1,2}:?\d{2})\s+)?(\*\*\s*)?([A-Z][A-Z0-9]{1,3})(?:\s+(\d{1,2}\s+\d{1,2}:?\d{2}))?$""",
    )
    private val TRAIN = Regex(
        """^([A-Z0-9]*\d[A-Z0-9]*)\s+(\d{1,2})\s+([A-Za-z]+)\s+(\d{1,2})\s+(\d{1,2}):?(\d{2})$""",
    )

    /** True when the text looks like a signed-in crew inquiry page. */
    fun looksLikeBoard(text: String): Boolean = parse(text, LocalDate.now()).isUsable

    /** The date in the page header, e.g. "Sunday, October 18, 2026 5:44 PM ET". */
    fun pageDate(text: String): LocalDate? = PAGE_DATE.find(text)?.let {
        val (month, day, year) = it.destructured
        runCatching { LocalDate.of(year.toInt(), Month.valueOf(month.uppercase()), day.toInt()) }.getOrNull()
    }

    fun parse(text: String, fallbackRef: LocalDate): BoardSnapshot {
        val ref = pageDate(text) ?: fallbackRef
        val tokens = PageText.tokens(text)

        val boardStart = indexOf(tokens, "Additional Board Information")
        val poolStart = indexOf(tokens, "Pool Lineup")
        val poolEnd = listOf("Train Lineup", "Terminal Lineup")
            .mapNotNull { indexOf(tokens, it, from = (poolStart ?: 0) + 1) }
            .minOrNull() ?: tokens.size

        val catsTokens = tokens.subList(0, boardStart ?: poolStart ?: tokens.size)
        val boardTokens = tokens.subList(boardStart ?: 0, poolStart ?: tokens.size)
        val poolTokens = tokens.subList(poolStart ?: 0, poolEnd)

        return BoardSnapshot(
            cats = parseCats(catsTokens, ref),
            board = if (boardStart != null || poolStart != null) parseBoard(boardTokens, ref) else emptyList(),
            trains = parseTrains(poolTokens, ref),
        )
    }

    /** First token that ends with [heading], e.g. "My Pool Lineup:" matches "Pool Lineup". */
    private fun indexOf(tokens: List<String>, heading: String, from: Int = 0): Int? =
        (from until tokens.size).firstOrNull {
            tokens[it].trimEnd(':').endsWith(heading, ignoreCase = true) && tokens[it].length <= heading.length + 4
        }

    private fun parseCats(tokens: List<String>, ref: LocalDate): CatsInfo {
        val values = mutableMapOf<String, String>()
        fun isLabel(t: String) = LABELS.any { it.equals(t.trimEnd(':'), ignoreCase = true) }
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (isLabel(t)) {
                // Labels may sit beside their values, or all labels may come first then all values.
                var j = i
                while (j < tokens.size && isLabel(tokens[j])) j++
                val labels = tokens.subList(i, j)
                labels.forEachIndexed { k, label ->
                    tokens.getOrNull(j + k)?.let { values.putIfAbsent(canonical(label), it) }
                }
                i = j + labels.size
                continue
            }
            // Single line form: "Board Position 08".
            LABELS.firstOrNull { t.startsWith("$it ", ignoreCase = true) }?.let {
                values.putIfAbsent(it, t.substring(it.length).trim())
            }
            i++
        }
        return CatsInfo(
            name = values["Name"],
            terminal = values["Terminal"],
            status = values["CATS Status"],
            restUntil = DayTime.parse(values["Rest until"], ref),
            assignment = values["Current Assignment"],
            location = values["Assignment Location"],
            boardPosition = values["Board Position"]?.trim()?.toIntOrNull(),
        )
    }

    private fun canonical(label: String) = LABELS.first { it.equals(label.trimEnd(':'), ignoreCase = true) }

    private fun parseBoard(tokens: List<String>, ref: LocalDate): List<BoardEntry> {
        val rows = mutableListOf<BoardEntry>()
        var group: MutableList<String>? = null
        fun flush() {
            group?.let { g -> rowFromCells(g, ref)?.let(rows::add) }
            group = null
        }
        for (t in tokens) {
            val line = ROW_LINE.matchEntire(t)
            when {
                POS.matches(t) -> { flush(); group = mutableListOf(t) }
                line != null && group == null -> rowFromLine(line, ref)?.let(rows::add)
                else -> group?.add(t)
            }
        }
        flush()
        return rows.distinctBy { it.pos }.sortedBy { it.pos }
    }

    /** Cells after the position: initials, then rest time, status and return time in that order. */
    private fun rowFromCells(cells: List<String>, ref: LocalDate): BoardEntry? {
        val pos = cells[0].toInt()
        val init = cells.getOrNull(1)?.takeUnless { DayTime.matches(it) } ?: return null
        var rest: String? = null
        var status: MatchResult? = null
        var ret: String? = null
        for (c in cells.drop(2)) {
            val s = STATUS.matchEntire(c)
            when {
                s != null && status == null -> status = s
                DayTime.matches(c) && status == null -> rest = c
                DayTime.matches(c) -> ret = c
            }
        }
        val st = status ?: return null
        return BoardEntry(
            pos = pos,
            init = init,
            restUntil = DayTime.parse(rest, ref),
            offStatus = st.groupValues[2],
            spare = st.groupValues[1].isNotEmpty(),
            returnAt = DayTime.parse(ret, ref),
        )
    }

    private fun rowFromLine(m: MatchResult, ref: LocalDate): BoardEntry? {
        val (pos, init, rest, stars, status, ret) = m.destructured
        return BoardEntry(
            pos = pos.toInt(),
            init = init.trim(),
            restUntil = DayTime.parse(rest, ref),
            offStatus = status,
            spare = stars.isNotBlank(),
            returnAt = DayTime.parse(ret, ref),
        )
    }

    private fun parseTrains(tokens: List<String>, ref: LocalDate): List<PoolTrain> {
        val trains = mutableListOf<PoolTrain>()
        var i = 0
        while (i < tokens.size) {
            val single = TRAIN.matchEntire(tokens[i])
            val joined = if (single == null && i + 1 < tokens.size) TRAIN.matchEntire("${tokens[i]} ${tokens[i + 1]}") else null
            val m = single ?: joined
            if (m != null) {
                val (symbol, origin, status, day, h, min) = m.destructured
                DayTime.resolve(day.toInt(), h.toInt(), min.toInt(), ref)?.let {
                    trains += PoolTrain(symbol, origin.toInt(), status, it)
                }
            }
            i += if (joined != null) 2 else 1
        }
        return trains.sortedBy { it.planned }
    }
}
