package com.krogerjt.sideout

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.krogerjt.sideout.ui.MatchDetailScreen
import com.krogerjt.sideout.ui.Palette
import com.krogerjt.sideout.ui.ScoresScreen
import com.krogerjt.sideout.ui.SettingsScreen
import com.krogerjt.sideout.ui.SideoutTheme
import com.krogerjt.sideout.ui.StandingsScreen
import com.krogerjt.sideout.ui.TeamsScreen

class MainActivity : ComponentActivity() {
    private val model: ScoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.data?.let(model::open)
        setContent { SideoutTheme { Root(model) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(model::open)
    }
}

@Composable
private fun Root(model: ScoreViewModel) {
    var tab by rememberSaveable { mutableStateOf("scores") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val selected by model.selected.collectAsState()
    val message by model.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var pendingFollow by remember { mutableStateOf<Match?>(null) }

    // Notifications are how followed scores are shown, so Android 13+ asks the first time someone follows or stars.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingFollow?.let { model.follow(it) }
        pendingFollow = null
        if (!granted) model.message.value = "Allow notifications for Sideout to see followed scores."
    }
    val needsPermission = { Build.VERSION.SDK_INT >= 33 && !Sideout.hasNotificationPermission() }
    val askForNotifications: () -> Unit = { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val follow: (Match) -> Unit = { match ->
        if (needsPermission()) { pendingFollow = match; askForNotifications() } else model.follow(match)
    }
    val onStar: () -> Unit = { if (needsPermission()) askForNotifications() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); model.message.value = null }
    }
    // A notification tap opens its match in front of everything, including Settings.
    LaunchedEffect(selected) { if (selected != null) settingsOpen = false }

    BackHandler(enabled = selected != null || settingsOpen) {
        if (selected != null) model.selected.value = null else settingsOpen = false
    }

    Scaffold(
        containerColor = Palette.paper,
        contentColor = Palette.ink,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (selected == null && !settingsOpen) NavigationBar(containerColor = Color.White) {
                val colors = NavigationBarItemDefaults.colors(selectedIconColor = Palette.green, selectedTextColor = Palette.green, indicatorColor = Palette.lime)
                NavigationBarItem(tab == "scores", { tab = "scores" }, { Icon(Icons.Filled.Home, null) }, label = { Text("Scores") }, colors = colors)
                NavigationBarItem(tab == "standings", { tab = "standings" }, { Icon(Icons.Filled.List, null) }, label = { Text("Standings") }, colors = colors)
                NavigationBarItem(tab == "teams", { tab = "teams" }, { Icon(Icons.Filled.Star, null) }, label = { Text("Teams") }, colors = colors)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val match = selected
            when {
                match != null -> MatchDetailScreen(match, onClose = { model.selected.value = null }, onFollow = follow, onStar = onStar)
                settingsOpen -> SettingsScreen(onClose = { settingsOpen = false }, onRequestNotifications = askForNotifications)
                tab == "standings" -> StandingsScreen()
                tab == "teams" -> TeamsScreen(onStar)
                else -> ScoresScreen(model, onSettings = { settingsOpen = true }, onFollow = follow)
            }
        }
    }
}
