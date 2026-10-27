package com.softalarm.app

enum class RestorePhase { IDLE, SIGNING_IN, CHECKING, RESTORED, NOTHING_FOUND, CANCELLED, FAILED }

class RestoreState(val phase: RestorePhase, val message: String)

// "Sign in / Restore on a new phone", as one small state machine (plain Kotlin, easy to test):
//   sign in (or link the guest to an account) -> ask the server whether this account has Pro -> unlock
// A guest who already paid is protected: before leaving the guest session for ANOTHER existing account,
// the user must confirm, because that Pro would not follow them.
class RestoreFlow(
    private val accounts: AccountGateway,
    private val gateway: ProGateway,
    private val store: ProStore,
    private val onState: (RestoreState) -> Unit,
    private val confirmSwitch: ((Boolean) -> Unit) -> Unit      // shows the question, then calls back with the answer
) {
    var phase: RestorePhase = RestorePhase.IDLE
        private set

    private var generation = 0      // answers from an old attempt are ignored

    private fun publish(p: RestorePhase, message: String) {
        phase = p
        onState(RestoreState(p, message))
    }

    private fun busy(): Boolean = phase == RestorePhase.SIGNING_IN || phase == RestorePhase.CHECKING

    // Sign in with [method] if needed, then look for the purchase
    fun restore(method: SignInMethod) {
        if (busy()) return
        val gen = ++generation
        val info = accounts.current()
        if (info.signedIn) {
            checkPurchases(gen, info.label, false)
            return
        }
        publish(RestorePhase.SIGNING_IN, "Signing in...")
        accounts.signIn(method) { result ->
            if (gen == generation) handleSignIn(gen, result)
        }
    }

    // Already signed in: just look again
    fun checkAccount() {
        if (busy()) return
        val gen = ++generation
        val info = accounts.current()
        if (!info.signedIn) {
            publish(RestorePhase.FAILED, "Please sign in first, then check again.")
            return
        }
        checkPurchases(gen, info.label, false)
    }

    fun reset() {
        generation++
        publish(RestorePhase.IDLE, "")
    }

    private fun handleSignIn(gen: Int, result: AccountResult) {
        when (result) {
            is AccountResult.Linked -> checkPurchases(gen, result.label, true)
            is AccountResult.SwitchedToExisting -> {
                store.setPro(false)         // Pro now follows THIS account, so start from "not Pro" and ask the server
                checkPurchases(gen, result.label, false)
            }
            is AccountResult.AccountExists -> askThenSwitch(gen, result)
            is AccountResult.Cancelled -> publish(RestorePhase.CANCELLED, "Sign-in cancelled. Nothing changed.")
            is AccountResult.NotSetUp -> publish(RestorePhase.FAILED, "Sign-in is not set up yet.")
            is AccountResult.Failed -> publish(RestorePhase.FAILED, result.reason)
        }
    }

    private fun askThenSwitch(gen: Int, exists: AccountResult.AccountExists) {
        val doSwitch = { exists.switchTo { r -> if (gen == generation) handleSignIn(gen, r) } }
        if (store.isPro()) {
            publish(RestorePhase.SIGNING_IN, "Waiting for your answer...")
            confirmSwitch { ok ->
                if (gen != generation) return@confirmSwitch
                if (ok) doSwitch()
                else publish(RestorePhase.CANCELLED, "Nothing changed. Your Pro stays on this guest session.")
            }
        } else {
            doSwitch()
        }
    }

    private fun checkPurchases(gen: Int, label: String, linked: Boolean) {
        publish(RestorePhase.CHECKING, "Checking purchases for $label...")
        gateway.fetchStatus { result ->
            if (gen != generation) return@fetchStatus
            when (result) {
                is StatusResult.Known -> {
                    if (result.pro) {
                        store.setPro(true)
                        store.setAwaiting(false)
                        publish(
                            RestorePhase.RESTORED,
                            if (linked) "Signed in as $label. Your Pro is saved to this account."
                            else "Pro restored for $label. Thank you!"
                        )
                    } else if (linked) {
                        publish(RestorePhase.NOTHING_FOUND, "Signed in as $label. You have not bought Pro yet.")
                    } else {
                        publish(
                            RestorePhase.NOTHING_FOUND,
                            "No purchase found for $label. If you paid with another account, sign in with that one."
                        )
                    }
                }
                is StatusResult.Error -> publish(RestorePhase.FAILED, result.reason)
            }
        }
    }
}
