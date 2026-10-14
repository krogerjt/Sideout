package com.krogerjt.sideout

import org.json.JSONArray
import org.json.JSONObject

/** The same match shape the server sends the iPhone app (see ios/Shared/Match.swift). */
data class Team(
    val name: String,
    val abbreviation: String,
    val slug: String,
    val setsWon: Int?,
    val rank: Int?,
)

data class SetScore(val number: Int, val home: Int?, val away: Int?)

data class Match(
    val id: String,
    val date: String,
    val division: String,
    val away: Team,
    val home: Team,
    val status: String,
    val statusText: String,
    val startTime: Double?,
    val sets: List<SetScore>,
    val venue: String,
    val checkedAt: Double,
    val sourceURL: String,
) {
    val isLive get() = status == "live"
    val isOver get() = status in setOf("final", "cancelled", "postponed")

    /** The set being played, read from status text such as "3rd" or "Set 3", as the server's contentState does. */
    val currentSet: SetScore?
        get() {
            if (!isLive) return null
            val number = statusText.firstOrNull { it in '1'..'5' }?.digitToInt() ?: return null
            return sets.firstOrNull { it.number == number }
        }
}

/** A team seen on this season's scoreboards, listed for picking favorites. */
data class TeamEntry(val slug: String, val name: String, val abbreviation: String, val division: String)

data class TeamList(val teams: List<TeamEntry>, val complete: Boolean)

data class Standings(val division: String, val updated: String, val source: String?, val conferences: List<Conference>) {
    data class Stat(val label: String, val value: String)
    data class Row(val school: String, val conference: String?, val overall: String?, val stats: List<Stat>)
    data class Conference(val name: String, val teams: List<Row>)
}

/** A favorite team, saved on this phone by the slug the server matches scoreboards on. */
data class FavoriteTeam(val slug: String, val name: String, val division: String)

/** A match this phone is keeping in an ongoing notification. */
data class Follow(
    val matchId: String,
    val date: String,
    val division: String,
    val awayName: String,
    val homeName: String,
    val awayAbbreviation: String,
    val homeAbbreviation: String,
    val expiresAt: Long,
)

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeUnless { it.isNaN() }
private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else optString(key)
private inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(transform) }

object Json {
    fun team(o: JSONObject) = Team(
        name = o.optString("name"),
        abbreviation = o.optString("abbreviation").ifEmpty { o.optString("name") },
        slug = o.optString("slug"),
        setsWon = o.optIntOrNull("setsWon"),
        rank = o.optIntOrNull("rank"),
    )

    fun match(o: JSONObject) = Match(
        id = o.getString("id"),
        date = o.getString("date"),
        division = o.getString("division"),
        away = team(o.getJSONObject("away")),
        home = team(o.getJSONObject("home")),
        status = o.optString("status"),
        statusText = o.optString("statusText"),
        startTime = o.optDoubleOrNull("startTime"),
        sets = o.optJSONArray("sets").mapObjects { SetScore(it.optInt("number"), it.optIntOrNull("home"), it.optIntOrNull("away")) },
        venue = o.optString("venue"),
        checkedAt = o.optDouble("checkedAt", 0.0),
        sourceURL = o.optString("sourceURL"),
    )

    fun matches(text: String) = JSONObject(text).optJSONArray("matches").mapObjects(::match)

    fun teams(text: String): TeamList {
        val o = JSONObject(text)
        return TeamList(
            o.optJSONArray("teams").mapObjects { TeamEntry(it.optString("slug"), it.optString("name"), it.optString("abbreviation"), it.optString("division")) },
            o.optBoolean("complete", true),
        )
    }

    fun standings(text: String): Standings {
        val o = JSONObject(text)
        return Standings(
            division = o.optString("division"),
            updated = o.optString("updated"),
            source = o.optStringOrNull("source"),
            conferences = o.optJSONArray("conferences").mapObjects { c ->
                Standings.Conference(c.optString("name"), c.optJSONArray("teams").mapObjects { r ->
                    Standings.Row(
                        r.optString("school"), r.optStringOrNull("conference"), r.optStringOrNull("overall"),
                        r.optJSONArray("stats").mapObjects { Standings.Stat(it.optString("label"), it.optString("value")) },
                    )
                })
            },
        )
    }

    fun favorites(text: String?): List<FavoriteTeam> = runCatching {
        JSONArray(text ?: "[]").mapObjects { FavoriteTeam(it.getString("slug"), it.getString("name"), it.getString("division")) }
    }.getOrDefault(emptyList())

    fun favoritesText(teams: List<FavoriteTeam>) = JSONArray(teams.map {
        JSONObject().put("slug", it.slug).put("name", it.name).put("division", it.division)
    }).toString()

    fun follows(text: String?): List<Follow> = runCatching {
        JSONArray(text ?: "[]").mapObjects {
            Follow(it.getString("matchId"), it.getString("date"), it.getString("division"), it.getString("awayName"), it.getString("homeName"),
                it.getString("awayAbbreviation"), it.getString("homeAbbreviation"), it.getLong("expiresAt"))
        }
    }.getOrDefault(emptyList())

    fun followsText(follows: List<Follow>) = JSONArray(follows.map {
        JSONObject().put("matchId", it.matchId).put("date", it.date).put("division", it.division)
            .put("awayName", it.awayName).put("homeName", it.homeName)
            .put("awayAbbreviation", it.awayAbbreviation).put("homeAbbreviation", it.homeAbbreviation).put("expiresAt", it.expiresAt)
    }).toString()

    fun error(text: String): String? = runCatching { JSONObject(text).optStringOrNull("error") }.getOrNull()
}

/** "–" for a score the feed hasn't reported, like the iOS app's scoreText. */
val Int?.scoreText: String get() = this?.toString() ?: "–"

fun divisionName(division: String) = mapOf("d1" to "Division I", "d2" to "Division II", "d3" to "Division III")[division] ?: division.uppercase()
