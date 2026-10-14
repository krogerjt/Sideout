package com.krogerjt.sideout

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ModelTest {
    private val live = """
        {"id":"6400123","date":"2026-10-14","division":"d1",
         "away":{"name":"Wisconsin","abbreviation":"WIS","slug":"wisconsin","setsWon":1,"rank":4,"color":"#496653"},
         "home":{"name":"Nebraska","abbreviation":"NEB","slug":"nebraska","setsWon":1,"rank":null,"color":"#496653"},
         "status":"live","statusText":"3rd","startTime":1792018800,
         "sets":[{"number":1,"home":25,"away":20},{"number":2,"home":22,"away":25},{"number":3,"home":15,"away":18}],
         "venue":"Devaney Center","checkedAt":1792021000,"detailsAvailable":true,"sourceURL":"https://www.ncaa.com/game/6400123"}
    """.trimIndent()

    @Test fun parsesMatchAndCurrentSet() {
        val match = Json.match(JSONObject(live))
        assertEquals("WIS", match.away.abbreviation)
        assertEquals(4, match.away.rank)
        assertNull(match.home.rank)
        assertEquals(SetScore(3, 15, 18), match.currentSet)
        assertEquals("18", match.currentSet?.away.scoreText)
    }

    @Test fun noCurrentSetOnceFinal() {
        val match = Json.match(JSONObject(live.replace("\"live\"", "\"final\"")))
        assertNull(match.currentSet)
        assertEquals(true, match.isOver)
    }

    @Test fun missingScoresShowDash() {
        val match = Json.match(JSONObject(live.replace("\"setsWon\":1,\"rank\":4", "\"setsWon\":null,\"rank\":4")))
        assertEquals("–", match.away.setsWon.scoreText)
    }

    @Test fun followsRoundTrip() {
        val follows = listOf(Follow("demo", "2026-10-14", "d1", "Away", "Home", "AWY", "HOM", 123L))
        assertEquals(follows, Json.follows(Json.followsText(follows)))
        assertEquals(emptyList<Follow>(), Json.follows("not json"))
    }

    @Test fun autoStartTiming() {
        val match = Json.match(JSONObject(live)).copy(status = "scheduled")
        val start = 1792018800_000L
        assertEquals(start - AutoStart.LEAD_MS, AutoStart.startAt(match, start - 2 * 3_600_000L))
        assertEquals(start - 60_000L, AutoStart.startAt(match, start - 60_000L))
        assertNull(AutoStart.startAt(match, start + 4 * 3_600_000L))
        assertNull(AutoStart.startAt(match.copy(status = "final"), start))
        assertEquals(42L, AutoStart.startAt(match.copy(status = "live"), 42L))
    }

    @Test fun lateNightChecksYesterday() {
        // 2:00 AM Eastern on Oct 15 still reads Oct 14's board for West Coast matches.
        assertEquals(listOf("2026-10-14", "2026-10-15"), AutoStart.dates(Instant.parse("2026-10-15T06:00:00Z")))
        assertEquals(listOf("2026-10-15"), AutoStart.dates(Instant.parse("2026-10-15T16:00:00Z")))
    }

    @Test fun serverAddressMustBeHttps() {
        assertEquals("https://example.com", ScoreApi.from(" https://example.com/ ")?.baseURL)
        assertNull(ScoreApi.from("http://example.com"))
        assertNull(ScoreApi.from("https://example.com?x=1"))
    }
}
