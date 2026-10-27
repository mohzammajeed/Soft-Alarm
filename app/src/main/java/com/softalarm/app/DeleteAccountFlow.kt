package com.softalarm.app

enum class DeletePhase { IDLE, DELETING, DONE, FAILED }

class DeleteState(val phase: DeletePhase, val message: String)

// "Delete my account": ask the server to erase the account, then make this phone a fresh guest.
// Plain Kotlin, so it is tested on a computer. Nothing is changed on the phone unless the server said "done".
class DeleteAccountFlow(
    private val gateway: ProGateway,
    private val accounts: AccountGateway,
    private val store: ProStore,
    private val onState: (DeleteState) -> Unit
) {
    var phase: DeletePhase = DeletePhase.IDLE
        private set

    private var generation = 0

    private fun publish(p: DeletePhase, message: String) {
        phase = p
        onState(DeleteState(p, message))
    }

    fun start() {
        if (phase == DeletePhase.DELETING) return
        val gen = ++generation
        publish(DeletePhase.DELETING, "Deleting your account...")
        gateway.deleteAccount { result ->
            if (gen != generation) return@deleteAccount
            when (result) {
                is DeleteResult.Done -> {
                    accounts.signOut()          // back to a fresh guest
                    store.setPro(false)         // the purchase belonged to the deleted account
                    store.setAwaiting(false)
                    publish(DeletePhase.DONE, "Your account and its data were deleted.")
                }
                is DeleteResult.Failed -> publish(DeletePhase.FAILED, result.reason)
            }
        }
    }

    fun reset() {
        generation++
        publish(DeletePhase.IDLE, "")
    }
}
