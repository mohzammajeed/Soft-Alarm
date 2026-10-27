package com.softalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The REAL fake backend, wrapped so the flows can ask it questions straight away
internal class BackendGateway(private val b: FakeBackend) : ProGateway {
    var statusError: String? = null
    var statusCalls = 0
    override fun startCheckout(done: (CheckoutResult) -> Unit) = done(b.checkout())
    override fun fetchStatus(done: (StatusResult) -> Unit) {
        statusCalls++
        val e = statusError
        done(if (e != null) StatusResult.Error(e) else b.status())
    }
}

class RestoreFlowTest {
    private val backend = FakeBackend()
    private val gateway = BackendGateway(backend)
    private val store = MemoryStore()
    private val phases = ArrayList<RestorePhase>()
    private val messages = ArrayList<String>()

    private var picked: String? = null            // what the "account chooser" answers
    private var chooserCalls = 0
    private var held: ((String?) -> Unit)? = null  // when set, the chooser waits
    private var holdChooser = false
    private var confirmAnswer = true
    private var confirmCalls = 0

    private val accounts = FakeAccountGateway(backend) { _, answer ->
        chooserCalls++
        if (holdChooser) {
            held = answer
        } else {
            answer(picked)
        }
    }

    private fun newFlow() = RestoreFlow(
        accounts = accounts,
        gateway = gateway,
        store = store,
        onState = { s -> phases.add(s.phase); messages.add(s.message) },
        confirmSwitch = { answer -> confirmCalls++; answer(confirmAnswer) }
    )

    // Someone paid with an account on "another phone", and this phone is now a fresh guest
    private fun accountThatPaidElsewhere(raw: String) {
        backend.link("google:$raw")
        backend.pay(); backend.status(); backend.status()
        backend.signOut()
    }

    @Test
    fun restoreOnANewPhoneBringsProBack() {
        accountThatPaidElsewhere("alex@example.com")
        picked = "alex@example.com"
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(RestorePhase.RESTORED, flow.phase)
        assertTrue(store.pro)
        assertTrue(accounts.current().signedIn)
    }

    @Test
    fun signingInToAnAccountThatNeverPaidFindsNothing() {
        backend.link("google:sam@example.com")
        backend.signOut()
        picked = "sam@example.com"
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(RestorePhase.NOTHING_FOUND, flow.phase)
        assertFalse(store.pro)
        assertTrue(messages.last().contains("another account"))
    }

    @Test
    fun aGuestWhoPaidKeepsProWhenTheyJustSignIn() {
        backend.pay(); backend.status(); backend.status()
        store.pro = true
        picked = "alex@example.com"
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(RestorePhase.RESTORED, flow.phase)
        assertTrue(store.pro)
        assertEquals(0, confirmCalls)
        assertTrue(messages.last().contains("saved to this account"))
    }

    @Test
    fun aGuestWithoutAPurchaseWhoSignsInIsToldSo() {
        picked = "alex@example.com"
        val flow = newFlow()
        flow.restore(SignInMethod.EMAIL)
        assertEquals(RestorePhase.NOTHING_FOUND, flow.phase)
        assertTrue(accounts.current().signedIn)
        assertFalse(store.pro)
    }

    @Test
    fun aPaidGuestMustConfirmBeforeSwitchingToAnotherExistingAccount() {
        backend.link("google:other@example.com")
        backend.signOut()                                  // other@ exists but never paid
        backend.pay(); backend.status(); backend.status()  // this guest paid
        store.pro = true
        picked = "other@example.com"
        confirmAnswer = true
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(1, confirmCalls)
        assertEquals(RestorePhase.NOTHING_FOUND, flow.phase)   // other@ has no Pro
        assertFalse("Pro follows the account, so it is now locked", store.pro)
    }

    @Test
    fun sayingNoToTheSwitchChangesNothing() {
        backend.link("google:other@example.com")
        backend.signOut()
        backend.pay(); backend.status(); backend.status()
        store.pro = true
        picked = "other@example.com"
        confirmAnswer = false
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(RestorePhase.CANCELLED, flow.phase)
        assertTrue(store.pro)
        assertFalse(accounts.current().signedIn)
    }

