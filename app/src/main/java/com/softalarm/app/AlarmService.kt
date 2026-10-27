package com.softalarm.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator

class AlarmService : Service() {

    companion object {
        const val ACTION_STOP = "com.softalarm.app.ACTION_STOP"
        const val ACTION_STOPPED = "com.softalarm.app.ALARM_STOPPED"
        private const val START_VOLUME = 0.3f      // gentle start...
        private const val FADE_STEP = 0.035f       // ...rising to full volume in about 20 seconds
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var volume = START_VOLUME
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAlarm() }
    private val fadeIn = object : Runnable {
        override fun run() {
            volume = minOf(1f, volume + FADE_STEP)
            try {
                player?.setVolume(volume, volume)
            } catch (e: Exception) {
                // ignore
            }
            if (volume < 1f) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopAlarm()
            return START_NOT_STICKY
        }
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        var voice = intent.getStringExtra(AlarmScheduler.EXTRA_VOICE) ?: "default"
        val customUri = intent.getStringExtra(AlarmScheduler.EXTRA_URI) ?: ""
        // A Pro voice on a phone that is not unlocked plays the normal alarm tone instead
        if (Voices.isPro(voice) && !ProManager.isPro(this)) voice = "default"

        // Must come first: promotes the service to a foreground service
        val notification = AlarmNotification.build(this, label, voice)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(AlarmNotification.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(AlarmNotification.NOTIF_ID, notification)
        }
        AlarmFallback.stop()   // the real service has taken over from the safety net

        try {
            // open the alarm screen right away (works when "Display over other apps" is allowed)
            startActivity(
                Intent(this, AlarmRingActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(AlarmScheduler.EXTRA_LABEL, label)
                    .putExtra(AlarmScheduler.EXTRA_VOICE, voice)
            )
        } catch (e: Exception) {
            // the notification (full-screen intent) is the backup
        }

        requestFocus()
        startSound(voice, customUri)
        startVibration()

        handler.removeCallbacks(fadeIn)
        handler.postDelayed(fadeIn, 1000)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 5 * 60 * 1000L)   // stop by itself after 5 minutes
        return START_NOT_STICKY
    }

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private fun requestFocus() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(alarmAttributes())
                .build()
            am.requestAudioFocus(req)
            focusRequest = req
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun abandonFocus() {
        try {
            val req = focusRequest
            if (req != null) getSystemService(AudioManager::class.java).abandonAudioFocusRequest(req)
        } catch (e: Exception) {
            // ignore
        }
        focusRequest = null
    }

    private fun buildPlayer(setSource: (MediaPlayer) -> Unit): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setAudioAttributes(alarmAttributes())
            setSource(mp)
            mp.isLooping = true
            mp.prepare()
            mp
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    private fun startSound(voice: String, customUri: String) {
        releasePlayer()
        var mp: MediaPlayer? = null

        // 0) a file the user picked from the phone
        if (voice == "custom" && customUri.isNotEmpty()) {
            mp = buildPlayer { p -> p.setDataSource(this@AlarmService, Uri.parse(customUri)) }
        }
        // 1) the voice mp3 (or default_file.mp3) from assets/audio, if you added it
        if (mp == null && voice != "default" && voice != "custom") {
            mp = buildPlayer { p ->
                val afd = assets.openFd("audio/$voice.mp3")
                try {
                    p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                } finally {
                    afd.close()
                }
            }
        }
        // 2) fallback: the phone's normal alarm sound
        if (mp == null) {
            val uris = listOf(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )
            for (uri in uris) {
                if (uri == null) continue
                mp = buildPlayer { p -> p.setDataSource(this@AlarmService, uri) }
                if (mp != null) break
            }
        }
        player = mp
        volume = START_VOLUME
        try {
            mp?.setVolume(volume, volume)
            mp?.start()
        } catch (e: Exception) {
            // could not start the sound; vibration and the notification still work
        }
    }

    private fun startVibration() {
        try {
            vibrator = getSystemService(Vibrator::class.java)
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 900), 0))
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun releasePlayer() {
        val p = player
        if (p != null) {
            try {
                if (p.isPlaying) p.stop()
            } catch (e: Exception) {
                // ignore
            }
            p.release()
        }
        player = null
    }

    private fun stopAlarm() {
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(fadeIn)
        releasePlayer()
        AlarmFallback.stop()
        abandonFocus()
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(AlarmNotification.NOTIF_ID)
        } catch (e: Exception) {
            // ignore
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        handler.removeCallbacks(fadeIn)
        releasePlayer()
        abandonFocus()
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }
}
