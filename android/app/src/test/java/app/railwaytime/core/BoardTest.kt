package app.railwaytime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Based on the IVRM screenshot from Oct 18, 2026, 15:44 Mountain. */
class BoardTest {
    // Shaped like PageText.EXTRACT_JS output: cells end in tabs, rows/blocks on new lines.
    private val tabbed = """
        Refresh
        Mobile Crew Inquiry
        Sunday, October 18, 2026 5:44 PM ET
        CATS Information
        My current CATS information:
        Name	CREW MEMBER
        Terminal	24 EM
        CATS Status	AVAILABLE
        Rest until	18 22:50
        Current Assignment	24 EM EPEP20 EN
        Assignment Location	Home
        Board Position	08
        Additional Board Information
        Employee Status
        All
        Pos	Init	Rest	Off St	Return
        01	SS		** HT	19 0559
        02	DF		** HT	19 0559
        03	JDL		A0
        04	JDS		** HT	18 1630
        05	R W		A0
        06	DML		A0
        07	JB		** HT	19 0559
        Pool Lineup
        My Pool Lineup:
        DHT303B 18	Planned 18 16:00
        U76151 18	Planned 18 18:30
        SK79151 18	Planned 18 19:00
        A41751 18	Planned 18 20:00
        Q10131 15	Planned 18 20:45
        G84551 17	Planned 18 21:00
        G81741 18	Planned 19 01:46
        U71151 18	Planned 19 08:01
        U72551 19	Planned 19 09:30
        M31151 19	Planned 19 10:00
        S71751 19	Planned 19 10:55
        G80951 19	Planned 19 13:15
        G81543 19	Planned 19 14:51
        Train Lineup
        X99999 19	Planned 19 03:00
        Terminal Lineup
        Français
        Configuration
    """.trimIndent()

    private val now = LocalDateTime.of(2026, 10, 18, 15, 44)
    private val ref = LocalDate.of(2026, 1, 1)

    @Test fun parsesCats() {
        val s = BoardParser.parse(tabbed, ref)
        assertEquals("CREW MEMBER", s.cats.name)
        assertEquals("24 EM", s.cats.terminal)
        assertEquals("AVAILABLE", s.cats.status)
        assertEquals(LocalDateTime.of(2026, 10, 18, 22, 50), s.cats.restUntil)
        assertEquals("24 EM EPEP20 EN", s.cats.assignment)
        assertEquals(8, s.cats.boardPosition)
    }

