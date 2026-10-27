package com.softalarm.app

// ---------------------------------------------------------------------------------------
// The payment flow talks to these small interfaces only. Today they are filled with FAKES
// (no network, no Firebase, no Polar). Later you plug in the real ones. See PLUGIN_POINTS.md.
// This file has NO Android code, so it can be unit-tested on any computer.
// ---------------------------------------------------------------------------------------

// The answer to "give me a payment page"
sealed class CheckoutResult {
    class Url(val url: String) : CheckoutResult()      // open this page so the user can pay
    object AlreadyPro : CheckoutResult()               // this account already has Pro
    object NotSetUp : CheckoutResult()                 // server / sign-in is not set up yet
    class Failed(val reason: String) : CheckoutResult()
}

// The answer to "has this user paid?"
sealed class StatusResult {
    // refunded = the server took Pro away because the payment was refunded (the ONLY reason the app removes Pro)
    class Known(val pro: Boolean, val refunded: Boolean = false) : StatusResult()
    class Error(val reason: String) : StatusResult()
}

// The answer to "delete my account"
sealed class DeleteResult {
    object Done : DeleteResult()
    class Failed(val reason: String) : DeleteResult()
}

// Talks to the payment server (real) or pretends to (fake)
interface ProGateway {
    fun startCheckout(done: (CheckoutResult) -> Unit)
    fun fetchStatus(done: (StatusResult) -> Unit)

    // Deletes the user's online account and data (required by app stores). Gateways that cannot do it say so.
    fun deleteAccount(done: (DeleteResult) -> Unit) {
        done(DeleteResult.Failed("Deleting your account is not available yet."))
    }
}

// Remembers Pro on the phone (a cache of what the server said)
interface ProStore {
    fun isPro(): Boolean
    fun setPro(value: Boolean)
    fun isAwaiting(): Boolean           // true while the user is away paying in the browser
    fun setAwaiting(value: Boolean)
}

// PLUG-IN POINT 1: gives the Firebase sign-in token that proves who the user is.
interface AuthTokenProvider {
    fun getIdToken(done: (String?) -> Unit)
}

// ---------------------------------------------------------------------------------------
// Accounts: Pro belongs to an ACCOUNT, so it survives a reinstall or a new phone.
// Everyone starts as a silent "guest". Signing in (optional) turns the guest into a permanent account.
// ---------------------------------------------------------------------------------------

enum class SignInMethod { GOOGLE, EMAIL }

class AccountInfo(val signedIn: Boolean, val label: String)     // label = email, or "Guest"

sealed class AccountResult {
    class Linked(val label: String) : AccountResult()                   // the guest became this account (purchases kept)
    class SwitchedToExisting(val label: String) : AccountResult()       // signed in to an account that already existed
    // This account already exists. Call switchTo only AFTER the user agreed to leave the guest session.
    class AccountExists(val label: String, val switchTo: (done: (AccountResult) -> Unit) -> Unit) : AccountResult()
    object Cancelled : AccountResult()
    object NotSetUp : AccountResult()
    class Failed(val reason: String) : AccountResult()
}

// PLUG-IN POINT 4: real sign-in (Firebase Auth: Google + email). See AccountGateways.kt.
interface AccountGateway {
    fun current(): AccountInfo
    fun signIn(method: SignInMethod, done: (AccountResult) -> Unit)
    fun signOut()
}
