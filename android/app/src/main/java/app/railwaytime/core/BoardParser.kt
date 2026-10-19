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
    /** Off St: a code ("A0", "HT"), possibly after "**", or "**" on its own (a spare turn). */
    private val STATUS = Regex("""^(\*{1,2})?\s*([A-Z][A-Z0-9]{1,3})?$""")
    private const val DT = DayTime.PATTERN_SRC

    /**
     * A whole board row on one line: "05 R W A0", "01 SS ** HT 19 0559". Screenshots can lose
     * the status ("03 JDL"); then it's taken as A0, or HT when there's a return time.
     * Missing initials ("07 ** HT 19 0559") still count as a turn, shown as "?".
     */
    private val ROW_LINE = Regex(
        """^(\d{1,3})(?:\s+([^*\s].*?))?(?:\s+($DT))?(?:\s+(\*{1,2}(?:\s*[A-Za-z][A-Za-z0-9]{1,3})?|[A-Za-z][A-Za-z0-9]{1,3}))?(?:\s+($DT))?$""",
    )

    /** "U76151 18 Planned 18 18:30"; screenshots may read "U7615118 Planned 1818:30". */
    private val TRAIN = Regex(
        """^([A-Z0-9]*?\d[A-Z0-9]*?)(?:\s+(\d{1,2})|(\d{2}))\s+([A-Za-z]+)\s*($DT)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Lower case, no spaces or trailing colon: "CurrentAssignment" matches "Current Assignment". */
    private fun norm(s: String) = s.lowercase().replace(" ", "").trimEnd(':')

    /** "Current Assignment" → matches "Current Assignment 24 EM…" or "CurrentAssignment 24 EM…". */
    private val LABEL_LINES = LABELS.associateWith { label ->
        Regex("""^${label.split(' ').joinToString("""\s*""")}:?\s+(.+)$""", RegexOption.IGNORE_CASE)
    }

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
    private fun indexOf(tokens: List<String>, heading: String, from: Int = 0): Int? {
        val h = norm(heading)
        return (from until tokens.size).firstOrNull { norm(tokens[it]).let { t -> t.endsWith(h) && t.length <= h.length + 4 } }
    }

    private fun parseCats(tokens: List<String>, ref: LocalDate): CatsInfo {
        val values = mutableMapOf<String, String>()
        fun isLabel(t: String) = LABELS.any { norm(it) == norm(t) }
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
            for ((label, re) in LABEL_LINES) {
                val m = re.matchEntire(t) ?: continue
                values.putIfAbsent(label, m.groupValues[1].trim())
                break
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

    private fun canonical(label: String) = LABELS.first { norm(it) == norm(label) }

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
        val init = cells.getOrNull(1)?.takeUnless { DayTime.matches(it) }?.uppercase() ?: return null
        var rest: String? = null
        var status: String? = null
        var ret: String? = null
        for (c in cells.drop(2)) {
            when {
                status == null && STATUS.matches(c) -> status = c
                DayTime.matches(c) && status == null -> rest = c
                DayTime.matches(c) -> ret = c
            }
        }
        return entry(pos, init, rest, status ?: return null, ret, ref)
    }

    private fun rowFromLine(m: MatchResult, ref: LocalDate): BoardEntry? {
        val (pos, init, rest, status, ret) = m.destructured
        return entry(pos.toInt(), init.trim().uppercase().ifEmpty { "?" }, rest, status, ret, ref)
    }

    /**
     * Off St rules: the code decides. "HT" (with or without a "**" in front) is a hold turn;
     * only "**" with no code after it is a spare turn. A screenshot that lost the code reads
     * as A0, or HT when there's a return time.
     */
    private fun entry(pos: Int, init: String, rest: String?, status: String, ret: String?, ref: LocalDate): BoardEntry {
        val returnAt = DayTime.parse(ret, ref)
        val stars = status.takeWhile { it == '*' }
        // Screenshots read A0 (zero) as AO (letter O).
        val code = status.drop(stars.length).trim().uppercase().let { if (it == "AO") "A0" else it }
        val spare = stars.isNotEmpty() && code.isEmpty()
        return BoardEntry(
            pos = pos,
            init = init,
            restUntil = DayTime.parse(rest, ref),
            offStatus = when {
                spare -> "**"
                code.isNotEmpty() -> code
                returnAt != null -> "HT"
                else -> "A0"
            },
            spare = spare,
            returnAt = returnAt,
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
                val (symbol, origin1, origin2, status, planned) = m.destructured
                DayTime.parse(planned, ref)?.let {
                    trains += PoolTrain(symbol.uppercase(), origin1.ifEmpty { origin2 }.toInt(), status, it)
                }
            }
            i += if (joined != null) 2 else 1
        }
        return trains.sortedBy { it.planned }
    }
}
