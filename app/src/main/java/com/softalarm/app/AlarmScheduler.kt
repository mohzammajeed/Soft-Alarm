package com.softalarm.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {
    const val EXTRA_ID = "alarm_id"
    const val EXTRA_LABEL = "alarm_label"
    const val EXTRA_VOICE = "alarm_voice"
    const val EXTRA_URI = "alarm_uri"
    const val TIMER_ID = 1999999999
    const val TEST_ID = 1999999998

    // Finds the next moment this alarm should ring (or null if none).
    fun nextTrigger(alarm: Alarm): Long? {
        val now = Calendar.getInstance()
        for (offset in 0..7) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, offset)
            c.set(Calendar.HOUR_OF_DAY, alarm.hour)
            c.set(Calendar.MINUTE, alarm.minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis <= now.timeInMillis) continue
            val dayIndex = c.get(Calendar.DAY_OF_WEEK) - 1
            if (alarm.days.isEmpty() || alarm.days.contains(dayIndex)) return c.timeInMillis
        }
        return null
    }

    fun schedule(ctx: Context, alarm: Alarm) {
        cancel(ctx, alarm.id)
        if (!alarm.enabled) return
        val t = nextTrigger(alarm) ?: return
        setClock(ctx, alarm.id, alarm.label, alarm.voice, alarm.customUri, t)
    }

    fun scheduleTimer(ctx: Context, endMillis: Long) {
        setClock(ctx, TIMER_ID, "Timer", "default", "", endMillis)
    }

    // "Test alarm": a real alarm through the same path, a few seconds from now
    fun scheduleTest(ctx: Context, seconds: Int) {
        setClock(ctx, TEST_ID, "Test alarm", "default", "", System.currentTimeMillis() + seconds * 1000L)
    }

    fun cancelTimer(ctx: Context) {
        cancel(ctx, TIMER_ID)
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }

    // setAlarmClock = exact alarm that works in Doze mode and needs no special permission.
    private fun setClock(ctx: Context, id: Int, label: String, voice: String, customUri: String, triggerAt: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val intent = Intent(ctx, AlarmReceiver::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_VOICE, voice)
            .putExtra(EXTRA_URI, customUri)
        val pi = PendingIntent.getBroadcast(
            ctx, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
    }
}
