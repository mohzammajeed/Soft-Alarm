package com.softalarm.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

class CloudResult(val uid: String?, val pro: Boolean?, val error: String?)

// Firebase in the app:
//   - signs the user in silently (anonymous account, no login screen)
//   - creates a small user record: users/{uid}  (createdAt, appVersion, lastSeen)
//   - reads the Pro status from users/{uid}.pro  (only the payment server can write that field)
// If google-services.json is missing, every function returns quickly and the app works normally.
object CloudSync {

    fun isAvailable(ctx: Context): Boolean =
        try {
            FirebaseApp.getApps(ctx.applicationContext).isNotEmpty()
        } catch (e: Exception) {
            false
        }

    fun userId(ctx: Context): String? {
        if (!isAvailable(ctx)) return null
        return try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (e: Exception) {
            null
        }
    }

    fun sync(ctx: Context, done: (CloudResult) -> Unit) {
        val app = ctx.applicationContext
        if (!isAvailable(app)) {
            done(CloudResult(null, null, "Firebase is not set up (google-services.json is missing)"))
            return
        }
        try {
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            if (user != null) {
                loadUser(app, user.uid, done)
            } else {
                auth.signInAnonymously().addOnCompleteListener { task ->
                    val u = auth.currentUser
                    if (task.isSuccessful && u != null) {
                        loadUser(app, u.uid, done)
                    } else {
                        done(CloudResult(null, null, task.exception?.message ?: "Sign-in failed"))
                    }
                }
            }
        } catch (e: Exception) {
            done(CloudResult(null, null, e.message ?: "Firebase error"))
        }
    }

    private fun loadUser(ctx: Context, uid: String, done: (CloudResult) -> Unit) {
        try {
            val ref = FirebaseFirestore.getInstance().collection("users").document(uid)
            ref.get().addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    done(CloudResult(uid, null, task.exception?.message ?: "Could not read the user record"))
                    return@addOnCompleteListener
                }
                val snap = task.result
                val pro = snap?.getBoolean("pro") ?: false
                val version = versionName(ctx)
                if (snap == null || !snap.exists()) {
                    ref.set(
                        mapOf(
                            "createdAt" to FieldValue.serverTimestamp(),
                            "appVersion" to version,
                            "lastSeen" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    ref.update(
                        mapOf(
                            "appVersion" to version,
                            "lastSeen" to FieldValue.serverTimestamp()
                        )
                    )
                }
                if (pro) ProManager.setPro(ctx, true)   // never downgrades here; refunds are handled later
                done(CloudResult(uid, pro, null))
            }
        } catch (e: Exception) {
            done(CloudResult(uid, null, e.message ?: "Firestore error"))
        }
    }

    private fun versionName(ctx: Context): String =
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
}
