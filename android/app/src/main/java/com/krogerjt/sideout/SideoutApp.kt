package com.krogerjt.sideout

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SideoutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Sideout.init(this)
        LiveNotifications.createChannels(this)
        AutoStart.schedule(this)
    }
}

/**
 * State shared by the screens, the score service and the auto-start alarms, which all run in the app's one process.
 * Favorites, followed matches and settings are saved in SharedPreferences and never leave the phone.
 */
object Sideout {
    const val FOLLOW_LIMIT = 10
    private const val FOLLOW_HOURS = 8L
    val eastern: ZoneId = ZoneId.of("America/New_York")

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    private val _favorites = MutableStateFlow<List<FavoriteTeam>>(emptyList())
    val favorites: StateFlow<List<FavoriteTeam>> = _favorites.asStateFlow()
    private val _follows = MutableStateFlow<List<Follow>>(emptyList())
    val follows: StateFlow<List<Follow>> = _follows.asStateFlow()
    /** What the score service last managed for each followed match, shown under it in the app. */
    val followMessages = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    data class Settings(
        val autoStart: Boolean = true,
        val developerMode: Boolean = false,
        val showDemo: Boolean = false,
        val serverAddress: String = "",
    )

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("sideout", Context.MODE_PRIVATE)
        _favorites.value = Json.favorites(prefs.getString("favorites", null)).sortedBy { it.name.lowercase() }
        _follows.value = Json.follows(prefs.getString("follows", null)).filter { it.expiresAt > System.currentTimeMillis() }
        _settings.value = Settings(
            autoStart = prefs.getBoolean("autoStart", true),
            developerMode = prefs.getBoolean("developerMode", false),
            showDemo = prefs.getBoolean("showDemoMatch", false),
            serverAddress = prefs.getString("serverAddress", "") ?: "",
        )
    }

    /** The server compiled into this build, or the address typed into Settings when the build has none. */
    val api: ScoreApi?
        get() = ScoreApi.from(BuildConfig.SERVER_URL) ?: ScoreApi.from(_settings.value.serverAddress)
    val serverBuiltIn get() = ScoreApi.from(BuildConfig.SERVER_URL) != null

    fun updateSettings(change: (Settings) -> Settings) {
        val before = _settings.value
        val next = change(before).let { if (!it.developerMode) it.copy(showDemo = false) else it }
        _settings.value = next
        prefs.edit {
            putBoolean("autoStart", next.autoStart)
            putBoolean("developerMode", next.developerMode)
            putBoolean("showDemoMatch", next.showDemo)
            putString("serverAddress", next.serverAddress)
        }
        if (before.autoStart != next.autoStart || before.serverAddress != next.serverAddress) AutoStart.checkNow(app)
    }

    // Favorites

    fun isFavorite(slug: String) = _favorites.value.any { it.slug == slug }
    fun isFavorite(match: Match) = isFavorite(match.home.slug) || isFavorite(match.away.slug)

    fun toggleFavorite(team: FavoriteTeam) {
        if (team.slug.isEmpty()) return
        saveFavorites(if (isFavorite(team.slug)) _favorites.value.filterNot { it.slug == team.slug } else _favorites.value + team)
    }

    /** Keeps saved names current with the scoreboard, as FavoritesStore.resolve does on iOS. */
    fun renameFavorites(teams: List<Team>) {
        val names = teams.associate { it.slug to it.name }
        val renamed = _favorites.value.map { favorite -> names[favorite.slug]?.takeIf { it.isNotEmpty() && it != favorite.name }?.let { favorite.copy(name = it) } ?: favorite }
        if (renamed != _favorites.value) saveFavorites(renamed)
    }

    fun removeFavorite(team: FavoriteTeam) = saveFavorites(_favorites.value.filterNot { it.slug == team.slug })

    private fun saveFavorites(teams: List<FavoriteTeam>) {
        val sorted = teams.sortedBy { it.name.lowercase() }
        _favorites.value = sorted
        prefs.edit { putString("favorites", Json.favoritesText(sorted)) }
        AutoStart.checkNow(app)
    }

    // Followed matches

    fun isFollowing(matchId: String) = _follows.value.any { it.matchId == matchId }

    /** Adds a match to the ongoing notifications and starts the score service. Returns an error message when it can't. */
    fun follow(match: Match): String? {
        if (isFollowing(match.id)) return null
        if (_follows.value.size >= FOLLOW_LIMIT) return "You can follow up to $FOLLOW_LIMIT matches at once."
        addFollow(Follow(match.id, match.date, match.division, match.away.name, match.home.name,
            match.away.abbreviation, match.home.abbreviation, System.currentTimeMillis() + FOLLOW_HOURS * 3_600_000))
        return LiveScoreService.start(app)
    }

    fun addFollow(follow: Follow) {
        _follows.update { current -> if (current.any { it.matchId == follow.matchId }) current else current + follow }
        followMessages.update { it + (follow.matchId to "Connecting live updates…") }
        markLaunched(follow.matchId)
        saveFollows()
    }

    fun stop(matchId: String) = removeFollows(setOf(matchId))

    fun removeFollows(ids: Set<String>) {
        if (ids.isEmpty()) return
        _follows.update { current -> current.filterNot { it.matchId in ids } }
        followMessages.update { it - ids }
        ids.forEach { LiveNotifications.cancelLive(app, it) }
        saveFollows()
    }

    private fun saveFollows() = prefs.edit { putString("follows", Json.followsText(_follows.value)) }

    // A match is started automatically at most once, so stopping or swiping away an auto-started one keeps it gone, as on iOS.

    fun launched(matchId: String): Boolean = launches().has(matchId)

    private fun markLaunched(matchId: String) {
        val launches = launches()
        val cutoff = System.currentTimeMillis() - 3 * 86_400_000L
        launches.keys().asSequence().toList().forEach { if (launches.optLong(it) < cutoff) launches.remove(it) }
        launches.put(matchId, System.currentTimeMillis())
        prefs.edit { putString("launches", launches.toString()) }
    }

    private fun launches() = runCatching { JSONObject(prefs.getString("launches", null) ?: "{}") }.getOrDefault(JSONObject())

    // Dates

    /** Scoreboards use Eastern Time dates, YYYY-MM-DD. */
    fun easternDate(instant: Instant = Instant.now()): String = instant.atZone(eastern).toLocalDate().toString()
    fun easternToday(): LocalDate = LocalDate.now(eastern)

    fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(app, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(): Boolean =
        app.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}
