package com.softalarm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// After the phone restarts, the app is updated, or the clock / time zone changes,
// set all enabled alarms again.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            for (alarm in AlarmStore.load(context)) {
                if (alarm.enabled) AlarmScheduler.schedule(context, alarm)
            }
        }
    }
}
