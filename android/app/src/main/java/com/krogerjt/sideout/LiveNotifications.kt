package com.krogerjt.sideout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.absoluteValue

/**
 * The Android stand-in for the iOS Live Activity: one ongoing notification per followed match.
 * On Android 16 it asks to be promoted to a Live Update, which keeps it on the lock screen and puts the score in a status bar chip.
 */
object LiveNotifications {
    const val LIVE_CHANNEL = "live-scores"
    const val RESULTS_CHANNEL = "results"
    const val STARTS_CHANNEL = "match-starts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(LIVE_CHANNEL, "Live scores", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "The scoreboard for a match you follow, updated while it is played."
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        manager.createNotificationChannel(NotificationChannel(RESULTS_CHANNEL, "Final scores", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The final score of a followed match, shown for 30 minutes."
            setShowBadge(false)
        })
        manager.createNotificationChannel(NotificationChannel(STARTS_CHANNEL, "Favorite team matches", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Shown when a favorite team's match is starting and Android didn't let Sideout follow it automatically."
        })
    }

    fun liveId(matchId: String) = 1000 + (matchId.hashCode().absoluteValue % 400_000)
    private fun resultId(matchId: String) = liveId(matchId) + 400_000
    private fun startId(matchId: String) = liveId(matchId) + 800_000

    fun cancelLive(context: Context, matchId: String) = NotificationManagerCompat.from(context).cancel(liveId(matchId))

    /** Opens the match center, like the Live Activity's sideout://match link. */
    private fun openMatch(context: Context, follow: Follow): PendingIntent {
        val uri = Uri.parse("sideout://match/${follow.matchId}?date=${follow.date}&division=${follow.division}")
        val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, liveId(follow.matchId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(context: Context, action: String, follow: Follow): PendingIntent {
        val intent = Intent(context, FollowActionReceiver::class.java).setAction(action).putExtra(FollowActionReceiver.MATCH_ID, follow.matchId)
        return PendingIntent.getBroadcast(context, liveId(follow.matchId) + action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Sets won, then the current set's points, with the away team first as on the scoreboard. */
    fun live(context: Context, follow: Follow, match: Match?, stale: Boolean): Notification {
        val away = match?.away?.abbreviation ?: follow.awayAbbreviation
        val home = match?.home?.abbreviation ?: follow.homeAbbreviation
        val current = match?.currentSet
        val title = if (match == null) "${follow.awayName} at ${follow.homeName}"
            else "$away ${match.away.setsWon.scoreText} – ${match.home.setsWon.scoreText} $home"
        val status = when {
            match == null -> "Connecting live updates…"
            current != null -> "${match.statusText}: ${current.away.scoreText}–${current.home.scoreText}"
            else -> match.statusText
        }
        val text = if (stale) "Update delayed · $status" else status
        val sets = match?.sets.orEmpty().joinToString("   ") { "Set ${it.number}  ${it.away.scoreText}–${it.home.scoreText}" }
        // The status bar chip is short: current points while a set is on, otherwise sets won.
        val chip = when {
            match == null -> null
            current != null -> "${current.away.scoreText}-${current.home.scoreText}"
            else -> "${match.away.setsWon.scoreText}-${match.home.setsWon.scoreText}"
        }
        val builder = NotificationCompat.Builder(context, LIVE_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("${follow.awayName} at ${follow.homeName}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (sets.isEmpty()) text else "$text\n$sets"))
            .setColor(0xFF305C42.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openMatch(context, follow))
            .setDeleteIntent(action(context, FollowActionReceiver.DISMISSED, follow))
            .addAction(0, "Stop following", action(context, FollowActionReceiver.STOP, follow))
        if (match != null) builder.setWhen((match.checkedAt * 1000).toLong()).setShowWhen(true)
        return promote(context, builder, chip)
    }

    /** Asks Android 16 to show the notification as a Live Update. Older versions ignore these extras. */
    private fun promote(context: Context, builder: NotificationCompat.Builder, chip: String?): Notification {
        builder.addExtras(Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
            if (chip != null) putString("android.shortCriticalText", chip)
        })
        val notification = builder.build()
        if (Build.VERSION.SDK_INT < 36) return notification
        // The platform builder sets the same fields directly; reflection keeps this working with older androidx libraries.
        return runCatching {
            val platform = Notification.Builder.recoverBuilder(context, notification)
            Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType).invoke(platform, true)
            if (chip != null) Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java).invoke(platform, chip)
            platform.build()
        }.getOrDefault(notification)
    }

    /** Whether Android will show Sideout's notifications as Live Updates (Android 16 and later). */
    fun canPromote(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(manager) as Boolean
        }.getOrNull()
    }

    fun postResult(context: Context, follow: Follow, match: Match) {
        if (!Sideout.hasNotificationPermission()) return
        val title = "${match.away.abbreviation} ${match.away.setsWon.scoreText} – ${match.home.setsWon.scoreText} ${match.home.abbreviation}"
        val sets = match.sets.joinToString("   ") { "Set ${it.number}  ${it.away.scoreText}–${it.home.scoreText}" }
        val notification = NotificationCompat.Builder(context, RESULTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(match.statusText.ifEmpty { "Final" })
            .setSubText("${match.away.name} at ${match.home.name}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(match.statusText, sets).filter { it.isNotEmpty() }.joinToString("\n")))
            .setColor(0xFF305C42.toInt())
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60 * 1000L)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openMatch(context, follow))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(resultId(match.id), notification)
    }

    /** Used when Android won't let Sideout start following from the background, so a tap starts it instead. */
    fun postStarting(context: Context, follow: Follow) {
        if (!Sideout.hasNotificationPermission()) return
        val notification = NotificationCompat.Builder(context, STARTS_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${follow.awayName} at ${follow.homeName}")
            .setContentText("Starting soon. Tap to follow the live score.")
            .setColor(0xFF305C42.toInt())
            .setAutoCancel(true)
            .setTimeoutAfter(4 * 3_600_000L)
            .setContentIntent(openMatch(context, follow))
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(context).notify(startId(follow.matchId), notification)
    }
}
