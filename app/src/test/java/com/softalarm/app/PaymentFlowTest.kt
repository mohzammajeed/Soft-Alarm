package com.softalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ----- small stand-ins used by the tests (no phone needed) -----

internal class MemoryStore(var pro: Boolean = false, var awaiting: Boolean = false) : ProStore {
    override fun isPro() = pro
    override fun setPro(value: Boolean) { pro = value }
    override fun isAwaiting() = awaiting
    override fun setAwaiting(value: Boolean) { awaiting = value }
}

// A gateway whose answers are decided by the test
internal class ScriptedGateway : ProGateway {
    val checkoutAnswers = ArrayDeque<CheckoutResult>()
    val statusAnswers = ArrayDeque<StatusResult>()
    var checkoutCalls = 0
    var statusCalls = 0
    var holdCheckout = false                       // true = do not answer until the test says so
    var pendingCheckout: ((CheckoutResult) -> Unit)? = null

    override fun startCheckout(done: (CheckoutResult) -> Unit) {
        checkoutCalls++
        if (holdCheckout) {
            pendingCheckout = done
            return
        }
        done(checkoutAnswers.removeFirst())
    }

    override fun fetchStatus(done: (StatusResult) -> Unit) {
        statusCalls++
        done(if (statusAnswers.isEmpty()) StatusResult.Known(false) else statusAnswers.removeFirst())
    }
}

// "Run this later": the test decides when
internal class ManualScheduler {
    val delays = ArrayList<Long>()
    private val tasks = ArrayList<() -> Unit>()

    fun schedule(delayMs: Long, task: () -> Unit) {
        delays.add(delayMs)
        tasks.add(task)
    }

    fun pending(): Int = tasks.size

    fun runAll() {
        while (tasks.isNotEmpty()) {
            val t = tasks.removeAt(0)
            t()
        }
    }
}

class PaymentFlowTest {
    private val store = MemoryStore()
    private val gateway = ScriptedGateway()
    private val sched = ManualScheduler()
    private val states = ArrayList<Phase>()
    private var opened: String? = null

    private fun newFlow(maxChecks: Int = 3, theStore: ProStore = store) = PaymentFlow(
        gateway = gateway,
        store = theStore,
        schedule = { delayMs, task -> sched.schedule(delayMs, task) },
        onState = { state -> states.add(state.phase) },
        maxChecks = maxChecks,
        checkEveryMs = 3000L
    )

    private fun openOk(url: String): Boolean {
        opened = url
        return true
    }

    private fun url(u: String = "https://pay.example/c1") = CheckoutResult.Url(u)

    @Test
    fun buyOpensThePaymentPageAndWaits() {
        gateway.checkoutAnswers.add(url())
        val flow = newFlow()
        flow.buy { openOk(it) }
        assertEquals("https://pay.example/c1", opened)
        assertEquals(Phase.WAITING, flow.phase)
        assertTrue(store.awaiting)
        assertEquals(listOf(Phase.STARTING, Phase.WAITING), states)
    }

    @Test
    fun comingBackAndFindingPaymentUnlocksPro() {
        gateway.checkoutAnswers.add(url())
        gateway.statusAnswers.add(StatusResult.Known(true))
        val flow = newFlow()
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        assertEquals(Phase.UNLOCKED, flow.phase)
        assertTrue(store.pro)
        assertFalse(store.awaiting)
    }

    @Test
    fun theConfirmationMayArriveLateSoItKeepsChecking() {
        gateway.checkoutAnswers.add(url())
        gateway.statusAnswers.add(StatusResult.Known(false))
        gateway.statusAnswers.add(StatusResult.Known(false))
        gateway.statusAnswers.add(StatusResult.Known(true))
        val flow = newFlow(maxChecks = 5)
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        assertEquals(Phase.CHECKING, flow.phase)
        sched.runAll()
        assertEquals(Phase.UNLOCKED, flow.phase)
        assertEquals(3, gateway.statusCalls)
        assertEquals(listOf(3000L, 3000L), sched.delays)
    }

    @Test
    fun ifNoPaymentAppearsItStopsWithATimeoutAndNeverUnlocks() {
        gateway.checkoutAnswers.add(url())
        val flow = newFlow(maxChecks = 3)
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        sched.runAll()
        assertEquals(Phase.TIMED_OUT, flow.phase)
        assertEquals(3, gateway.statusCalls)
        assertFalse(store.pro)
    }

    @Test
    fun aTimedOutCheckCanBeTriedAgainAndThenSucceeds() {
        gateway.checkoutAnswers.add(url())
        val flow = newFlow(maxChecks = 2)
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        sched.runAll()
        assertEquals(Phase.TIMED_OUT, flow.phase)
        gateway.statusAnswers.add(StatusResult.Known(true))
        flow.checkNow()
        assertEquals(Phase.UNLOCKED, flow.phase)
        assertTrue(store.pro)
    }

