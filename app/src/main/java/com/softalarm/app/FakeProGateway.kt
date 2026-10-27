package com.softalarm.app

import android.os.Handler
import android.os.Looper

// FAKE gateway: answers after a short delay, like a real network would.
class FakeProGateway(private val backend: FakeBackend) : ProGateway {
    private val handler = Handler(Looper.getMainLooper())

    override fun startCheckout(done: (CheckoutResult) -> Unit) {
        handler.postDelayed({ done(backend.checkout()) }, 500)
    }

    override fun fetchStatus(done: (StatusResult) -> Unit) {
        handler.postDelayed({ done(backend.status()) }, 400)
    }

    override fun deleteAccount(done: (DeleteResult) -> Unit) {
        handler.postDelayed({ done(backend.deleteAccount()) }, 600)
    }
}
