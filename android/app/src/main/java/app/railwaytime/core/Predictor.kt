package app.railwaytime.core

import java.time.LocalDateTime

/**
 * Who is expected to take a train. [who] is null when nobody at or ahead of you can take it,
 * so it goes to someone behind you. [lostTurn] are "**" people it passed while they rested.
 */
data class Assignment(
    val train: PoolTrain,
    val orderAt: LocalDateTime,
    val who: String?,
    val pos: Int?,
    val isMe: Boolean = false,
    val lostTurn: List<String> = emptyList(),
)

data class Prediction(
    val myPosition: Int?,
    val ahead: List<BoardEntry>,
    /** A0 turns ahead of you. */
    val availableAhead: Int,
    /** Ahead of you and still on rest (HT or ** HT with a return time to come). */
    val restingAhead: Int,
    /** "**" turns ahead of you (they don't hold their turn while resting). */
    val spareAhead: Int,
    /** The next person ahead of you to come off rest (earliest Return time still to come). */
    val nextBack: BoardEntry?,
    val myRestUntil: LocalDateTime?,
    val assignments: List<Assignment>,
    val myTrain: Assignment?,
    /** How many people are expected to be ordered before you. */
    val goBeforeYou: Int,
    /** When the lineup runs out first: how many more trains until your turn. */
    val trainsBeyondLineup: Int,
)

/**
 * Walks the pool lineup in order. Each train is ordered [callLeadMinutes] before its planned
 * time and goes to the first turn on the board, top down, that is off rest by then:
 *  - A0: available now.
 *  - HT: resting until the Return time, but holds the turn — a train can pass them while they
 *    rest and they're still ahead of you when they're back.
 *  - ** HT: resting until the Return time and does NOT hold the turn — once a train passes
 *    them while they rest, they lose the turn and drop behind you.
 *  - You: off rest at "Rest until"; a train that comes up before that passes you.
 * Trains whose order time has already passed are treated as already crewed.
 */
object Predictor {
    private class Candidate(
        val label: String,
        val pos: Int?,
        val freeAt: LocalDateTime?,
        val holdsTurn: Boolean,
        val isMe: Boolean,
    ) {
        fun freeBy(t: LocalDateTime) = freeAt != null && !freeAt.isAfter(t)
    }

    fun predict(snapshot: BoardSnapshot, now: LocalDateTime, callLeadMinutes: Long = 120): Prediction {
        val myPos = snapshot.cats.boardPosition
        val ahead = snapshot.board.filter { myPos == null || it.pos < myPos }
        val myRest = snapshot.cats.restUntil

        val queue = ahead.map { e ->
            val freeAt = if (e.available) maxOf(now, e.restUntil ?: now) else e.returnAt?.let { maxOf(now, it) }
            Candidate(e.init, e.pos, freeAt, holdsTurn = !e.spare, isMe = false)
        }.toMutableList()
        queue += Candidate("YOU", myPos, maxOf(now, myRest ?: now), holdsTurn = true, isMe = true)

        val assignments = mutableListOf<Assignment>()
        var mine: Assignment? = null
        for (train in snapshot.trains.sortedBy { it.planned }) {
            val orderAt = train.planned.minusMinutes(callLeadMinutes)
            if (orderAt.isBefore(now)) continue
            val takerIndex = queue.indexOfFirst { it.freeBy(orderAt) }
            // Everyone above the taker (or everyone, if nobody here can take it) is passed by.
            val passed = if (takerIndex < 0) queue.toList() else queue.subList(0, takerIndex).toList()
            val lost = passed.filter { !it.holdsTurn }
            queue.removeAll(lost)
            val c = queue.firstOrNull { it.freeBy(orderAt) }
            val a = Assignment(train, orderAt, c?.label, c?.pos, c?.isMe == true, lost.map { it.label })
            assignments += a
            if (c == null) continue
            queue.remove(c)
            if (c.isMe) {
                mine = a
                break
            }
        }

        return Prediction(
            myPosition = myPos,
            ahead = ahead,
            availableAhead = ahead.count { it.available },
            restingAhead = ahead.count { !it.available && (it.returnAt == null || it.returnAt.isAfter(now)) },
            spareAhead = ahead.count { it.spare },
            nextBack = ahead.filter { it.returnAt != null && it.returnAt.isAfter(now) }.minByOrNull { it.returnAt!! },
            myRestUntil = myRest,
            assignments = assignments,
            myTrain = mine,
            goBeforeYou = assignments.count { it.who != null && !it.isMe } +
                if (mine == null) queue.takeWhile { !it.isMe }.count { it.freeAt != null } else 0,
            trainsBeyondLineup = if (mine != null) 0 else queue.takeWhile { !it.isMe }.count { it.freeAt != null } + 1,
        )
    }
}
