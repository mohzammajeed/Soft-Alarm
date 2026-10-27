package com.softalarm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Keep the CPU awake for a few seconds while the ringing service starts
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "softalarm:alarm").acquire(15000L)
        } catch (e: Exception) {
            // ignore
        }

        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        val voice = intent.getStringExtra(AlarmScheduler.EXTRA_VOICE) ?: "default"

        // Start the ringing service (plays the voice mp3 and shows the full-screen alarm)
        val svc = Intent(context, AlarmService::class.java)
            .putExtra(AlarmScheduler.EXTRA_LABEL, label)
            .putExtra(AlarmScheduler.EXTRA_VOICE, voice)
            .putExtra(AlarmScheduler.EXTRA_URI, intent.getStringExtra(AlarmScheduler.EXTRA_URI) ?: "")
        try {
            context.startForegroundService(svc)
        } catch (e: Exception) {
            // Android refused to start the service: still make sure the alarm is heard
            AlarmFallback.start(context, label, voice)
        }

        // Repeating alarms: schedule the next day. One-time alarms: switch off.
        if (id != AlarmScheduler.TIMER_ID) {
            val list = AlarmStore.load(context)
            val alarm = list.firstOrNull { it.id == id }
            if (alarm != null) {
                if (alarm.days.isEmpty()) {
                    alarm.enabled = false
                    AlarmStore.save(context, list)
                } else {
                    AlarmScheduler.schedule(context, alarm)
                }
            }
        }
    }
}
