package app.railwaytime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Lines laid out like the IVRM screenshot (914 px wide), handed over column by column as OCR does. */
class OcrTest {
    private fun line(text: String, x: Int, y: Int, h: Int = 13) = OcrLine(text, x, y, x + text.length * 7, y + h)

    private fun screenshot(): List<OcrLine> {
        val left = mutableListOf<OcrLine>()
        val right = mutableListOf<OcrLine>()
        left += line("15:44", 72, 55, 26)
        left += line("ivrm.cn.ca", 140, 170, 28)
        left += line("Mobile Crew Inquiry", 391, 243, 15)
        left += line("Sunday, October 18, 2026 5:44 PM ET", 330, 374, 15)
        left += line("CATS Information", 81, 468, 15)
        left += line("My current CATS information:", 15, 512)
        val cats = listOf(
            "Name" to "CREW MEMBER", "Terminal" to "24 EM", "CATS Status" to "AVAILABLE",
            "Rest until" to "18 22:50", "Current Assignment" to "24 EM EPEP20 EN",
            "Assignment Location" to "Home", "Board Position" to "08",
        )
        cats.forEachIndexed { i, (label, value) ->
            left += line(label, 15, 547 + i * 16, 12)
            right += line(value, 457, 547 + i * 16, 12)
        }
        left += line("Additional Board Information", 81, 690, 15)
        left += line("Employee Status", 21, 754, 15)
        left += line("All", 172, 754, 15)
        left += line("Pos", 14, 803, 15); left += line("Init", 103, 803, 15); left += line("Rest", 191, 803, 15)
        right += line("Off St", 368, 803, 15); right += line("Return", 546, 803, 15)
        val board = listOf(
            listOf("01", "SS", "** HT", "19 0559"), listOf("02", "DF", "** HT", "19 0559"),
            listOf("03", "JDL", "A0", ""), listOf("04", "JDS", "** HT", "18 1630"),
            listOf("05", "R W", "A0", ""), listOf("06", "DML", "A0", ""),
            listOf("07", "JB", "* HT", "19 0559"), // OCR dropped a star
        )
        board.forEachIndexed { i, (pos, init, off, ret) ->
            val y = 829 + i * 27
            left += line(pos, 14, y, 16)
            left += line(init, 103, y, 16)
            right += line(off, 368, y, 16)
            if (ret.isNotEmpty()) right += line(ret, 546, y, 16)
        }
        left += line("Pool Lineup", 81, 1045, 15)
        left += line("My Pool Lineup:", 15, 1088)
        val trains = listOf(
            "DHT303B 18" to "Planned 18 16:00", "U76151 18" to "Planned 18 18:30",
            "SK79151 18" to "Planned 18 19:00", "A41751 18" to "Planned 18 20:00",
            "Q10131 15" to "Planned 18 20:45", "G84551 17" to "Planned 18 21:00",
            "G81741 18" to "Planned 19 01:46", "U71151 18" to "Planned 19 08:01",
            "U72551 19" to "Planned 19 09:30", "M31151 19" to "Planned 19 10:00",
        )
        trains.forEachIndexed { i, (t, p) ->
            left += line(t, 15, 1124 + i * 16, 12)
            right += line(p, 457, 1124 + i * 16, 12)
        }
        left += line("Train Lineup", 81, 1361, 15)
        left += line("Terminal Lineup", 81, 1418, 15)
        return left + right
    }

    @Test fun screenshotReadsLikeThePage() {
        val text = OcrRows.toText(screenshot())
        assertTrue(text, text.contains("Board Position 08"))
        assertTrue(text, text.contains("01 SS ** HT 19 0559"))
        assertTrue(text, text.contains("DHT303B 18 Planned 18 16:00"))

        val s = BoardParser.parse(text, LocalDate.of(2026, 1, 1))
        assertEquals(8, s.cats.boardPosition)
        assertEquals(LocalDateTime.of(2026, 10, 18, 22, 50), s.cats.restUntil)
        assertEquals(listOf("SS", "DF", "JDL", "JDS", "R W", "DML", "JB"), s.board.map { it.init })
        // "** HT" is a hold turn; none of these are spare turns.
        assertTrue(s.board.none { it.spare })
        assertEquals(listOf("HT", "HT", "A0", "HT", "A0", "A0", "HT"), s.board.map { it.offStatus })
        assertEquals(10, s.trains.size)

        val p = Predictor.predict(s, LocalDateTime.of(2026, 10, 18, 15, 44))
        assertEquals("G81741", p.myTrain!!.train.symbol)
    }
}

/** Text boxes from the real IVRM screenshot, as an on-device reader returns them. */
class RealScreenshotTest {
    private fun lines(): List<OcrLine> =
        javaClass.getResourceAsStream("/ivrm_screenshot_ocr.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { row ->
                val (l, t, r, b, text) = row.split('\t', limit = 5)
                OcrLine(text, l.toInt(), t.toInt(), r.toInt(), b.toInt())
            }

    @Test fun readsTheRealScreenshot() {
        val text = OcrRows.toText(lines())
        val s = BoardParser.parse(text, LocalDate.of(2026, 1, 1))
        assertEquals(text, 8, s.cats.boardPosition)
        assertEquals("CREW MEMBER", s.cats.name)
        assertEquals("24 EM EPEP20 EN", s.cats.assignment)
        assertEquals(LocalDateTime.of(2026, 10, 18, 22, 50), s.cats.restUntil)

        // This reader also lost JB's initials on row 07; the turn still counts.
        assertEquals(text, listOf("SS", "DF", "JDL", "JDS", "RW", "DML", "?"), s.board.map { it.init })
        assertTrue(s.board.none { it.spare })
        // Row 03's "A0" wasn't read at all: no status and no return time counts as available.
        assertEquals(listOf("HT", "HT", "A0", "HT", "A0", "A0", "HT"), s.board.map { it.offStatus })
        assertEquals(LocalDateTime.of(2026, 10, 19, 5, 59), s.board[0].returnAt)
        assertEquals(LocalDateTime.of(2026, 10, 18, 16, 30), s.board[3].returnAt)

        assertEquals(text, 13, s.trains.size)
        assertEquals("DHT303B", s.trains.first().symbol)
        assertEquals(18, s.trains.first().originDay)
        assertEquals(LocalDateTime.of(2026, 10, 19, 13, 15), s.trains.first { it.symbol == "G80951" }.planned)

        val p = Predictor.predict(s, LocalDateTime.of(2026, 10, 18, 15, 44))
        assertEquals(3, p.availableAhead)
        assertEquals(0, p.spareAhead)
        assertEquals("JDS", p.nextBack!!.init)
        assertEquals("G81741", p.myTrain!!.train.symbol)
    }
}
