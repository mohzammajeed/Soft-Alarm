package com.softalarm.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import java.util.Locale

object Colors {
    val CREAM = Color.parseColor("#FFF8F2")
    val PEACH = Color.parseColor("#FFDCC2")
    val BLUSH = Color.parseColor("#FFB6AE")
    val PLUM = Color.parseColor("#3D2C3A")
    val CORAL = Color.parseColor("#FF7A5C")
    val MAUVE = Color.parseColor("#9C7A9E")
    val SOFT_TEXT = Color.parseColor("#7A5C6E")
    val GLASS = Color.parseColor("#B3FFFFFF")
    val GLASS_LIGHT = Color.parseColor("#8CFFFFFF")
    val SOFT_BTN = Color.parseColor("#F0E1DA")
    val WHITE = Color.WHITE
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = dp(radiusDp).toFloat()
    return d
}

fun Context.pillButton(label: String, primary: Boolean): Button {
    val b = Button(this)
    b.text = label
    b.isAllCaps = false
    b.textSize = 15f
    b.typeface = Typeface.DEFAULT_BOLD
    b.setTextColor(if (primary) Colors.WHITE else Colors.SOFT_TEXT)
    b.background = rounded(if (primary) Colors.CORAL else Colors.SOFT_BTN, 14)
    b.stateListAnimator = null
    b.setPadding(dp(24), dp(12), dp(24), dp(12))
    return b
}

fun formatHMS(totalSeconds: Long): String {
    val s = if (totalSeconds < 0) 0L else totalSeconds
    return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

fun formatTime(hour: Int, minute: Int): String {
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    val ampm = if (hour >= 12) "PM" else "AM"
    return String.format(Locale.US, "%d:%02d %s", h12, minute, ampm)
}
