package com.softalarm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepliesTest {

    @Test
    fun goodCheckoutAnswerGivesThePaymentPage() {
        val r = ServerReplies.checkout(200, """{"checkoutUrl":"https://sandbox.polar.sh/checkout/abc"}""")
        assertTrue(r is CheckoutResult.Url)
        assertEquals("https://sandbox.polar.sh/checkout/abc", (r as CheckoutResult.Url).url)
    }

    @Test
    fun onlySecureHttpsPagesAreEverOpened() {
        assertTrue(ServerReplies.checkout(200, """{"checkoutUrl":"http://evil.example/pay"}""") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(200, """{"checkoutUrl":"javascript:alert(1)"}""") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(200, """{"checkoutUrl":"softalarm-fake://checkout"}""") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(200, """{"checkoutUrl":""}""") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(200, """{}""") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(200, "not json at all") is CheckoutResult.Failed)
    }

    @Test
    fun serverStatusCodesBecomeTheRightResults() {
        assertTrue(ServerReplies.checkout(409, """{"alreadyPro":true}""") is CheckoutResult.AlreadyPro)
        assertTrue(ServerReplies.checkout(401, "{}") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(429, "{}") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(502, "{}") is CheckoutResult.Failed)
        assertTrue(ServerReplies.checkout(500, "") is CheckoutResult.Failed)
    }

    @Test
    fun statusAnswers() {
        assertEquals(true, (ServerReplies.status(200, """{"pro":true}""") as StatusResult.Known).pro)
        assertEquals(false, (ServerReplies.status(200, """{"pro":false}""") as StatusResult.Known).pro)
        assertEquals(false, (ServerReplies.status(200, """{}""") as StatusResult.Known).pro)
        assertTrue(ServerReplies.status(401, "{}") is StatusResult.Error)
        assertTrue(ServerReplies.status(500, "{}") is StatusResult.Error)
        assertTrue(ServerReplies.status(200, "garbage") is StatusResult.Error)
    }

    @Test
    fun refundedFlagIsReadFromTheStatusAnswer() {
        val refunded = ServerReplies.status(200, """{"pro":false,"refunded":true}""") as StatusResult.Known
        assertEquals(false, refunded.pro)
        assertEquals(true, refunded.refunded)
        val normal = ServerReplies.status(200, """{"pro":true,"refunded":false}""") as StatusResult.Known
        assertEquals(false, normal.refunded)
        val oldServer = ServerReplies.status(200, """{"pro":false}""") as StatusResult.Known
        assertEquals(false, oldServer.refunded)
    }

    @Test
    fun deleteAccountAnswers() {
        assertTrue(ServerReplies.deleteAccount(200) is DeleteResult.Done)
        assertTrue(ServerReplies.deleteAccount(204) is DeleteResult.Done)
        assertTrue(ServerReplies.deleteAccount(401) is DeleteResult.Failed)
        assertTrue(ServerReplies.deleteAccount(500) is DeleteResult.Failed)
        assertTrue(ServerReplies.deleteAccount(404) is DeleteResult.Failed)
    }
}
