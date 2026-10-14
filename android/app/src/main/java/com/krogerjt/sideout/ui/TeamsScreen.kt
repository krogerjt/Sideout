package com.krogerjt.sideout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krogerjt.sideout.FavoriteTeam
import com.krogerjt.sideout.Sideout
import com.krogerjt.sideout.TeamEntry
import com.krogerjt.sideout.divisionName
import com.krogerjt.sideout.friendlyMessage

/** Every team on this season's scoreboards, for finding and starring favorites without waiting for a match day. */
@Composable
fun TeamsScreen(onStar: () -> Unit) {
    var division by rememberSaveable { mutableStateOf("d1") }
    var search by rememberSaveable { mutableStateOf("") }
    var teams by remember { mutableStateOf<List<TeamEntry>>(emptyList()) }
    var complete by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val favorites by Sideout.favorites.collectAsState()

    LaunchedEffect(division) {
        val api = Sideout.api ?: run { error = "Sideout's server address is not set. Add it in Settings."; return@LaunchedEffect }
        loading = true
        try {
            val list = api.teams(division)
            teams = list.teams; complete = list.complete; error = null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (teams.firstOrNull()?.division != division) teams = emptyList()
            error = e.friendlyMessage()
        } finally { loading = false }
    }

    val visible = if (search.isEmpty()) teams else teams.filter { "${it.name} ${it.abbreviation}".contains(search, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Teams", fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif) }
        item {
            TextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("Find a team") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") } },
                shape = RoundedCornerShape(13.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (search.isEmpty() && favorites.isNotEmpty()) {
            item { SectionLabel("Your favorites") }
            items(favorites, key = { "fav-${it.slug}" }) { team ->
                TeamRow(team, teams.firstOrNull { it.slug == team.slug }?.abbreviation ?: team.name, showDivision = true, onStar = onStar)
            }
        }
        item { Segmented(divisions, division, { division = it }, Modifier.padding(vertical = 8.dp)) }
        items(visible) { team ->
            TeamRow(FavoriteTeam(team.slug, team.name, team.division), team.abbreviation, onStar = onStar)
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                when {
                    loading && teams.isEmpty() -> CircularProgressIndicator()
                    error != null && teams.isEmpty() -> Note("Couldn't load teams. $error")
                    search.isNotEmpty() && visible.isEmpty() -> Note("No teams match “$search”.")
                    !loading && teams.isEmpty() -> Note("Teams appear once this season's schedule has been read. Check back shortly.")
                    !complete -> Note("Sideout is still reading this season's schedule, so a few teams may be missing for now.")
                    else -> Note("Starred teams' matches go in your notifications 30 minutes before first serve.")
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        color = Palette.secondary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.secondary, textAlign = TextAlign.Center)
}

@Composable
private fun TeamRow(team: FavoriteTeam, abbreviation: String, showDivision: Boolean = false, onStar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TeamBadge(abbreviation, size = 34.dp)
        Column(Modifier.weight(1f)) {
            Text(team.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (showDivision) Text(divisionName(team.division), style = MaterialTheme.typography.bodySmall, color = Palette.secondary)
        }
        FavoriteStar(team, onStar)
    }
}
