package com.krogerjt.sideout.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.krogerjt.sideout.Match
import com.krogerjt.sideout.ScoreViewModel
import com.krogerjt.sideout.Sideout
import com.krogerjt.sideout.Team
import com.krogerjt.sideout.divisionName
import com.krogerjt.sideout.scoreText
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val statusOrder = mapOf("live" to 0, "scheduled" to 1, "final" to 2)

@Composable
fun ScoresScreen(model: ScoreViewModel, onSettings: () -> Unit, onFollow: (Match) -> Unit) {
    val board by model.board.collectAsState()
    val follows by Sideout.follows.collectAsState()
    val messages by Sideout.followMessages.collectAsState()
    val favorites by Sideout.favorites.collectAsState()
    val settings by Sideout.settings.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Refresh every 20 seconds while the scoreboard is on screen, as the iOS app does in the foreground.
    LaunchedEffect(board.queryKey) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                model.refresh()
                delay(20_000)
            }
        }
    }

    val visible = board.matches.filter { match ->
        (filter != "live" || match.isLive) &&
            (filter != "favorites" || favorites.any { it.slug == match.home.slug || it.slug == match.away.slug }) &&
            (search.isEmpty() || "${match.home.name} ${match.away.name}".contains(search, ignoreCase = true))
    }.sortedWith(compareBy<Match>({ statusOrder[it.status] ?: 3 }, { it.startTime ?: 0.0 }, { it.id }))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Header(onSettings) }
        if (follows.isNotEmpty()) item {
            Surface(color = Palette.green, contentColor = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("IN YOUR NOTIFICATIONS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    follows.forEach { follow ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${follow.awayAbbreviation} · ${follow.homeAbbreviation}", fontWeight = FontWeight.Bold)
                                Text(messages[follow.matchId] ?: "Following", style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { Sideout.stop(follow.matchId) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Stop") }
                        }
                    }
                }
            }
        }
        item { Controls(board, model, search, { search = it }, filter, { filter = it }) }
        board.error?.let { item { WarningCard(it) } }
        if (board.loading && board.matches.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("Checking the court…", color = Palette.secondary)
            }
        } else if (visible.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (search.isEmpty()) "No matches here" else "No matching teams", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (filter == "favorites") "Star a team in the Teams tab, or next to its name inside a match. Its matches will appear in your notifications automatically."
                    else "Try another date or filter. Schedules use Eastern Time.",
                    color = Palette.secondary, textAlign = TextAlign.Center,
                )
            }
        } else items(visible, key = { it.id }) { match ->
            MatchCard(match, following = follows.any { it.matchId == match.id }, open = { model.selected.value = match }, follow = { onFollow(match) })
        }
        if (settings.showDemo) item { DemoCard { model.openDemo() } }
        item {
            Text(
                "NCAA women’s volleyball · Scores via ncaa-api\nUpdate timing and coverage vary by match.",
                style = MaterialTheme.typography.bodySmall, color = Palette.secondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("COURTSIDE, ANYWHERE", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.8.sp)
            Text("Sideout", fontSize = 46.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif, letterSpacing = (-2).sp)
            Text("Every set. A little closer.", style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
        }
        Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.8f)) {
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
    }
}

@Composable
private fun Controls(
    board: ScoreViewModel.Board, model: ScoreViewModel,
    search: String, onSearch: (String) -> Unit, filter: String, onFilter: (String) -> Unit,
) {
    val context = LocalContext.current
    var divisionMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MATCH DAY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { model.setDate(board.date.minusDays(1)) }) { Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous day") }
                    Row(
                        Modifier.clickable {
                            DatePickerDialog(context, { _, year, month, day -> model.setDate(LocalDate.of(year, month + 1, day)) },
                                board.date.year, board.date.monthValue - 1, board.date.dayOfMonth).show()
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.DateRange, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(board.date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { model.setDate(board.date.plusDays(1)) }) { Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next day") }
                }
            }
            Box {
                TextButton(onClick = { divisionMenu = true }) {
                    Text(divisionName(board.division))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = divisionMenu, onDismissRequest = { divisionMenu = false }) {
                    divisions.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { model.setDivision(value); divisionMenu = false })
                    }
                }
            }
        }
        TextField(
            value = search, onValueChange = onSearch, singleLine = true,
            placeholder = { Text("Find your team") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { onSearch("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") } },
            shape = RoundedCornerShape(13.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Segmented(listOf("all" to "All matches", "live" to "Live", "favorites" to "Favorites"), filter, onFilter)
    }
}

@Composable
fun MatchCard(match: Match, following: Boolean, open: () -> Unit, follow: () -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.clickable(onClick = open), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    Text(
                        (if (match.isLive) "● " else "") + match.statusText.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, color = if (match.isLive) Palette.green else Palette.secondary, modifier = Modifier.weight(1f),
                    )
                    Text("SETS", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                }
                TeamRow(match.away)
                TeamRow(match.home)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when {
                    following -> TextButton(onClick = { Sideout.stop(match.id) }) { Text("Stop following") }
                    match.isLive -> TextButton(onClick = follow) { Text("Follow in notifications") }
                    else -> TextButton(onClick = open) { Text("Match details ↗") }
                }
            }
        }
    }
}

@Composable
private fun TeamRow(team: Team) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TeamBadge(team.abbreviation)
        team.rank?.takeIf { it > 0 }?.let { Text("$it", style = MaterialTheme.typography.bodySmall, color = Palette.secondary) }
        Text(team.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(team.setsWon.scoreText, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DemoCard(open: () -> Unit) {
    Surface(color = Palette.green, contentColor = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Demo match", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif)
            Text("Try a simulated match and put its live scoreboard in your notifications.", color = Color.White.copy(alpha = 0.8f))
            Button(onClick = open, colors = ButtonDefaults.buttonColors(containerColor = Palette.lime, contentColor = Palette.ink)) {
                Text("Try a demo match →")
            }
        }
    }
}
