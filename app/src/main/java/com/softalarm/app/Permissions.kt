package com.softalarm.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

// Checks every permission an alarm app needs to ring exactly on time, and opens the right Settings page.
object AppPermissions {

    class Item(val key: String, val title: String, val why: String, val granted: Boolean, val manual: Boolean = false)

    fun items(ctx: Context): List<Item> {
        val list = mutableListOf<Item>()
        val nm = ctx.getSystemService(NotificationManager::class.java)

        list.add(Item("notifications", "Notifications",
            "Shows the ringing alarm and its Dismiss button.", nm.areNotificationsEnabled()))

        if (Build.VERSION.SDK_INT >= 31) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            list.add(Item("exact", "Alarms & reminders",
                "Lets the alarm ring at the exact minute.", am.canScheduleExactAlarms()))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            list.add(Item("fullscreen", "Full-screen alarm",
                "Shows the alarm screen over the lock screen.", nm.canUseFullScreenIntent()))
        }

        val pm = ctx.getSystemService(PowerManager::class.java)
        list.add(Item("battery", "No battery restriction",
            "Stops the phone from delaying or stopping the alarm to save power.",
            pm.isIgnoringBatteryOptimizations(ctx.packageName)))

        val audio = ctx.getSystemService(AudioManager::class.java)
        list.add(Item("volume", "Alarm volume",
            "Alarms use the alarm volume. If it is at zero the alarm is silent.",
            audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0))

        list.add(Item("overlay", "Display over other apps",
            "Lets the alarm screen open on top of whatever you are doing.", Settings.canDrawOverlays(ctx)))

        // Cannot be detected by any app: the user must check it by hand.
        list.add(Item("autostart", "Autostart / background (some brands)",
            "Xiaomi, Infinix, Tecno, Oppo, Vivo, Huawei, Samsung: allow Autostart, set Battery to " +
                "\"No restrictions\", and lock SoftAlarm in the Recent apps list.", false, true))
        return list
    }

    fun missingCount(ctx: Context): Int = items(ctx).count { !it.granted && !it.manual }

    fun fix(activity: Activity, key: String) {
        val pkg = activity.packageName
        val pkgUri = Uri.parse("package:" + pkg)
        val prefs = activity.getSharedPreferences("softalarm_perm", Context.MODE_PRIVATE)
        try {
            when (key) {
                "notifications" -> {
                    if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_notif", false)) {
                        prefs.edit().putBoolean("asked_notif", true).apply()
                        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    } else {
                        activity.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        )
                    }
                }
                "exact" -> activity.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
                "fullscreen" -> activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
                "battery" -> activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri))
                "overlay" -> activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))
                "volume" -> activity.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                else -> activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            }
        } catch (e: Exception) {
            // that settings page does not exist on this phone: open the general app settings instead
            try {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            } catch (e2: Exception) {
                // ignore
            }
        }
    }
}