    @Test fun parsesBoard() {
        val b = BoardParser.parse(tabbed, ref).board
        assertEquals(listOf("SS", "DF", "JDL", "JDS", "R W", "DML", "JB"), b.map { it.init })
        // "** HT" is a hold turn, not a spare turn.
        assertFalse(b[0].spare)
        assertEquals("HT", b[0].offStatus)
        assertEquals(LocalDateTime.of(2026, 10, 19, 5, 59), b[0].returnAt)
        assertTrue(b[2].available)
        assertFalse(b[2].spare)
        assertNull(b[2].returnAt)
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 30), b[3].returnAt)
    }

    @Test fun parsesPoolLineupOnly() {
        val t = BoardParser.parse(tabbed, ref).trains
        assertEquals(13, t.size)
        assertEquals("DHT303B 18", t.first().label)
        assertEquals(LocalDateTime.of(2026, 10, 18, 20, 45), t.first { it.symbol == "Q10131" }.planned)
        assertTrue(t.none { it.symbol == "X99999" })
    }

    @Test fun predictsFromScreenshot() {
        val p = Predictor.predict(BoardParser.parse(tabbed, ref), now, 120)
        assertEquals(7, p.ahead.size)
        assertEquals(3, p.availableAhead)
        assertEquals(0, p.spareAhead)
        assertEquals(4, p.heldAhead) // SS, DF, JB until 19 05:59; JDS until 16:30
        // DHT303B was ordered at 14:00, so it's already crewed. SS, DF and JB hold their turns until
        // 19 05:59; JDS until 16:30. JDL takes U76151, JDS (back 16:30) SK79151, then R W and DML.
        // G84551 (ordered 19:00): everyone left is resting, you included, so it goes behind you.
        assertEquals(listOf("JDL", "JDS", "R W", "DML", null, "YOU"), p.assignments.map { it.who })
        assertEquals(4, p.goBeforeYou)
        assertEquals("G81741", p.myTrain!!.train.symbol)
        assertEquals(LocalDateTime.of(2026, 10, 18, 23, 46), p.myTrain!!.orderAt)
    }

    @Test fun heldTurnKeepsItsPlace() {
        val text = tabbed.replace("Rest until\t18 22:50", "Rest until\t19 09:00")
        val p = Predictor.predict(BoardParser.parse(text, ref), now, 120)
        // While SS, DF and JB rest, trains go past them; back at 19 05:59 they're still ahead of you.
        assertEquals("JDL", p.assignments.first().who)
        assertEquals("SS", p.assignments.first { it.train.symbol == "U71151" }.who)
        assertEquals("DF", p.assignments.first { it.train.symbol == "U72551" }.who)
        assertEquals("JB", p.assignments.first { it.train.symbol == "M31151" }.who)
        // You're rested 19 09:00, so trains ordered before that go past you.
        assertEquals("G80951", p.myTrain!!.train.symbol)
    }

    @Test fun onlyStarsOnItsOwnIsASpareTurn() {
        val text = tabbed.replace("02\tDF\t\t** HT\t19 0559", "02\tDF\t\t**\t")
        val s = BoardParser.parse(text, ref)
        assertTrue(s.board[1].spare)
        val p = Predictor.predict(s, now, 120)
        // DF's turn goes spare: it's worked right away, ahead of you.
        assertEquals("DF (spare)", p.assignments.first().who)
        assertEquals(1, p.spareAhead)
    }

    /** The live board from the app on Oct 18 at 16:55. */
    @Test fun liveBoardAt1655() {
        val live = """
            Sunday, October 18, 2026 6:55 PM ET
            Rest until	18 22:50
            Board Position	08
            Additional Board Information
            Pos	Init	Rest	Off St	Return
            01	SS		** HT	19 0559
            02	DF		** HT	19 0559
            03	JDL		A0
            04	JDS		A0
            05	R W		A0
            06	DML		A0
            07	JB		** HT	19 0559
            Pool Lineup
            U76151 18	Planned 18 19:30
            A41751 18	Planned 18 19:45
            Q10131 15	Planned 18 20:45
            SK79151 18	Planned 18 21:45
            G84551 17	Planned 19 01:00
            G81741 18	Planned 19 01:46
            U71151 18	Planned 19 08:01
            U72551 19	Planned 19 09:30
        """.trimIndent()
        val p = Predictor.predict(BoardParser.parse(live, ref), LocalDateTime.of(2026, 10, 18, 16, 55), 120)
        // SS, DF and JB are on HT (hold turn) until 05:59: they don't go ahead of you. The four A0 do.
        assertEquals(listOf("JDL", "JDS", "R W", "DML", "YOU"), p.assignments.map { it.who })
        assertEquals(4, p.goBeforeYou)
        assertEquals("G84551", p.myTrain!!.train.symbol)
        assertEquals(LocalDateTime.of(2026, 10, 18, 23, 0), p.myTrain!!.orderAt)
        assertEquals("SS", p.nextBack!!.init)
    }

    @Test fun nextBackIsEarliestHeldReturn() {
        val text = tabbed.replace("04\tJDS\t\t** HT", "04\tJDS\t\tHT")
        val p = Predictor.predict(BoardParser.parse(text, ref), now, 120)
        assertEquals("JDS", p.nextBack!!.init)
    }

    @Test fun lineupTooShort() {
        val text = tabbed.lines().filterNot { it.startsWith("U7") || it.startsWith("M3") || it.startsWith("S7") || it.startsWith("G8") }
            .joinToString("\n")
        val p = Predictor.predict(BoardParser.parse(text, ref), now, 120)
        assertNull(p.myTrain)
        assertTrue(p.trainsBeyondLineup > 0)
    }

    @Test fun eachCellOnItsOwnLine() {
        val p = BoardParser.parse(tabbed.replace('\t', '\n'), ref)
        assertEquals(8, p.cats.boardPosition)
        assertEquals(7, p.board.size)
        assertEquals("R W", p.board[4].init)
        assertEquals(13, p.trains.size)
    }

    @Test fun plainTextWithoutTabs() {
        val p = BoardParser.parse(tabbed.replace(Regex("\t+"), " "), ref)
        assertEquals(8, p.cats.boardPosition)
        assertEquals(listOf("SS", "DF", "JDL", "JDS", "R W", "DML", "JB"), p.board.map { it.init })
        assertEquals("HT", p.board[0].offStatus)
        assertEquals(13, p.trains.size)
    }

    @Test fun fromHtml() {
        val html = """
            <html><body><div>Sunday, October 18, 2026 5:44 PM ET</div>
            <table><tr><td>Board Position</td><td>08</td></tr></table>
            <h3>Additional Board Information</h3>
            <table><tr><th>Pos</th><th>Init</th><th>Rest</th><th>Off St</th><th>Return</th></tr>
            <tr><td>01</td><td>SS</td><td>&nbsp;</td><td>** HT</td><td>19 0559</td></tr></table>
            <h3>Pool Lineup</h3><div>U76151 18</div><div>Planned 18 18:30</div>
            <script>var x = "<td>99</td>";</script></body></html>
        """.trimIndent()
        val s = BoardParser.parse(PageText.fromHtml(html), ref)
        assertEquals(8, s.cats.boardPosition)
        assertEquals(1, s.board.size)
        assertEquals(1, s.trains.size)
    }

    @Test fun loginPageIsNotABoard() {
        assertFalse(BoardParser.looksLikeBoard("Sign in\nEmail, phone, or Skype\nNext"))
    }

    @Test fun railtimeFallsBackNovFirst() {
        val s = AppSettings(homeZoneId = "America/Edmonton")
        val before = Instant.parse("2026-10-18T21:44:00Z")
        assertEquals(LocalDateTime.of(2026, 10, 18, 15, 44), RailClock.railNow(s, before))
        assertEquals("GMT-06:00", RailClock.railZoneId(s, before))
        val after = Instant.parse("2026-11-02T18:00:00Z")
        val local = LocalDateTime.ofInstant(after, ZoneId.of("America/Edmonton"))
        assertEquals(local.minusHours(1), RailClock.railNow(s, after))
        assertEquals(LocalDateTime.of(2026, 11, 1, 2, 0).atZone(ZoneId.of("America/Edmonton")).toInstant(), RailClock.nextSwitch(s, before))
    }

    @Test fun dayRollsIntoNextMonth() {
        assertEquals(LocalDateTime.of(2026, 11, 1, 5, 0), DayTime.parse("01 0500", LocalDate.of(2026, 10, 31)))
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 0), DayTime.parse("30 23:00", LocalDate.of(2026, 10, 1)))
    }
}

