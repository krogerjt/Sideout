package com.krogerjt.sideout

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Follows favorite teams' matches automatically from 30 minutes before first serve, as the server's push-to-start does on iOS.
 * A periodic check reads today's scoreboards and sets an exact alarm for each favorite's match; the alarm starts the score service.
 */
object AutoStart {
    const val LEAD_MS = 30 * 60 * 1000L
    private const val PERIODIC = "autostart-check"
    private const val NOW = "autostart-check-now"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AutoStartWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        checkNow(context)
    }

    /** Re-reads the schedule soon, after favorites or the setting change. */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoStartWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    /** Today in Eastern Time, plus yesterday before 6 AM so late West Coast and Hawaii matches are not missed. */
    fun dates(now: Instant = Instant.now()): List<String> {
        val eastern = now.atZone(Sideout.eastern)
        val today = eastern.toLocalDate()
        return if (eastern.hour < 6) listOf(today.minusDays(1).toString(), today.toString()) else listOf(today.toString())
    }

    /** When to start following: now for a live match, 30 minutes before a scheduled one, or never. Mirrors autostartDue on the server. */
    fun startAt(match: Match, now: Long = System.currentTimeMillis()): Long? {
        if (match.isLive) return now
        val start = match.startTime?.let { (it * 1000).toLong() } ?: return null
        if (match.status != "scheduled" || now >= start + 3 * 3_600_000L) return null
        return maxOf(now, start - LEAD_MS)
    }

    suspend fun check(context: Context) {
        val settings = Sideout.settings.value
        val favorites = Sideout.favorites.value
        val api = Sideout.api ?: return
        if (!settings.autoStart || favorites.isEmpty()) return
        for (date in dates()) for (division in favorites.map { it.division }.toSet()) {
            val slugs = favorites.filter { it.division == division }.map { it.slug }.toSet()
            val board = runCatching { api.matches(date, division) }.getOrNull() ?: continue
            for (match in board) {
                if (match.home.slug !in slugs && match.away.slug !in slugs) continue
                if (Sideout.launched(match.id) || Sideout.isFollowing(match.id)) continue
                val at = startAt(match) ?: continue
                setAlarm(context, match, at)
            }
        }
    }

    private fun setAlarm(context: Context, match: Match, at: Long) {
        val intent = Intent(context, AutoStartReceiver::class.java)
            .putExtra("matchId", match.id).putExtra("date", match.date).putExtra("division", match.division)
            .putExtra("awayName", match.away.name).putExtra("homeName", match.home.name)
            .putExtra("awayAbbreviation", match.away.abbreviation).putExtra("homeAbbreviation", match.home.abbreviation)
        val pending = PendingIntent.getBroadcast(context, LiveNotifications.liveId(match.id), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        // An exact alarm is what lets a closed app start a foreground service on Android 12 and later.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
}

class AutoStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Sideout.init(applicationContext)
        AutoStart.check(applicationContext)
        return Result.success()
    }
}

class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sideout.init(context)
        val id = intent.getStringExtra("matchId") ?: return
        if (!Sideout.settings.value.autoStart || Sideout.launched(id) || Sideout.isFollowing(id)) return
        if (Sideout.follows.value.size >= Sideout.FOLLOW_LIMIT) return
        val follow = Follow(id, intent.getStringExtra("date") ?: return, intent.getStringExtra("division") ?: return,
            intent.getStringExtra("awayName") ?: "", intent.getStringExtra("homeName") ?: "",
            intent.getStringExtra("awayAbbreviation") ?: "", intent.getStringExtra("homeAbbreviation") ?: "",
            System.currentTimeMillis() + 8 * 3_600_000L)
        // The score service checks the match right away and ends the follow if it was cancelled or is already over.
        Sideout.addFollow(follow)
        if (LiveScoreService.start(context) != null) {
            Sideout.stop(id)
            LiveNotifications.postStarting(context, follow)
        }
    }
}
