package com.softalarm.app

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

// REAL gateway: talks to your payment server (backend-stub). No Polar or Stripe code is needed
// in the app: Polar's payment page is just a web address the server gives us.
// It needs the Firebase sign-in token, which comes from the AuthTokenProvider (PLUG-IN POINT 1).
class RealProGateway(
    private val tokens: AuthTokenProvider,
    private val serverUrl: String
) : ProGateway {

    private val main = Handler(Looper.getMainLooper())

    override fun startCheckout(done: (CheckoutResult) -> Unit) {
        if (!ProConfig.serverConfigured) {
            done(CheckoutResult.NotSetUp)
            return
        }
        tokens.getIdToken { token ->
            if (token == null) {
                done(CheckoutResult.NotSetUp)
            } else {
                call("POST", "/api/create-checkout", token) { status, body ->
                    done(if (status < 0) CheckoutResult.Failed("No internet connection. Please try again.") else ServerReplies.checkout(status, body))
                }
            }
        }
    }

    override fun fetchStatus(done: (StatusResult) -> Unit) {
        if (!ProConfig.serverConfigured) {
            done(StatusResult.Error("Payments are not set up yet."))
            return
        }
        tokens.getIdToken { token ->
            if (token == null) {
                done(StatusResult.Error("Not signed in yet."))
            } else {
                call("GET", "/api/status", token) { status, body ->
                    done(if (status < 0) StatusResult.Error("No internet connection.") else ServerReplies.status(status, body))
                }
            }
        }
    }

    override fun deleteAccount(done: (DeleteResult) -> Unit) {
        if (!ProConfig.serverConfigured) {
            done(DeleteResult.Failed("Payments and accounts are not set up yet."))
            return
        }
        tokens.getIdToken { token ->
            if (token == null) {
                done(DeleteResult.Failed("Not signed in yet."))
            } else {
                call("DELETE", "/api/account", token) { status, _ ->
                    done(if (status < 0) DeleteResult.Failed("No internet connection. Please try again.") else ServerReplies.deleteAccount(status))
                }
            }
        }
    }

    // Runs the HTTP request off the main thread and reports back on the main thread.
    // status = -1 means "could not connect".
    private fun call(method: String, path: String, token: String, done: (Int, String) -> Unit) {
        Thread {
            var status = -1
            var body = ""
            try {
                val conn = URL(serverUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/json")
                status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                status = -1
            }
            main.post { done(status, body) }
        }.start()
    }
}

// PLUG-IN POINT 1 (placeholder): no Firebase sign-in is connected yet, so there is no token.
// While USE_FAKE_PAYMENTS is true this is never used.
class PlaceholderTokenProvider : AuthTokenProvider {
    override fun getIdToken(done: (String?) -> Unit) {
        done(null)
    }
}

/*
 HOW TO PLUG IN FIREBASE (when you add the Firebase SDK yourself):
 1. Add this class (it needs the firebase-auth library and your google-services.json):

    class FirebaseTokenProvider : AuthTokenProvider {
        override fun getIdToken(done: (String?) -> Unit) {
            val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            if (user == null) {
                done(null)
                return
            }
            user.getIdToken(false).addOnCompleteListener { task ->
                done(if (task.isSuccessful) task.result?.token else null)
            }
        }
    }

 2. In ProGatewayFactory below, replace PlaceholderTokenProvider() with FirebaseTokenProvider().
 3. In ProConfig.kt set USE_FAKE_PAYMENTS = false and SERVER_URL = "https://your-server".
*/

object ProGatewayFactory {
    fun create(): ProGateway =
        if (ProConfig.USE_FAKE_PAYMENTS) {
            FakeProGateway(FakeServer.backend)
        } else {
            RealProGateway(PlaceholderTokenProvider(), ProConfig.SERVER_URL)   // PLUG-IN POINT 1
        }
}