/** The board in the app on Oct 18 at 19:35: SS, DF and JB on HT must not go ahead of you. */
class Board1935Test {
    @Test fun holdTurnsStayBehindYou() {
        val text = """
            Sunday, October 18, 2026 9:35 PM ET
            Rest until	18 22:50
            Board Position	06
            Additional Board Information
            Pos	Init	Rest	Off St	Return
            01	SS		** HT	19 0559
            02	DF		** HT	19 0559
            03	R W		A0
            04	DML		A0
            05	JB		** HT	19 0559
            Pool Lineup
            G84551 17	Planned 19 01:00
            SK79151 18	Planned 19 01:00
            G81741 18	Planned 19 04:00
            U71151 18	Planned 19 08:01
            U72551 19	Planned 19 09:30
            M31151 19	Planned 19 10:00
        """.trimIndent()
        val p = Predictor.predict(BoardParser.parse(text, LocalDate.of(2026, 10, 18)), LocalDateTime.of(2026, 10, 18, 19, 35), 120)
        assertEquals(2, p.availableAhead)
        assertEquals(0, p.spareAhead)
        assertEquals(3, p.heldAhead)
        assertEquals(listOf("R W", "DML", "YOU"), p.assignments.map { it.who })
        assertEquals(2, p.goBeforeYou)
        assertEquals("G81741", p.myTrain!!.train.symbol)
        // Shown around it on the widget: one train before, two after.
        assertEquals("SK79151", p.trainBefore!!.symbol)
        assertEquals(listOf("U71151", "U72551"), p.trainsAfter.map { it.symbol })
    }
}

/**
 * "Ordered" on IVRM means the train's time is set, not that a crew is called: the call still
 * comes 2 h before. Only a passed call time takes a train out.
 */
