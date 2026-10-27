package com.softalarm.app

// A pretend payment server + pretend accounts, for testing every screen with NO network and NO money.
// It behaves like the real thing:
//   - a payment is confirmed a moment AFTER the user pays (1st check "not yet", 2nd check "paid")
//   - Pro belongs to an account. A guest who signs in KEEPS their purchase (same user, like Firebase "linking").
//   - signing in to an account that already existed switches to it
//   - a refund takes Pro away and says so
class FakeBackend {
    private val paidIds = HashSet<String>()
    private val refundedIds = HashSet<String>()
    private val knownAccounts = HashSet<String>()
    private val checks = HashMap<String, Int>()
    private var guestCount = 0

    var currentId: String = newGuest()
        private set

    private fun newGuest(): String {
        guestCount++
        return "guest-$guestCount"
    }

    val paid: Boolean get() = currentId in paidIds
    val isSignedIn: Boolean get() = !currentId.startsWith("guest-")
    val label: String get() = if (isSignedIn) currentId.substringAfter(':') else "Guest"

    fun checkout(): CheckoutResult =
        if (paid) CheckoutResult.AlreadyPro else CheckoutResult.Url(FAKE_URL)

    fun pay() {
        paidIds.add(currentId)
        refundedIds.remove(currentId)
        checks[currentId] = 0
    }

    fun refund() {
        if (paid) {
            paidIds.remove(currentId)
            refundedIds.add(currentId)
        }
    }

    fun status(): StatusResult {
        if (currentId in refundedIds) return StatusResult.Known(false, true)
        if (!paid) return StatusResult.Known(false)
        val n = (checks[currentId] ?: 0) + 1
        checks[currentId] = n
        return StatusResult.Known(n >= 2)
    }

    // Guest signs in to a NEW account: same user, so the purchase follows. Returns false if the account already exists.
    fun link(accountId: String): Boolean {
        if (accountId in knownAccounts) return false
        knownAccounts.add(accountId)
        val old = currentId
        if (old in paidIds) { paidIds.remove(old); paidIds.add(accountId) }
        if (old in refundedIds) { refundedIds.remove(old); refundedIds.add(accountId) }
        checks[accountId] = checks.remove(old) ?: 0
        currentId = accountId
        return true
    }

    fun switchTo(accountId: String) {
        knownAccounts.add(accountId)
        currentId = accountId
    }

    // Sign out / a brand new phone: a fresh guest with nothing bought
    fun signOut() {
        currentId = newGuest()
    }

    // Deletes this account and everything about it; the phone becomes a fresh guest
    fun deleteAccount(): DeleteResult {
        paidIds.remove(currentId)
        refundedIds.remove(currentId)
        knownAccounts.remove(currentId)
        checks.remove(currentId)
        currentId = newGuest()
        return DeleteResult.Done
    }

    fun reset() {
        paidIds.clear()
        refundedIds.clear()
        knownAccounts.clear()
        checks.clear()
        currentId = newGuest()
    }

    companion object {
        const val FAKE_URL = "softalarm-fake://checkout"
    }
}

// One shared fake server for the whole app process
object FakeServer {
    val backend = FakeBackend()
}

// FAKE sign-in. The "chooser" asks the user which account to use (a pop-up on the phone; a script in tests).
class FakeAccountGateway(
    private val backend: FakeBackend,
    private val chooser: (SignInMethod, (String?) -> Unit) -> Unit
) : AccountGateway {

    override fun current(): AccountInfo = AccountInfo(backend.isSignedIn, backend.label)

    override fun signIn(method: SignInMethod, done: (AccountResult) -> Unit) {
        chooser(method) { raw ->
            val name = raw?.trim()?.lowercase()
            if (name.isNullOrEmpty()) {
                done(AccountResult.Cancelled)
            } else {
                val id = method.name.lowercase() + ":" + name
                if (backend.link(id)) {
                    done(AccountResult.Linked(name))
                } else {
                    done(AccountResult.AccountExists(name) { cb ->
                        backend.switchTo(id)
                        cb(AccountResult.SwitchedToExisting(name))
                    })
                }
            }
        }
    }

    override fun signOut() {
        backend.signOut()
    }
}
