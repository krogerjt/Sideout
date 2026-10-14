package com.krogerjt.sideout

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

class ApiException(message: String, val status: Int? = null) : IOException(message)

/**
 * Reads scores from the Sideout server's public endpoints. Android needs nothing else from the server:
 * followed matches are kept current by this phone's own score service, so there are no push tokens or install credentials.
 */
class ScoreApi(val baseURL: String) {
    companion object {
        const val DEMO_MATCH_ID = "demo"

        /** Accepts an https address with no query, fragment or login, like the iOS app's ScoreAPI(address:). */
        fun from(address: String): ScoreApi? {
            val trimmed = address.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
            return ScoreApi(trimmed)
        }
    }

    val privacyPolicyURL get() = "$baseURL/privacy"

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val queryText = query.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val connection = URL("$baseURL/$path" + if (queryText.isEmpty()) "" else "?$queryText").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (status !in 200..299) throw ApiException(Json.error(text) ?: "The score server couldn't complete this request.", status)
            text
        } finally {
            connection.disconnect()
        }
    }

    suspend fun pushEnabled(): Boolean = JSONObject(get("v1/config")).optBoolean("pushEnabled")
    suspend fun matches(date: String, division: String): List<Match> = Json.matches(get("v1/matches", mapOf("date" to date, "division" to division)))
    suspend fun match(id: String, date: String, division: String): Match =
        Json.match(JSONObject(get("v1/matches/$id", mapOf("date" to date, "division" to division))))
    suspend fun match(match: Match): Match = match(match.id, match.date, match.division)
    suspend fun teams(division: String): TeamList = Json.teams(get("v1/teams", mapOf("division" to division)))
    suspend fun standings(division: String): Standings = Json.standings(get("v1/standings", mapOf("division" to division)))
}

/** Turns network failures into the short messages the iOS app shows. */
fun Throwable.friendlyMessage(): String = when (this) {
    is ApiException -> message ?: "The score server couldn't complete this request."
    is java.net.UnknownHostException -> "Couldn't find Sideout's server. Check your internet connection."
    is java.net.SocketTimeoutException -> "Sideout's server took too long to answer."
    is javax.net.ssl.SSLException -> "A secure connection to Sideout's server couldn't be made."
    is IOException -> "Couldn't reach Sideout's server. ${message ?: ""}".trim()
    else -> message ?: "Something went wrong."
}
