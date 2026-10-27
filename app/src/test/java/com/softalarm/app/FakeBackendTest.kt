package com.softalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBackendTest {

    private fun pro(r: StatusResult) = (r as StatusResult.Known).pro

    @Test
    fun beforePayingThereIsACheckoutPageAndNoPro() {
        val b = FakeBackend()
        assertEquals(FakeBackend.FAKE_URL, (b.checkout() as CheckoutResult.Url).url)
        assertFalse(pro(b.status()))
    }

    @Test
    fun theConfirmationArrivesOnTheSecondCheckLikeARealWebhook() {
        val b = FakeBackend()
        b.pay()
        assertFalse(pro(b.status()))
        assertTrue(pro(b.status()))
        assertTrue(pro(b.status()))
    }

    @Test
    fun afterPayingTheAccountIsAlreadyPro() {
        val b = FakeBackend()
        b.pay()
        assertTrue(b.checkout() is CheckoutResult.AlreadyPro)
    }

    @Test
    fun resetGoesBackToUnpaid() {
        val b = FakeBackend()
        b.pay()
        b.status(); b.status()
        b.reset()
        assertFalse(b.paid)
        assertFalse(pro(b.status()))
        assertTrue(b.checkout() is CheckoutResult.Url)
    }
}

class FakeAccountsTest {

    private fun pro(r: StatusResult) = (r as StatusResult.Known).pro

    @Test
    fun aGuestWhoSignsInKeepsTheirPurchase() {
        val b = FakeBackend()
        b.pay(); b.status(); b.status()
        assertTrue(b.link("google:alex@example.com"))
        assertTrue(b.isSignedIn)
        assertEquals("google:alex@example.com", b.currentId)
        assertTrue(pro(b.status()))
    }

    @Test
    fun linkingToAnAccountThatAlreadyExistsIsRefused() {
        val b = FakeBackend()
        assertTrue(b.link("google:alex@example.com"))
        b.signOut()
        assertFalse(b.isSignedIn)
        assertFalse(b.link("google:alex@example.com"))
    }

    @Test
    fun aNewPhoneStartsAsAGuestWithoutPro() {
        val b = FakeBackend()
        b.pay(); b.status(); b.status()
        b.signOut()
        assertEquals("Guest", b.label)
        assertFalse(pro(b.status()))
    }

    @Test
    fun aRefundSaysRefundedAndPayingAgainClearsIt() {
        val b = FakeBackend()
        b.pay(); b.status(); b.status()
        b.refund()
        val r = b.status() as StatusResult.Known
        assertFalse(r.pro)
        assertTrue(r.refunded)
        b.pay(); b.status()
        assertFalse((b.status() as StatusResult.Known).refunded)
    }

    @Test
    fun theFakeGatewayLinksOrOffersToSwitchAndCancelsCleanly() {
        val b = FakeBackend()
        var answer: String? = "alex@example.com"
        val g = FakeAccountGateway(b) { _, picked -> picked(answer) }

        var result: AccountResult? = null
        g.signIn(SignInMethod.GOOGLE) { result = it }
        assertTrue(result is AccountResult.Linked)
        assertTrue(g.current().signedIn)
        assertEquals("alex@example.com", g.current().label)

        g.signOut()
        assertFalse(g.current().signedIn)

        g.signIn(SignInMethod.GOOGLE) { result = it }          // same account again: it already exists
        assertTrue(result is AccountResult.AccountExists)
        assertFalse(g.current().signedIn)                        // nothing switched until we say so
        var after: AccountResult? = null
        (result as AccountResult.AccountExists).switchTo { after = it }
        assertTrue(after is AccountResult.SwitchedToExisting)
        assertTrue(g.current().signedIn)

        answer = null
        g.signOut()
        g.signIn(SignInMethod.EMAIL) { result = it }
        assertTrue(result is AccountResult.Cancelled)
    }

    @Test
    fun deletingAnAccountForgetsIt() {
        val b = FakeBackend()
        b.link("google:alex@example.com")
        b.pay(); b.status(); b.status()
        assertTrue(b.deleteAccount() is DeleteResult.Done)
        assertEquals("Guest", b.label)
        assertTrue(b.link("google:alex@example.com"))          // the address is free to be used again as a NEW account
        assertFalse((b.status() as StatusResult.Known).pro)
    }
}
