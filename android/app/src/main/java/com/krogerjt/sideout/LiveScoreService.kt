package com.krogerjt.sideout

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps followed matches' notifications current by asking the Sideout server every 20 seconds, the same pace the server
 * polls for iPhones. It runs only while at least one followed match is in progress, then stops itself.
 */
class LiveScoreService : Service() {
    companion object {
        private const val POLL_MS = 20_000L
        private const val STALE_SECONDS = 120

        /** Starts the service from the app or an alarm. Returns a message when Android refuses. */
        fun start(context: Context): String? = try {
            ContextCompat.startForegroundService(context, Intent(context, LiveScoreService::class.java))
            null
        } catch (error: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when started from the background without an exemption.
            "Android didn't allow Sideout to start live updates in the background."
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var lastStartId = 0
    private val latest = mutableMapOf<String, Match>()
    private val posted = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Sideout.init(this)
        lastStartId = startId
        val follows = Sideout.follows.value
        // Android requires the foreground notification within a few seconds of starting, before the first score arrives.
        val first = follows.firstOrNull()
        if (first == null) {
            enterForeground(1, LiveNotifications.live(this, Follow("none", "", "", "Sideout", "", "", "", 0), null, false))
            finish()
            return START_NOT_STICKY
        }
        enterForeground(LiveNotifications.liveId(first.matchId), LiveNotifications.live(this, first, latest[first.matchId], false))
        if (loop == null) loop = scope.launch {
            Sideout.follows.collectLatest { current ->
                if (current.isEmpty()) { finish(); return@collectLatest }
                while (true) {
                    poll(current)
                    delay(POLL_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun enterForeground(id: Int, notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, id, notification, type)
    }

    @SuppressLint("MissingPermission")
    private suspend fun poll(follows: List<Follow>) {
        val api = Sideout.api
        val now = System.currentTimeMillis()
        val finished = mutableSetOf<String>()
        val manager = NotificationManagerCompat.from(this)
        for (follow in follows) {
            if (follow.expiresAt <= now) { finished += follow.matchId; continue }
            if (api != null) try {
                val match = api.match(follow.matchId, follow.date, follow.division)
                latest[follow.matchId] = match
                if (match.isOver) {
                    finished += follow.matchId
                    LiveNotifications.postResult(this, follow, match)
                    continue
                }
                Sideout.followMessages.update { it + (follow.matchId to "Following in your notifications") }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                Sideout.followMessages.update { it + (follow.matchId to "Updates not connected: ${error.friendlyMessage()} Retrying.") }
            }
        }
        val live = follows.filter { it.matchId !in finished }
        // The first live match carries the service's own notification; the rest are ordinary ongoing notifications.
        live.forEachIndexed { index, follow ->
            val match = latest[follow.matchId]
            val stale = match == null || now / 1000.0 - match.checkedAt > STALE_SECONDS
            val notification = LiveNotifications.live(this, follow, match, stale && match != null)
            val id = LiveNotifications.liveId(follow.matchId)
            if (index == 0) enterForeground(id, notification)
            else if (Sideout.hasNotificationPermission()) manager.notify(id, notification)
            posted += follow.matchId
        }
        // Clear notifications for matches that ended or were stopped, after the foreground has moved off them.
        val keep = live.map { it.matchId }.toSet()
        posted.filter { it !in keep }.forEach { LiveNotifications.cancelLive(this, it); posted -= it; latest -= it }
        if (finished.isNotEmpty()) Sideout.removeFollows(finished)
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        posted.forEach { LiveNotifications.cancelLive(this, it) }
        posted.clear()
        // A newer start (a match followed just now) keeps the service running; the loop picks that match up.
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Handles "Stop following" and swiping a score notification away, which stops following like dismissing a Live Activity. */
class FollowActionReceiver : BroadcastReceiver() {
    companion object {
        const val STOP = "com.krogerjt.sideout.STOP"
        const val DISMISSED = "com.krogerjt.sideout.DISMISSED"
        const val MATCH_ID = "matchId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Sideout.init(context)
        val id = intent.getStringExtra(MATCH_ID) ?: return
        if (intent.action == STOP || intent.action == DISMISSED) Sideout.stop(id)
    }
}
