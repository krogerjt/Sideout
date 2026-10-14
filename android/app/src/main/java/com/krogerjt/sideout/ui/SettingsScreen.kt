package com.krogerjt.sideout.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.krogerjt.sideout.BuildConfig
import com.krogerjt.sideout.LiveNotifications
import com.krogerjt.sideout.ScoreApi
import com.krogerjt.sideout.Sideout

@SuppressLint("BatteryLife")
@Composable
fun SettingsScreen(onClose: () -> Unit, onRequestNotifications: () -> Unit) {
    val context = LocalContext.current
    val settings by Sideout.settings.collectAsState()
    val favorites by Sideout.favorites.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notificationsOn by remember { mutableStateOf(true) }
    var promoted by remember { mutableStateOf<Boolean?>(null) }
    var unrestricted by remember { mutableStateOf(true) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var showDeveloper by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(settings.serverAddress) }

    // Re-read Android's settings each time the person comes back from changing them.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            notificationsOn = Sideout.hasNotificationPermission() && Sideout.notificationsEnabled()
            promoted = LiveNotifications.canPromote(context)
            unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (showDeveloper) showDeveloper = false else onClose() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text(if (showDeveloper) "Developer" else "Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (showDeveloper) {
                Group(footer = "Adds a simulated live match to the scoreboard for trying live notifications when no real match is on.") {
                    SwitchRow("Show demo match", settings.showDemo) { on -> Sideout.updateSettings { it.copy(showDemo = on) } }
                }
                Group(footer = "Leave empty to use the server built into this app${if (Sideout.serverBuiltIn) "" else " (this build has none)"}.") {
                    ServerField(address, { address = it }) { Sideout.updateSettings { s -> s.copy(serverAddress = address.trim()) } }
                }
                Group(footer = "Tap the version seven times to bring them back.") {
                    Text("Hide developer settings", color = Color(0xFFB3261E), modifier = Modifier.fillMaxWidth().clickable {
                        Sideout.updateSettings { it.copy(developerMode = false) }
                        showDeveloper = false
                    }.padding(vertical = 6.dp))
                }
                return@Column
            }

            Group("Live updates", "Followed matches stay in your notifications and on the lock screen until they end. Sideout checks the score every 20 seconds while a match you follow is on.") {
                Text(if (notificationsOn) "✓ Notifications are on" else "Notifications are off, so followed scores can't be shown.")
                if (!notificationsOn) Link("Turn on notifications") {
                    if (!Sideout.hasNotificationPermission()) onRequestNotifications()
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
                when (promoted) {
                    true -> Text("✓ Scores show as Live Updates in the status bar")
                    false -> {
                        Text("Live Updates are off for Sideout, so scores appear as ordinary notifications.")
                        Link("Allow Live Updates") {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }
                    }
                    null -> {}
                }
                if (unrestricted) Text("✓ Background updates are unrestricted")
                else {
                    Text("Android may pause score updates while the screen is off.")
                    Link("Allow background updates") {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    }
                }
                Link("Notification settings") {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }

            Group("Favorite teams", if (favorites.isEmpty()) "Star teams in the Teams tab, or next to a team's name inside a match, to save them on this phone."
                else "When on, your favorite teams' matches appear in your notifications 30 minutes before first serve, even if Sideout is closed.") {
                SwitchRow("Start automatically", settings.autoStart) { on -> Sideout.updateSettings { it.copy(autoStart = on) } }
                if (settings.autoStart) Text(
                    when {
                        favorites.isEmpty() -> "Star a team to start its matches automatically."
                        !notificationsOn -> "Turn on notifications for Sideout to see them."
                        else -> "On for ${favorites.size} ${if (favorites.size == 1) "team" else "teams"}. Scoreboards appear 30 minutes before first serve."
                    },
                    style = MaterialTheme.typography.bodySmall, color = Palette.secondary,
                )
                favorites.forEach { team ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(team.name, modifier = Modifier.weight(1f))
                        Text(team.division.uppercase(), style = MaterialTheme.typography.bodySmall, color = Palette.secondary)
                        IconButton(onClick = { Sideout.removeFavorite(team) }) { Icon(Icons.Filled.Clear, contentDescription = "Remove ${team.name}") }
                    }
                }
            }

            if (Sideout.api == null) Group("Server", "This build has no server address. Enter the https address of your Sideout server.") {
                ServerField(address, { address = it }) { Sideout.updateSettings { s -> s.copy(serverAddress = address.trim()) } }
            }

            Group("About") {
                Text("Sideout · NCAA women’s volleyball")
                Text("Independent app. Not affiliated with the NCAA. Scores are supplied through the community ncaa-api service; availability varies.",
                    style = MaterialTheme.typography.bodySmall, color = Palette.secondary)
                Sideout.api?.let { api -> Link("Privacy policy") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(api.privacyPolicyURL))) } }
                // Tapping the version seven times unlocks developer settings, so family members never stumble on them.
                Row(Modifier.fillMaxWidth().clickable {
                    if (settings.developerMode) return@clickable
                    versionTaps += 1
                    if (versionTaps >= 7) Sideout.updateSettings { it.copy(developerMode = true) }
                }.padding(vertical = 6.dp)) {
                    Text("Version", modifier = Modifier.weight(1f))
                    Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Palette.secondary)
                }
                if (settings.developerMode) Link("Developer settings") { showDeveloper = true }
            }
        }
    }
}

@Composable
private fun Group(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let { SectionLabel(it) }
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.secondary, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(text, color = Palette.green, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp))
}

@Composable
private fun ServerField(address: String, onChange: (String) -> Unit, onSave: () -> Unit) {
    val valid = address.isBlank() || ScoreApi.from(address) != null
    OutlinedTextField(value = address, onValueChange = onChange, singleLine = true, label = { Text("Server address") },
        placeholder = { Text("https://your-server.example") }, isError = !valid, modifier = Modifier.fillMaxWidth())
    Link(if (valid) "Save address" else "Use an https:// address") { if (valid) onSave() }
}
