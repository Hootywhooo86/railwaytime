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
        assertTrue(b[0].spare)
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
        assertEquals(4, p.spareAhead)
        assertEquals(7, p.leavingAhead)
        // DHT303B was ordered at 14:00, so it's already crewed; the next 7 trains go to the 7 ahead.
        assertEquals(
            listOf("SS", "DF", "JDL", "JDS", "R W", "DML", "JB", "YOU"),
            p.assignments.map { it.who },
        )
        assertEquals("U72551", p.myTrain!!.train.symbol)
        assertEquals(LocalDateTime.of(2026, 10, 19, 7, 30), p.myTrain!!.orderAt)
    }

    @Test fun heldTurnWaitsAndRestedYouGetSkipped() {
        val text = tabbed
            .replace("01\tSS\t\t** HT", "01\tSS\t\tHT")
            .replace("Rest until\t18 22:50", "Rest until\t19 09:00")
        val p = Predictor.predict(BoardParser.parse(text, ref), now, 120)
        assertEquals(1, p.heldAhead)
        // SS is held until 19 05:59, so SS isn't free until then.
        assertEquals("DF", p.assignments.first().who)
        assertTrue(p.assignments.any { it.who == "SS" && it.train.symbol == "U71151" })
        // You are rested 19 09:00: U72551, M31151 and S71751 are ordered before that and pass you by.
        assertEquals(3, p.assignments.count { it.who == null })
        assertEquals("G80951", p.myTrain!!.train.symbol)
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
        assertTrue(p.board[0].spare)
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