    @Test
    fun aGuestWithNoPurchaseSwitchesWithoutBeingAsked() {
        accountThatPaidElsewhere("alex@example.com")
        picked = "alex@example.com"
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(0, confirmCalls)
    }

    @Test
    fun cancellingTheChooserCancelsTheRestore() {
        picked = null
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(RestorePhase.CANCELLED, flow.phase)
        assertFalse(accounts.current().signedIn)
    }

    @Test
    fun alreadySignedInJustChecksWithoutAskingForSignInAgain() {
        accountThatPaidElsewhere("alex@example.com")
        backend.switchTo("google:alex@example.com")
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(0, chooserCalls)
        assertEquals(RestorePhase.RESTORED, flow.phase)
        assertTrue(store.pro)
    }

    @Test
    fun checkAccountAsAGuestAsksToSignInFirst() {
        val flow = newFlow()
        flow.checkAccount()
        assertEquals(RestorePhase.FAILED, flow.phase)
        assertEquals(0, gateway.statusCalls)
    }

    @Test
    fun aServerProblemShowsAFailureAndDoesNotUnlock() {
        accountThatPaidElsewhere("alex@example.com")
        backend.switchTo("google:alex@example.com")
        gateway.statusError = "No internet connection."
        val flow = newFlow()
        flow.checkAccount()
        assertEquals(RestorePhase.FAILED, flow.phase)
        assertFalse(store.pro)
    }

    @Test
    fun tappingRestoreTwiceWhileSigningInIsIgnored() {
        holdChooser = true
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(1, chooserCalls)
        assertEquals(RestorePhase.SIGNING_IN, flow.phase)
    }

    @Test
    fun anAnswerThatArrivesAfterResetIsIgnored() {
        holdChooser = true
        val flow = newFlow()
        flow.restore(SignInMethod.GOOGLE)
        flow.reset()
        held!!.invoke("alex@example.com")
        assertEquals(RestorePhase.IDLE, flow.phase)
        assertFalse(store.pro)
    }

    @Test
    fun aPlaceholderGatewayExplainsThatSignInIsNotSetUp() {
        val flow = RestoreFlow(PlaceholderAccountGateway(), gateway, store, { s -> phases.add(s.phase) }, { it(true) })
        flow.restore(SignInMethod.GOOGLE)
        assertEquals(RestorePhase.FAILED, flow.phase)
    }
}

class ProSyncTest {
    private val backend = FakeBackend()
    private val gateway = BackendGateway(backend)
    private val store = MemoryStore()
    private var revoked = 0
    private var unlocked = 0
    private fun sync() = ProSync(gateway, store, { revoked++ }, { unlocked++ })

    @Test
    fun paidElsewhereUnlocksThisPhone() {
        backend.pay(); backend.status()
        sync().refresh()                       // 2nd check -> confirmed
        assertTrue(store.pro)
        assertEquals(1, unlocked)
    }

    @Test
    fun aRefundLocksProAgain() {
        backend.pay(); backend.status(); backend.status()
        store.pro = true
        backend.refund()
        sync().refresh()
        assertFalse(store.pro)
        assertEquals(1, revoked)
    }

    @Test
    fun aPlainNotProAnswerNeverTakesProAway() {
        store.pro = true                       // e.g. a server that lost the record, or a new guest id
        sync().refresh()
        assertTrue(store.pro)
        assertEquals(0, revoked)
    }

    @Test
    fun serverErrorsChangeNothing() {
        store.pro = true
        gateway.statusError = "No internet connection."
        sync().refresh()
        assertTrue(store.pro)
        assertEquals(0, revoked)
        assertEquals(0, unlocked)
    }

    @Test
    fun alreadyProAndStillProIsQuiet() {
        backend.pay(); backend.status(); backend.status()
        store.pro = true
        sync().refresh()
        assertEquals(0, unlocked)
        assertEquals(0, revoked)
    }