    @Test
    fun networkErrorsAreRetriedAndThenItWorks() {
        gateway.checkoutAnswers.add(url())
        gateway.statusAnswers.add(StatusResult.Error("No internet connection."))
        gateway.statusAnswers.add(StatusResult.Error("No internet connection."))
        gateway.statusAnswers.add(StatusResult.Known(true))
        val flow = newFlow(maxChecks = 5)
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        sched.runAll()
        assertEquals(Phase.UNLOCKED, flow.phase)
    }

    @Test
    fun ifTheServerStaysUnreachableItFailsWithTheReason() {
        gateway.checkoutAnswers.add(url())
        for (i in 1..3) gateway.statusAnswers.add(StatusResult.Error("No internet connection."))
        val flow = newFlow(maxChecks = 3)
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        sched.runAll()
        assertEquals(Phase.FAILED, flow.phase)
        assertFalse(store.pro)
    }

    @Test
    fun anAccountThatIsAlreadyProIsUnlockedWithoutPaying() {
        gateway.checkoutAnswers.add(CheckoutResult.AlreadyPro)
        val flow = newFlow()
        flow.buy { openOk(it) }
        assertNull(opened)
        assertEquals(Phase.UNLOCKED, flow.phase)
        assertTrue(store.pro)
    }

    @Test
    fun buyWhenAlreadyProOnThePhoneDoesNotAskTheServer() {
        val flow = newFlow(theStore = MemoryStore(pro = true))
        flow.buy { openOk(it) }
        assertEquals(0, gateway.checkoutCalls)
        assertEquals(Phase.UNLOCKED, flow.phase)
    }

    @Test
    fun checkoutProblemsShowAFriendlyFailure() {
        gateway.checkoutAnswers.add(CheckoutResult.Failed("Too many attempts."))
        gateway.checkoutAnswers.add(CheckoutResult.NotSetUp)
        val flow = newFlow()
        flow.buy { openOk(it) }
        assertEquals(Phase.FAILED, flow.phase)
        assertNull(opened)
        assertFalse(store.awaiting)
        flow.buy { openOk(it) }
        assertEquals(Phase.FAILED, flow.phase)
    }

    @Test
    fun ifTheBrowserCannotBeOpenedItFailsAndDoesNotWaitForever() {
        gateway.checkoutAnswers.add(url())
        val flow = newFlow()
        flow.buy { false }
        assertEquals(Phase.FAILED, flow.phase)
        assertFalse(store.awaiting)
    }

    @Test
    fun tappingBuyTwiceQuicklyAsksTheServerOnlyOnce() {
        gateway.holdCheckout = true
        val flow = newFlow()
        flow.buy { openOk(it) }
        flow.buy { openOk(it) }
        assertEquals(1, gateway.checkoutCalls)
        assertEquals(Phase.STARTING, flow.phase)
    }

    @Test
    fun anAnswerThatArrivesAfterResetIsIgnored() {
        gateway.holdCheckout = true
        val flow = newFlow()
        flow.buy { openOk(it) }
        flow.reset()
        gateway.pendingCheckout!!.invoke(url())
        assertNull(opened)
        assertEquals(Phase.IDLE, flow.phase)
        assertFalse(store.awaiting)
    }

    @Test
    fun resettingWhileCheckingStopsTheTimers() {
        gateway.checkoutAnswers.add(url())
        val flow = newFlow(maxChecks = 5)
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        assertEquals(1, sched.pending())
        flow.reset()
        sched.runAll()
        assertEquals(1, gateway.statusCalls)
        assertEquals(Phase.IDLE, flow.phase)
    }

    @Test
    fun ifTheAppWasKilledWhilePayingItChecksWhenReopened() {
        val killedStore = MemoryStore(awaiting = true)
        gateway.statusAnswers.add(StatusResult.Known(true))
        val flow = newFlow(theStore = killedStore)      // a brand new flow: phase is IDLE
        flow.onReturnedToApp()
        assertEquals(Phase.UNLOCKED, flow.phase)
        assertTrue(killedStore.pro)
    }

    @Test
    fun openingTheAppNormallyDoesNotStartAnyCheck() {
        val flow = newFlow()
        flow.onReturnedToApp()
        assertEquals(0, gateway.statusCalls)
        assertEquals(Phase.IDLE, flow.phase)
    }

    @Test
    fun proIsNeverSwitchedOffByThePaymentFlow() {
        val proStore = MemoryStore(pro = true)
        gateway.statusAnswers.add(StatusResult.Known(false))
        val flow = newFlow(maxChecks = 1, theStore = proStore)
        flow.checkNow()
        assertTrue(proStore.pro)
    }

    @Test
    fun checkNowWhileAlreadyCheckingIsIgnored() {
        gateway.checkoutAnswers.add(url())
        val flow = newFlow(maxChecks = 5)
        flow.buy { openOk(it) }
        flow.onReturnedToApp()
        flow.checkNow()
        assertEquals(1, gateway.statusCalls)
    }
}
