package com.softalarm.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The screen that appears over the lock screen when an alarm rings.
class AlarmRingActivity : Activity() {

    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }
    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val label = intent.getStringExtra(AlarmScheduler.EXTRA_LABEL) ?: ""
        val voice = intent.getStringExtra(AlarmScheduler.EXTRA_VOICE) ?: "default"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(dp(24), dp(24), dp(24), dp(24))
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Colors.CREAM, Colors.PEACH, Colors.BLUSH)
        )

        val time = TextView(this)
        time.text = SimpleDateFormat("h:mm a", Locale.US).format(Date())
        time.textSize = 56f
        time.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        time.setTextColor(Colors.PLUM)
        time.gravity = Gravity.CENTER
        root.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val title = TextView(this)
        title.text = if (label.isBlank()) "Time to wake up" else label
        title.textSize = 22f
        title.setTextColor(Colors.PLUM)
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(12), 0, dp(4))
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val voiceText = TextView(this)
        voiceText.text = Voices.labelFor(voice)
        voiceText.textSize = 15f
        voiceText.setTextColor(Colors.MAUVE)
        voiceText.gravity = Gravity.CENTER
        root.addView(voiceText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dismiss = pillButton("Dismiss", true)
        dismiss.textSize = 18f
        dismiss.setOnClickListener {
            startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
            finish()
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(48)
        root.addView(dismiss, params)

        setContentView(root)

        val filter = IntentFilter(AlarmService.ACTION_STOPPED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stoppedReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stoppedReceiver)
            } catch (e: Exception) {
                // ignore
            }
        }
        super.onDestroy()
    }
}