    @Test
    fun aSecondRefreshWhileOneIsRunningIsIgnored() {
        var held: ((StatusResult) -> Unit)? = null
        val slow = object : ProGateway {
            var calls = 0
            override fun startCheckout(done: (CheckoutResult) -> Unit) {}
            override fun fetchStatus(done: (StatusResult) -> Unit) { calls++; held = done }
        }
        val s = ProSync(slow, store, { revoked++ }, { unlocked++ })
        s.refresh(); s.refresh()
        assertEquals(1, slow.calls)
        held!!.invoke(StatusResult.Known(true))
        s.refresh()
        assertEquals(2, slow.calls)
    }
}

class DeleteAccountFlowTest {
    private val backend = FakeBackend()
    private val gateway = DeletingGateway(backend)
    private val store = MemoryStore()
    private val accounts = FakeAccountGateway(backend) { _, answer -> answer("alex@example.com") }
    private val phases = ArrayList<DeletePhase>()

    private fun newFlow() = DeleteAccountFlow(gateway, accounts, store) { s -> phases.add(s.phase) }

    private fun payAndSignIn() {
        backend.pay(); backend.status(); backend.status()
        accounts.signIn(SignInMethod.GOOGLE) { }
        store.pro = true
    }

    @Test
    fun deletingTheAccountRemovesProAndMakesAFreshGuest() {
        payAndSignIn()
        assertTrue(accounts.current().signedIn)
        val flow = newFlow()
        flow.start()
        assertEquals(DeletePhase.DONE, flow.phase)
        assertFalse(store.pro)
        assertFalse(accounts.current().signedIn)
        assertEquals(listOf(DeletePhase.DELETING, DeletePhase.DONE), phases)
    }

    @Test
    fun theDeletedAccountReallyHasNoPurchaseLeftOnTheServer() {
        payAndSignIn()
        newFlow().start()
        backend.switchTo("google:alex@example.com")          // signing back in to the old address
        assertFalse((backend.status() as StatusResult.Known).pro)
    }

    @Test
    fun ifTheServerFailsNothingChangesOnThePhone() {
        payAndSignIn()
        gateway.failWith = "Your account could not be deleted right now."
        val flow = newFlow()
        flow.start()
        assertEquals(DeletePhase.FAILED, flow.phase)
        assertTrue(store.pro)
        assertTrue(accounts.current().signedIn)
    }

    @Test
    fun tappingDeleteTwiceAsksTheServerOnce() {
        gateway.hold = true
        val flow = newFlow()
        flow.start()
        flow.start()
        assertEquals(1, gateway.calls)
    }

    @Test
    fun anAnswerAfterResetIsIgnored() {
        payAndSignIn()
        gateway.hold = true
        val flow = newFlow()
        flow.start()
        flow.reset()
        gateway.release()
        assertEquals(DeletePhase.IDLE, flow.phase)
        assertTrue(store.pro)
    }

    @Test
    fun aGatewayThatCannotDeleteSaysSo() {
        val noDelete = object : ProGateway {
            override fun startCheckout(done: (CheckoutResult) -> Unit) {}
            override fun fetchStatus(done: (StatusResult) -> Unit) {}
        }
        val flow = DeleteAccountFlow(noDelete, accounts, store) { }
        flow.start()
        assertEquals(DeletePhase.FAILED, flow.phase)
    }
}

internal class DeletingGateway(private val b: FakeBackend) : ProGateway {
    var failWith: String? = null
    var hold = false
    var calls = 0
    private var pending: (() -> Unit)? = null
    override fun startCheckout(done: (CheckoutResult) -> Unit) {}
    override fun fetchStatus(done: (StatusResult) -> Unit) {}
    override fun deleteAccount(done: (DeleteResult) -> Unit) {
        calls++
        val f = failWith
        val action = { done(if (f != null) DeleteResult.Failed(f) else b.deleteAccount()) }
        if (hold) pending = action else action()
    }
    fun release() { pending?.invoke() }
}
