package app.railwaytime.core

import java.time.LocalDateTime

/** "My current CATS information" block. Times are board times (see [RailClock.boardNow]). */
data class CatsInfo(
    val name: String? = null,
    val terminal: String? = null,
    val status: String? = null,
    val restUntil: LocalDateTime? = null,
    val assignment: String? = null,
    val location: String? = null,
    val boardPosition: Int? = null,
    /** On an excused absence (or similar) CATS shows "Expected Return" instead of "Rest until". */
    val expectedReturn: LocalDateTime? = null,
) {
    /** When you're free again: the later of Rest until and Expected Return. */
    val offUntil: LocalDateTime? get() = listOfNotNull(restUntil, expectedReturn).maxOrNull()

    /** The CATS status when it's something other than plain AVAILABLE (e.g. EXCUSED ABSENCE). */
    val specialStatus: String? get() = status?.trim()?.takeIf { it.isNotEmpty() && !it.equals("AVAILABLE", ignoreCase = true) }
}

/** One row of "Additional Board Information": Pos / Init / Rest / Off St / Return. */
data class BoardEntry(
    val pos: Int,
    val init: String,
    val restUntil: LocalDateTime?,
    /** Off-status code, e.g. "A0" or "HT" ("**" for a spare turn). */
    val offStatus: String,
    /** Off St shows only "**": a spare turn — a spare works it, so it goes out ahead of you. "** HT" is a hold turn, not this. */
    val spare: Boolean,
    val returnAt: LocalDateTime?,
) {
    /** A0 (zero) or AO (letter) both mean available. */
    val available: Boolean get() = offStatus == "A0" || offStatus == "AO"
}

/** One line of "My Pool Lineup", e.g. "U76151 18   Planned 18 18:30". */
data class PoolTrain(
    val symbol: String,
    val originDay: Int,
    val status: String,
    val planned: LocalDateTime,
) {
    val label: String get() = "%s %02d".format(symbol, originDay)

    /** IVRM's word for the time, shortened to fit: "Plan" (Planned) or "Ord" (Ordered, time is set). */
    val statusWord: String get() = when (status.lowercase()) {
        "planned" -> "Plan"
        "ordered" -> "Ord"
        else -> status.lowercase().replaceFirstChar { it.uppercase() }
    }
}

data class BoardSnapshot(
    val cats: CatsInfo,
    val board: List<BoardEntry>,
    val trains: List<PoolTrain>,
) {
    val isUsable: Boolean get() = cats.boardPosition != null || board.isNotEmpty() || trains.isNotEmpty()
}
