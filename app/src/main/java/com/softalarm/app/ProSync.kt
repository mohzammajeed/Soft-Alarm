package com.softalarm.app

// Quietly keeps the phone's Pro in step with the server (called when the app opens):
//   - server says Pro        -> unlock (e.g. paid on another phone with the same account)
//   - server says REFUNDED   -> lock again
//   - anything else (not Pro, error, offline) changes NOTHING, so a server hiccup can never take Pro away.
class ProSync(
    private val gateway: ProGateway,
    private val store: ProStore,
    private val onRevoked: () -> Unit,
    private val onUnlocked: () -> Unit
) {
    private var running = false

    fun refresh() {
        if (running) return
        running = true
        gateway.fetchStatus { result ->
            running = false
            if (result is StatusResult.Known) {
                if (result.pro && !store.isPro()) {
                    store.setPro(true)
                    onUnlocked()
                } else if (!result.pro && result.refunded && store.isPro()) {
                    store.setPro(false)
                    onRevoked()
                }
            }
        }
    }
}
