package com.softalarm.app

// The few settings you change when you go from FAKE to REAL payments. See PLUGIN_POINTS.md.
object ProConfig {
    // PLUG-IN POINT 2: true = pretend payments (no network, no money). false = your real server.
    const val USE_FAKE_PAYMENTS = true

    // The address of your payment server (backend-stub), https only, no slash at the end.
    const val SERVER_URL = "https://YOUR-SERVER.example.com"

    // Text shown on the Unlock button. The real price is set in your Polar product, not here.
    const val PRICE_LABEL = "$1"

    val serverConfigured: Boolean get() = !SERVER_URL.contains("YOUR-SERVER")

    // The legal pages your server shows (also needed in the store listing)
    val privacyUrl: String get() = SERVER_URL.trimEnd('/') + "/privacy"
    val deleteAccountUrl: String get() = SERVER_URL.trimEnd('/') + "/delete-account"

    // How often / how long to check after the user pays (fake mode is quicker for testing)
    val checkMaxAttempts: Int get() = if (USE_FAKE_PAYMENTS) 4 else 10
    val checkEveryMs: Long get() = if (USE_FAKE_PAYMENTS) 1000L else 3000L
}
