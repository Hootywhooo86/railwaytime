package app.railwaytime.core

import java.time.LocalDateTime

/** Who is expected to take a train. [who] is null when it goes past you to someone behind. */
data class Assignment(
    val train: PoolTrain,
    val orderAt: LocalDateTime,
    val who: String?,
    val pos: Int?,
    val isMe: Boolean = false,
)

data class Prediction(
    val myPosition: Int?,
    val ahead: List<BoardEntry>,
    /** A0 turns ahead of you. */
    val availableAhead: Int,
    /** "**" turns ahead of you, covered by a spare. */
    val spareAhead: Int,
    /** Held turns (HT without "**") ahead of you that aren't back yet. */
    val heldAhead: Int,
    /** The next held turn ahead of you to come back. */
    val nextBack: BoardEntry?,
    val myRestUntil: LocalDateTime?,
    val assignments: List<Assignment>,
    val myTrain: Assignment?,
    /** When the lineup runs out first: how many more trains until your turn. */
    val trainsBeyondLineup: Int,
) {
    val leavingAhead: Int get() = availableAhead + spareAhead
}

/**
 * Walks the pool lineup in order. Each train is ordered [callLeadMinutes] before its
 * planned time and goes to the first turn on the board that is free by then:
 *  - A0 turns are free now (or when their rest ends),
 *  - "**" turns are covered by a spare, so they always leave ahead of you,
 *  - held turns are free again at their return time,
 *  - your turn is free once your rest ends; if it isn't, the train goes past you.
 * Trains whose order time has already passed are treated as already crewed.
 */
object Predictor {
    private class Candidate(val label: String, val pos: Int?, val freeAt: LocalDateTime?, val isMe: Boolean)

    fun predict(snapshot: BoardSnapshot, now: LocalDateTime, callLeadMinutes: Long = 120): Prediction {
        val myPos = snapshot.cats.boardPosition
        val ahead = snapshot.board.filter { myPos == null || it.pos < myPos }
        val myRest = snapshot.cats.restUntil

        val queue = ahead.map { e ->
            val freeAt = when {
                e.spare -> now
                e.available -> maxOf(now, e.restUntil ?: now)
                else -> e.returnAt
            }
            Candidate(e.init, e.pos, freeAt, isMe = false)
        }.toMutableList()
        queue += Candidate("YOU", myPos, maxOf(now, myRest ?: now), isMe = true)

        val assignments = mutableListOf<Assignment>()
        var mine: Assignment? = null
        for (train in snapshot.trains.sortedBy { it.planned }) {
            val orderAt = train.planned.minusMinutes(callLeadMinutes)
            if (orderAt.isBefore(now)) continue
            val c = queue.firstOrNull { it.freeAt != null && !it.freeAt.isAfter(orderAt) }
            val a = Assignment(train, orderAt, c?.label, c?.pos, c?.isMe == true)
            assignments += a
            if (c == null) continue
            queue.remove(c)
            if (c.isMe) { mine = a; break }
        }

        val held = ahead.filter { !it.spare && !it.available }
        return Prediction(
            myPosition = myPos,
            ahead = ahead,
            availableAhead = ahead.count { !it.spare && it.available },
            spareAhead = ahead.count { it.spare },
            heldAhead = held.count { it.returnAt == null || it.returnAt.isAfter(now) },
            nextBack = held.filter { it.returnAt != null && it.returnAt.isAfter(now) }.minByOrNull { it.returnAt!! },
            myRestUntil = myRest,
            assignments = assignments,
            myTrain = mine,
            trainsBeyondLineup = if (mine != null) 0 else queue.takeWhile { !it.isMe }.count { it.freeAt != null } + 1,
        )
    }
}
