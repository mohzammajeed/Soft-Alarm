package com.softalarm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

// The "alarm is ringing" notification (full-screen on the lock screen, with a Dismiss button).
object AlarmNotification {
    const val CHANNEL_ID = "softalarm_ringing"
    const val NOTIF_ID = 4242

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH)
            ch.setSound(null, null)          // the sound is played by the alarm service itself
            ch.enableVibration(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    fun build(ctx: Context, label: String, voice: String): Notification {
        ensureChannel(ctx)

        val ringIntent = Intent(ctx, AlarmRingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AlarmScheduler.EXTRA_LABEL, label)
            .putExtra(AlarmScheduler.EXTRA_VOICE, voice)
        val ringPi = PendingIntent.getActivity(
            ctx, 0, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            ctx, 1, Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
            "Dismiss", stopPi
        ).build()

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (label.isBlank()) "SoftAlarm" else label)
            .setContentText("Tap to open")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(ringPi)
            .setFullScreenIntent(ringPi, true)
            .addAction(dismiss)
            .build()
    }
}
