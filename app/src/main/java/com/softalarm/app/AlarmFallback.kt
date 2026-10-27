package com.softalarm.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build

// Safety net: if Android refuses to start the ringing service, the alarm must still make a sound.
// It plays the phone's alarm tone and shows the same notification (with Dismiss).
object AlarmFallback {
    private var ringtone: Ringtone? = null

    fun start(ctx: Context, label: String, voice: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.notify(AlarmNotification.NOTIF_ID, AlarmNotification.build(ctx, label, voice))
        } catch (e: Exception) {
            // ignore
        }
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return
            val r = RingtoneManager.getRingtone(ctx.applicationContext, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stop() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
            // ignore
        }
        ringtone = null
    }
}
