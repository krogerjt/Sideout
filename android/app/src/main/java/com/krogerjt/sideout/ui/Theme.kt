package com.krogerjt.sideout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krogerjt.sideout.FavoriteTeam
import com.krogerjt.sideout.Sideout

/** The iOS app's palette (Palette in SideoutApp.swift). */
object Palette {
    val paper = Color(0xFFF5F2E8)
    val ink = Color(0xFF1F3329)
    val green = Color(0xFF305C42)
    val lime = Color(0xFFD1F073)
    val star = Color(0xFFEDA81A)
    val secondary = Color(0xFF6B7A70)
    val warning = Color(0xFFC46A00)
}

@Composable
fun SideoutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.green,
            onPrimary = Color.White,
            secondary = Palette.lime,
            onSecondary = Palette.ink,
            background = Palette.paper,
            onBackground = Palette.ink,
            surface = Palette.paper,
            onSurface = Palette.ink,
            surfaceContainer = Color.White,
            secondaryContainer = Palette.lime,
            onSecondaryContainer = Palette.ink,
        ),
        content = content,
    )
}

/** A team's short-name badge, shared by the scoreboard, match center, and team list. */
@Composable
fun TeamBadge(abbreviation: String, size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.29f)).background(Palette.paper),
        contentAlignment = Alignment.Center,
    ) {
        Text(abbreviation.take(3), fontSize = (size.value * 0.27f).sp, fontWeight = FontWeight.Black, color = Palette.ink)
    }
}

/** The star that saves a team as a favorite, shown right beside the team's name. */
@Composable
fun FavoriteStar(team: FavoriteTeam, onStar: () -> Unit = {}) {
    val favorites by Sideout.favorites.collectAsState()
    val saved = favorites.any { it.slug == team.slug }
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            if (!saved) onStar()
            Sideout.toggleFavorite(team)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        },
        modifier = Modifier.semantics { contentDescription = if (saved) "Remove ${team.name} from favorites" else "Add ${team.name} to favorites" },
    ) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = if (saved) Palette.star else Palette.secondary.copy(alpha = 0.35f))
    }
}

@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier) {
    Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.warning)
            Text(text, color = Palette.warning, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A row of mutually exclusive choices, like the iOS segmented pickers. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x14000000)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) Color.White else Color.Transparent)
                    .clickable { onSelect(value) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = Palette.ink, maxLines = 1)
            }
        }
    }
}

val divisions = listOf("d1" to "Division I", "d2" to "Division II", "d3" to "Division III")
