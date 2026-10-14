package com.krogerjt.sideout.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.krogerjt.sideout.FavoriteTeam
import com.krogerjt.sideout.Match
import com.krogerjt.sideout.Sideout
import com.krogerjt.sideout.Team
import com.krogerjt.sideout.friendlyMessage
import com.krogerjt.sideout.scoreText
import kotlinx.coroutines.delay

@Composable
fun MatchDetailScreen(initial: Match, onClose: () -> Unit, onFollow: (Match) -> Unit, onStar: () -> Unit) {
    var updated by remember(initial.id) { mutableStateOf<Match?>(null) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val match = updated ?: initial
    val follows by Sideout.follows.collectAsState()
    val messages by Sideout.followMessages.collectAsState()
    val favorites by Sideout.favorites.collectAsState()
    val following = follows.any { it.matchId == match.id }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current

    LaunchedEffect(initial.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                Sideout.api?.let { api ->
                    try { updated = api.match(initial); error = null }
                    catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.friendlyMessage() }
                }
                now = System.currentTimeMillis()
                delay(20_000)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text("Match center", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(match.statusText.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = Palette.green)
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp), horizontalArrangement = Arrangement.End) {
                        Text("SETS", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Palette.secondary)
                    }
                    BigTeamRow(match.away, match.division, onStar)
                    HorizontalDivider(Modifier.padding(start = 70.dp))
                    BigTeamRow(match.home, match.division, onStar)
                }
            }
            val starrable = match.away.slug.isNotEmpty() && match.home.slug.isNotEmpty()
            if (starrable && favorites.none { it.slug == match.home.slug || it.slug == match.away.slug }) {
                Text("Star a team to put its matches in your notifications automatically.", style = MaterialTheme.typography.bodySmall, color = Palette.secondary)
            }
            error?.let { WarningCard(it) }
            if (match.sets.isNotEmpty()) SetTable(match)
            else Text("Point-by-point scores are not available for this match yet.", color = Palette.secondary)
            if (following) {
                Text(messages[match.id] ?: "Following in your notifications")
                OutlinedButton(onClick = { Sideout.stop(match.id) }) { Text("Stop following") }
            } else if (match.isLive) {
                Button(onClick = { onFollow(match) }, modifier = Modifier.fillMaxWidth()) { Text("Follow in notifications") }
            }
            if (match.venue.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Place, contentDescription = null)
                Text(match.venue)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val age = (now / 1000 - match.checkedAt.toLong()).coerceAtLeast(0)
                if (age > 120 && match.isLive) Text("Scores may be out of date", color = Palette.warning, style = MaterialTheme.typography.bodySmall)
                Text("Feed checked ${relative(age)}", color = Palette.secondary, style = MaterialTheme.typography.bodySmall)
            }
            if (match.sourceURL.startsWith("https://")) TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(match.sourceURL)))
            }) { Text("View on NCAA.com ↗") }
        }
    }
}

private fun relative(seconds: Long) = when {
    seconds < 60 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    else -> "${seconds / 3600} hr ago"
}

@Composable
private fun BigTeamRow(team: Team, division: String, onStar: () -> Unit) {
    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(team.abbreviation, size = 44.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(team.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif, maxLines = 2)
            team.rank?.takeIf { it > 0 }?.let { Text("No. $it", style = MaterialTheme.typography.bodySmall, color = Palette.secondary) }
        }
        // The demo match's made-up teams have no slug, so they can't be saved.
        if (team.slug.isNotEmpty()) FavoriteStar(FavoriteTeam(team.slug, team.name, division), onStar)
        Text(team.setsWon.scoreText, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun SetTable(match: Match) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("SET", style = MaterialTheme.typography.bodySmall, color = Palette.secondary)
                Text(match.away.abbreviation, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(match.home.abbreviation, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            }
            match.sets.forEach { set ->
                Column(Modifier.width(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${set.number}", style = MaterialTheme.typography.bodySmall, color = Palette.secondary)
                    Text(set.away.scoreText, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    Text(set.home.scoreText, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
