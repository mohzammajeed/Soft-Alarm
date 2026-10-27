package com.softalarm.app

import android.content.Context

// Remembers on the phone whether Pro is unlocked (a cache of what the server said).
object ProManager {
    private const val PREFS = "softalarm_pro"

    fun isPro(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pro", false)

    fun setPro(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("pro", value).apply()
    }
}

// The same storage, in the shape the payment flow needs
class PrefsProStore(private val ctx: Context) : ProStore {
    private val prefs get() = ctx.getSharedPreferences("softalarm_pro", Context.MODE_PRIVATE)

    override fun isPro(): Boolean = ProManager.isPro(ctx)
    override fun setPro(value: Boolean) = ProManager.setPro(ctx, value)
    // "Away paying in the browser" is only believed for 30 minutes, so an abandoned payment does not nag forever
    override fun isAwaiting(): Boolean =
        prefs.getBoolean("awaiting", false) &&
            System.currentTimeMillis() - prefs.getLong("awaiting_at", 0L) < 30 * 60 * 1000L

    override fun setAwaiting(value: Boolean) {
        prefs.edit().putBoolean("awaiting", value).putLong("awaiting_at", System.currentTimeMillis()).apply()
    }
}
