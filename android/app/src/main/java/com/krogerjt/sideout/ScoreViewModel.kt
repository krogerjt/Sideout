package com.krogerjt.sideout

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The scoreboard's state, like ScoreStore on iOS. Favorites and followed matches live in [Sideout]. */
class ScoreViewModel : ViewModel() {
    data class Board(
        val date: LocalDate = Sideout.easternToday(),
        val division: String = "d1",
        val matches: List<Match> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    ) {
        val queryKey get() = "$date/$division"
    }

    private val _board = MutableStateFlow(Board())
    val board: StateFlow<Board> = _board.asStateFlow()
    /** The match open in the match center, if any. */
    val selected = MutableStateFlow<Match?>(null)
    val message = MutableStateFlow<String?>(null)
    private var generation = 0

    fun setDate(date: LocalDate) = _board.update { if (it.date == date) it else it.copy(date = date, matches = emptyList(), error = null) }
    fun setDivision(division: String) = _board.update { if (it.division == division) it else it.copy(division = division, matches = emptyList(), error = null) }

    suspend fun refresh() {
        val api = Sideout.api ?: run { _board.update { it.copy(error = "Sideout's server address is not set. Add it in Settings.") }; return }
        val request = ++generation
        val key = _board.value.queryKey
        _board.update { it.copy(loading = true) }
        try {
            val matches = api.matches(_board.value.date.toString(), _board.value.division)
            if (request != generation || key != _board.value.queryKey) return
            _board.update { it.copy(matches = matches, error = null) }
            Sideout.renameFavorites(matches.flatMap { listOf(it.home, it.away) })
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (request == generation && key == _board.value.queryKey) _board.update { it.copy(error = error.friendlyMessage()) }
        } finally {
            if (request == generation) _board.update { it.copy(loading = false) }
        }
    }

    fun openDemo() = viewModelScope.launch {
        val api = Sideout.api ?: return@launch
        try { selected.value = api.match(ScoreApi.DEMO_MATCH_ID, Sideout.easternDate(), "d1") }
        catch (error: Exception) { message.value = error.friendlyMessage() }
    }

    /** Opens a sideout://match/<id>?date=&division= link from a score notification. */
    fun open(uri: Uri) {
        if (uri.scheme != "sideout" || uri.host != "match") return
        val id = uri.lastPathSegment ?: return
        if (id != ScoreApi.DEMO_MATCH_ID && !id.all(Char::isDigit)) return
        _board.value.matches.firstOrNull { it.id == id }?.let { selected.value = it; return }
        val date = uri.getQueryParameter("date") ?: return
        val division = uri.getQueryParameter("division") ?: return
        viewModelScope.launch {
            val api = Sideout.api ?: return@launch
            try { selected.value = api.match(id, date, division) }
            catch (error: Exception) { message.value = error.friendlyMessage() }
        }
    }

    fun follow(match: Match) = viewModelScope.launch {
        val api = Sideout.api ?: return@launch
        try {
            val current = api.match(match)
            if (!current.isLive) { message.value = "This match is no longer in progress."; return@launch }
            Sideout.follow(current)?.let { message.value = it }
        } catch (error: Exception) { message.value = error.friendlyMessage() }
    }
}
