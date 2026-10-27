package com.softalarm.app

// PLUG-IN POINT 4 (placeholder): real sign-in is not connected yet, so everyone stays a guest.
// While USE_FAKE_PAYMENTS is true the FAKE sign-in is used instead (see FakeBackend.kt).
class PlaceholderAccountGateway : AccountGateway {
    override fun current(): AccountInfo = AccountInfo(false, "Guest")

    override fun signIn(method: SignInMethod, done: (AccountResult) -> Unit) {
        done(AccountResult.NotSetUp)
    }

    override fun signOut() {
        // nothing to do yet
    }
}

/*
 HOW TO PLUG IN FIREBASE SIGN-IN (when you add the Firebase Auth SDK yourself):
 The idea: everyone is a silent Firebase "anonymous" guest. Signing in LINKS the guest to a Google or email
 account, which keeps the same user id, so a purchase made as a guest is kept.

    class FirebaseAccountGateway(
        private val getCredential: (SignInMethod, (com.google.firebase.auth.AuthCredential?) -> Unit) -> Unit
    ) : AccountGateway {
        private val auth get() = com.google.firebase.auth.FirebaseAuth.getInstance()

        override fun current(): AccountInfo {
            val u = auth.currentUser
            return AccountInfo(u != null && !u.isAnonymous, u?.email ?: "Guest")
        }

        override fun signIn(method: SignInMethod, done: (AccountResult) -> Unit) {
            val user = auth.currentUser
            if (user == null) { done(AccountResult.NotSetUp); return }
            getCredential(method) { cred ->                       // Google pop-up / email link: returns an AuthCredential
                if (cred == null) { done(AccountResult.Cancelled); return@getCredential }
                user.linkWithCredential(cred).addOnCompleteListener { t ->
                    when {
                        t.isSuccessful -> done(AccountResult.Linked(auth.currentUser?.email ?: "your account"))
                        t.exception is com.google.firebase.auth.FirebaseAuthUserCollisionException ->
                            // that account already exists: let the user decide before switching to it
                            done(AccountResult.AccountExists("your account") { cb ->
                                auth.signInWithCredential(cred).addOnCompleteListener { r ->
                                    cb(if (r.isSuccessful) AccountResult.SwitchedToExisting(auth.currentUser?.email ?: "your account")
                                       else AccountResult.Failed(r.exception?.message ?: "Sign-in failed"))
                                }
                            })
                        else -> done(AccountResult.Failed(t.exception?.message ?: "Sign-in failed"))
                    }
                }
            }
        }

        override fun signOut() {
            auth.signOut()
            auth.signInAnonymously()      // back to a fresh guest
        }
    }

 Then in AccountGatewayFactory replace PlaceholderAccountGateway() with FirebaseAccountGateway(...).
 The Google pop-up itself (Credential Manager / Google Sign-In) is the part you add; the rest is above.
 Remember to turn on the Google and Email sign-in providers in the Firebase console.
*/

object AccountGatewayFactory {
    // chooser = how the FAKE gateway asks which account to use (a pop-up); not used by the real one
    fun create(chooser: (SignInMethod, (String?) -> Unit) -> Unit): AccountGateway =
        if (ProConfig.USE_FAKE_PAYMENTS) {
            FakeAccountGateway(FakeServer.backend, chooser)
        } else {
            PlaceholderAccountGateway()      // PLUG-IN POINT 4
        }
}
