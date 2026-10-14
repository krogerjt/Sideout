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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krogerjt.sideout.Sideout
import com.krogerjt.sideout.Standings
import com.krogerjt.sideout.divisionName
import com.krogerjt.sideout.friendlyMessage

/** Conference standings, one division at a time, optionally narrowed to one conference. */
@Composable
fun StandingsScreen() {
    var division by rememberSaveable { mutableStateOf("d1") }
    var conference by rememberSaveable { mutableStateOf("") }
    var standings by remember { mutableStateOf<Standings?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(division) {
        val api = Sideout.api ?: run { error = "Sideout's server address is not set. Add it in Settings."; return@LaunchedEffect }
        loading = true
        try { standings = api.standings(division); error = null }
        catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (standings?.division != division) standings = null
            error = e.friendlyMessage()
        } finally { loading = false }
    }

    val shown = standings?.conferences.orEmpty().filter { conference.isEmpty() || it.name == conference }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Standings", fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Menu(divisionName(division), divisions) { division = it; conference = "" }
                Box(Modifier.weight(1f))
                Menu(conference.ifEmpty { "All conferences" }, listOf("" to "All conferences") + standings?.conferences.orEmpty().map { it.name to it.name }) { conference = it }
            }
        }
        shown.forEach { group ->
            item { SectionLabel(group.name) }
            item {
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(vertical = 6.dp)) {
                    val first = group.teams.firstOrNull()
                    val labels = when {
                        first == null -> emptyList()
                        first.conference != null || first.overall != null -> listOf("CONF", "OVR")
                        else -> first.stats.take(2).map { it.label.uppercase() }
                    }
                    StandingRow("", "TEAM", labels, header = true)
                    group.teams.forEachIndexed { index, row ->
                        HorizontalDivider(Modifier.padding(start = 44.dp), color = Palette.paper)
                        StandingRow("${index + 1}", row.school, columns(row))
                    }
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val current = standings
                when {
                    loading && current == null -> CircularProgressIndicator()
                    error != null && current == null -> Note("Couldn't load standings. $error")
                    !loading && current?.conferences?.isEmpty() == true -> Note("No final scores have been counted for this division yet. Check back once Sideout has read this season's schedule.")
                    current != null && current.updated.isNotEmpty() -> Note(
                        if (current.source == "scores") "NCAA.com hasn't posted volleyball standings, so Sideout counted these records from ${current.updated}. Conference records count matches between teams in the same conference."
                        else "Updated ${current.updated} · NCAA.com via ncaa-api"
                    )
                }
            }
        }
    }
}

/** Conference and overall records when NCAA.com labels them, otherwise its first two columns. */
private fun columns(row: Standings.Row): List<String> =
    if (row.conference != null || row.overall != null) listOf(row.conference ?: "–", row.overall ?: "–") else row.stats.take(2).map { it.value }

@Composable
private fun StandingRow(rank: String, school: String, values: List<String>, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val color = if (header) Palette.secondary else Palette.ink
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(rank, style = MaterialTheme.typography.bodySmall, color = Palette.secondary, textAlign = TextAlign.End, modifier = Modifier.width(20.dp))
        Text(school, style = style, color = color, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        values.forEach { Text(it, style = style, color = color, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(52.dp)) }
    }
}

@Composable
private fun Menu(label: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); open = false }) }
        }
    }
}
