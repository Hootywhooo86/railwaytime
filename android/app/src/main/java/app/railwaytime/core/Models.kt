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
)

/** One row of "Additional Board Information": Pos / Init / Rest / Off St / Return. */
data class BoardEntry(
    val pos: Int,
    val init: String,
    val restUntil: LocalDateTime?,
    /** Off-status code without the "**" marker, e.g. "A0" or "HT". */
    val offStatus: String,
    /** "**" in Off St: the turn goes spare, so it still leaves ahead of you. */
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
    val label: String get() = "$symbol $originDay"
}

data class BoardSnapshot(
    val cats: CatsInfo,
    val board: List<BoardEntry>,
    val trains: List<PoolTrain>,
) {
    val isUsable: Boolean get() = cats.boardPosition != null || board.isNotEmpty() || trains.isNotEmpty()
}
