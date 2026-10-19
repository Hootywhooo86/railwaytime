package app.railwaytime.core

import java.time.LocalDateTime

/**
 * Who is expected to take a train. [who] is null when nobody at or ahead of you can take it,
 * so it goes to someone behind you.
 */
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
    /** HT ahead of you, still on rest: they hold their turn and go once rested. */
    val heldAhead: Int,
    /** "**" ahead of you: spare turns, which go out ahead of you. */
    val spareAhead: Int,
    /** The next person ahead of you to come off rest (earliest Return time still to come). */
    val nextBack: BoardEntry?,
    val myRestUntil: LocalDateTime?,
    val assignments: List<Assignment>,
    val myTrain: Assignment?,
    /** How many people are expected to be called before you. */
    val goBeforeYou: Int,
    /** When the lineup runs out first: how many more trains until your turn. */
    val trainsBeyondLineup: Int,
)

/**
 * Walks the pool lineup in order. Each train is called [callLeadMinutes] before its planned
 * time and goes to the first turn on the board, top down, that is off rest by then:
 *  - A0: available now.
 *  - HT: holds the turn but doesn't go until the Return time; a train can pass them while
 *    they rest and they're still ahead of you after.
 *  - ** (on its own): spare turn — a spare works it, so it goes out ahead of you, starting now.
 *    "** HT" is still HT: a hold turn.
 *  - You: off rest at "Rest until"; a train that comes up before that passes you.
 * Trains whose order time has already passed are treated as already crewed.
 */
object Predictor {
    private class Candidate(val label: String, val pos: Int?, val freeAt: LocalDateTime?, val isMe: Boolean) {
        fun freeBy(t: LocalDateTime) = freeAt != null && !freeAt.isAfter(t)
    }

    fun predict(snapshot: BoardSnapshot, now: LocalDateTime, callLeadMinutes: Long = 120): Prediction {
        val myPos = snapshot.cats.boardPosition
        val ahead = snapshot.board.filter { myPos == null || it.pos < myPos }
        val myRest = snapshot.cats.restUntil

        val queue = ahead.map { e ->
            val freeAt = when {
                e.spare -> now // a spare works the turn: it goes out ahead of you, now
                e.available -> maxOf(now, e.restUntil ?: now)
                else -> e.returnAt?.let { maxOf(now, it) } // HT: holds the spot until rested
            }
            Candidate(if (e.spare) "${e.init} (spare)" else e.init, e.pos, freeAt, isMe = false)
        }.toMutableList()
        queue += Candidate("YOU", myPos, maxOf(now, myRest ?: now), isMe = true)

        val assignments = mutableListOf<Assignment>()
        var mine: Assignment? = null
        for (train in snapshot.trains.sortedBy { it.planned }) {
            val orderAt = train.planned.minusMinutes(callLeadMinutes)
            // Already called (IVRM shows "Ordered"), or its call time has passed: already crewed.
            if (orderAt.isBefore(now) || train.status.equals("Ordered", ignoreCase = true)) continue
            val c = queue.firstOrNull { it.freeBy(orderAt) }
            val a = Assignment(train, orderAt, c?.label, c?.pos, c?.isMe == true)
            assignments += a
            if (c == null) continue
            queue.remove(c)
            if (c.isMe) {
                mine = a
                break
            }
        }

        val stillAhead = queue.takeWhile { !it.isMe }.count { it.freeAt != null }
        return Prediction(
            myPosition = myPos,
            ahead = ahead,
            availableAhead = ahead.count { !it.spare && it.available },
            heldAhead = ahead.count { !it.spare && !it.available && (it.returnAt == null || it.returnAt.isAfter(now)) },
            spareAhead = ahead.count { it.spare },
            nextBack = ahead.filter { it.returnAt != null && it.returnAt.isAfter(now) }.minByOrNull { it.returnAt!! },
            myRestUntil = myRest,
            assignments = assignments,
            myTrain = mine,
            goBeforeYou = assignments.count { it.who != null && !it.isMe } + if (mine == null) stillAhead else 0,
            trainsBeyondLineup = if (mine != null) 0 else stillAhead + 1,
        )
    }
}
