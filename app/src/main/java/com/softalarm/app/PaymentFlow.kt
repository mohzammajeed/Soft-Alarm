package com.softalarm.app

enum class Phase { IDLE, STARTING, WAITING, CHECKING, UNLOCKED, FAILED, TIMED_OUT }

class FlowState(val phase: Phase, val message: String)

// The whole "Unlock Pro" journey, as one small state machine:
//   tap Buy -> ask for a payment page -> open it -> (user pays) -> come back -> check until unlocked
// Plain Kotlin: no Android code, so it is easy to test. Things that touch the phone are passed in.
class PaymentFlow(
    private val gateway: ProGateway,
    private val store: ProStore,
    private val schedule: (Long, () -> Unit) -> Unit,   // "run this later" (a Handler on the phone)
    private val onState: (FlowState) -> Unit,           // tells the screen what to show
    private val maxChecks: Int = 10,
    private val checkEveryMs: Long = 3000L
) {
    var phase: Phase = Phase.IDLE
        private set

    // Every new start/reset bumps this number, so answers and timers from an OLD attempt are ignored.
    private var generation = 0

    private fun publish(p: Phase, message: String) {
        phase = p
        onState(FlowState(p, message))
    }

    // openPage opens the payment page and says whether it worked (a browser, or the fake page)
    fun buy(openPage: (String) -> Boolean) {
        if (phase == Phase.STARTING || phase == Phase.CHECKING) return
        if (store.isPro()) {
            publish(Phase.UNLOCKED, "You already have Pro. Enjoy!")
            return
        }
        val gen = ++generation
        publish(Phase.STARTING, "Opening the secure payment page...")
        gateway.startCheckout { result ->
            if (gen != generation) return@startCheckout
            when (result) {
                is CheckoutResult.Url -> {
                    if (openPage(result.url)) {
                        store.setAwaiting(true)
                        publish(Phase.WAITING, "Waiting for your payment. When you finish, come back to SoftAlarm.")
                    } else {
                        publish(Phase.FAILED, "Could not open the payment page. Please check your browser and try again.")
                    }
                }
                is CheckoutResult.AlreadyPro -> unlocked("Welcome back! Pro is already active on your account.")
                is CheckoutResult.NotSetUp -> publish(Phase.FAILED, "Payments are not set up yet.")
                is CheckoutResult.Failed -> publish(Phase.FAILED, result.reason)
            }
        }
    }

    // Call when the user comes back to the app (after the browser, or through the softalarm://paid link)
    fun onReturnedToApp() {
        if (phase == Phase.WAITING || phase == Phase.TIMED_OUT || (phase == Phase.IDLE && store.isAwaiting())) {
            startChecking()
        }
    }

    // "I've paid" / "Restore purchase" button
    fun checkNow() {
        if (phase == Phase.STARTING || phase == Phase.CHECKING) return
        startChecking()
    }

    fun reset() {
        generation++
        store.setAwaiting(false)
        publish(Phase.IDLE, "")
    }

    private fun startChecking() {
        val gen = ++generation
        runCheck(gen, 1)
    }

    // The payment confirmation can arrive a few seconds after the user pays, so we check several times.
    private fun runCheck(gen: Int, attempt: Int) {
        publish(Phase.CHECKING, "Checking your payment... ($attempt of $maxChecks)")
        gateway.fetchStatus { result ->
            if (gen != generation) return@fetchStatus
            var error: String? = null
            when (result) {
                is StatusResult.Known -> if (result.pro) {
                    unlocked("Thank you! Pro is unlocked.")
                    return@fetchStatus
                }
                is StatusResult.Error -> error = result.reason
            }
            if (attempt < maxChecks) {
                schedule(checkEveryMs) { if (gen == generation) runCheck(gen, attempt + 1) }
            } else if (error != null) {
                publish(Phase.FAILED, error)
            } else {
                publish(
                    Phase.TIMED_OUT,
                    "We could not see your payment yet. If you already paid, wait a minute and tap here to check again."
                )
            }
        }
    }

    private fun unlocked(message: String) {
        generation++            // stops any timer still waiting from before
        store.setPro(true)      // Pro is only ever switched ON here, never off
        store.setAwaiting(false)
        publish(Phase.UNLOCKED, message)
    }
}
