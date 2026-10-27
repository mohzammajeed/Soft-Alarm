package com.softalarm.app

import org.json.JSONObject

// Turns the payment server's HTTP answers into results the app understands.
// (Plain Kotlin + org.json, so it is tested on a computer too.)
object ServerReplies {

    fun checkout(httpStatus: Int, body: String): CheckoutResult {
        return when {
            httpStatus == 409 -> CheckoutResult.AlreadyPro
            httpStatus == 401 -> CheckoutResult.Failed("Please sign in again (close and reopen the app), then retry.")
            httpStatus == 429 -> CheckoutResult.Failed("Too many attempts. Please try again in a little while.")
            httpStatus in 200..299 -> {
                val url = try {
                    JSONObject(body).optString("checkoutUrl", "")
                } catch (e: Exception) {
                    ""
                }
                // Only a secure https page is ever opened
                if (url.startsWith("https://")) CheckoutResult.Url(url)
                else CheckoutResult.Failed("The payment page address was not valid. Please try again.")
            }
            else -> CheckoutResult.Failed("The payment service is busy. Please try again.")
        }
    }

    fun deleteAccount(httpStatus: Int): DeleteResult {
        return when {
            httpStatus in 200..299 -> DeleteResult.Done
            httpStatus == 401 -> DeleteResult.Failed("Please sign in again (close and reopen the app), then retry.")
            else -> DeleteResult.Failed("Your account could not be deleted right now. Please try again later.")
        }
    }

    fun status(httpStatus: Int, body: String): StatusResult {
        if (httpStatus == 401) return StatusResult.Error("Please sign in again (close and reopen the app).")
        if (httpStatus !in 200..299) return StatusResult.Error("The server could not check your payment (error $httpStatus).")
        return try {
            val json = JSONObject(body)
            StatusResult.Known(json.optBoolean("pro", false), json.optBoolean("refunded", false))
        } catch (e: Exception) {
            StatusResult.Error("The server answer was not understood.")
        }
    }
}