class OrderedTrainTest {
    private fun board(lineup: String) = """
        Sunday, October 18, 2026 11:08 PM ET
        Rest until	18 22:50
        Board Position	05
        Additional Board Information
        Pos	Init	Rest	Off St	Return
        01	SS		** HT	19 0559
        02	DF		** HT	19 0559
        03	DML		A0
        04	JB		** HT	19 0559
        Pool Lineup
    """.trimIndent() + "\n" + lineup.trimIndent()

    private val at2109 = LocalDateTime.of(2026, 10, 18, 21, 9)

    @Test fun orderedButCallTimePassedIsCrewed() {
        val text = board("""
            U76151 18	Ordered 18 19:55
            SK79151 18	Planned 19 02:00
            G81741 18	Planned 19 04:00
        """)
        val p = Predictor.predict(BoardParser.parse(text, LocalDate.of(2026, 10, 18)), at2109, 120)
        // 19:55 train: call was 17:55, already crewed.
        assertTrue(p.assignments.none { it.train.symbol == "U76151" })
        assertEquals(listOf("DML", "YOU"), p.assignments.map { it.who })
        assertEquals("G81741", p.myTrain!!.train.symbol)
    }

    @Test fun orderedButNotCalledYetStillCounts() {
        val text = board("""
            SK79151 18	Ordered 19 02:00
            G81741 18	Planned 19 04:00
        """)
        val p = Predictor.predict(BoardParser.parse(text, LocalDate.of(2026, 10, 18)), at2109, 120)
        // 02:00 train shows Ordered, but its call isn't until 00:00: DML still gets it.
        assertEquals("DML", p.assignments.first { it.train.symbol == "SK79151" }.who)
        assertEquals("Ord", p.assignments.first().train.statusWord)
        assertEquals("Plan", p.myTrain!!.train.statusWord)
        assertEquals("G81741", p.myTrain!!.train.symbol)
        assertEquals(LocalDateTime.of(2026, 10, 19, 2, 0), p.myTrain!!.orderAt)
    }
}

/** The live board on Oct 19 at 12:41: A0s with rest times in the Rest column. */
class Board1241Test {
    private val text = """
        Monday, October 19, 2026 2:40 PM ET
        CATS Information
        Name	CREW MEMBER
        CATS Status	AVAILABLE
        Rest until	19 20:25
        Assignment Location	Away
        Board Position	04
        Additional Board Information
        Pos	Init	Rest	Off St	Return
        01	JDS		A0
        02	R W	19 1340	A0
        03	DML	19 1830	A0
        Pool Lineup
        G83454 17	Ordered 19 15:00
        G83451 18	Planned 19 16:31
        G83455 17	Planned 19 19:00
        Q10251 18	Planned 19 21:15
        M35651 18	Planned 19 22:16
        G84651 18	Planned 19 23:15
        DH84651 18	Planned 19 23:15
        G83453 18	Planned 20 00:29
    """.trimIndent()

    private val p = Predictor.predict(BoardParser.parse(text, LocalDate.of(2026, 10, 19)), LocalDateTime.of(2026, 10, 19, 12, 41), 120)

    @Test fun nextOffRestUsesTheRestColumn() {
        assertEquals("R W", p.nextBack!!.init)
        assertEquals(LocalDateTime.of(2026, 10, 19, 13, 40), p.nextBackAt)
    }

    @Test fun restingA0WaitsForItsRest() {
        // JDS now; R W off rest 13:40, in time for the 14:31 call; DML rests until 18:30 so the
        // 17:00 call passes to someone behind; DML takes Q10251. M35651 is called 20:16, before
        // your 20:25 rest ends, so it goes past you: G84651 is yours.
        assertEquals(listOf("JDS", "R W", null, "DML", null, "YOU"), p.assignments.map { it.who })
        assertEquals("G84651", p.myTrain!!.train.symbol)
        assertEquals("M35651", p.trainBefore!!.symbol)
        assertEquals(listOf("DH84651", "G83453"), p.trainsAfter.map { it.symbol })
    }

    @Test fun readsLocationAndStatus() {
        val c = BoardParser.parse(text, LocalDate.of(2026, 10, 19)).cats
        assertEquals("Away", c.location)
        assertEquals("AVAILABLE", c.status)
    }
}
