'use strict';

// Saves payments and Pro status in Firestore. `admin` is the firebase-admin package
// (passed in so tests can use a fake). The Admin SDK ignores the Firestore security rules,
// which is exactly why only this server can set "pro".
function createFirebaseStore(admin) {
  const db = admin.firestore();
  const FieldValue = admin.firestore.FieldValue;

  return {
    async getUser(uid) {
      const snap = await db.collection('users').doc(uid).get();
      if (!snap.exists) return null;
      const pro = snap.get('pro') === true;
      // "refunded" = Pro was taken away because the payment was refunded (the app removes Pro only for this)
      const refunded = !pro && snap.get('refundedAt') !== undefined && snap.get('refundedAt') !== null;
      return { pro: pro, refunded: refunded };
    },

    // payments/{polarOrderId} + users/{uid}.pro = true, both in ONE transaction.
    // Running the same webhook twice changes nothing ('duplicate').
    async recordPayment(p) {
      const payRef = db.collection('payments').doc(p.id);
      const userRef = db.collection('users').doc(p.uid);
      return db.runTransaction(async (t) => {
        const existing = await t.get(payRef);
        if (existing.exists) return 'duplicate';
        t.set(payRef, {
          uid: p.uid,
          provider: 'polar',
          amount: p.amount,
          currency: p.currency,
          status: 'succeeded',
          environment: p.environment,
          createdAt: FieldValue.serverTimestamp()
        });
        t.set(userRef, {
          pro: true,
          proSince: FieldValue.serverTimestamp(),
          lastPaymentId: p.id,
          refundedAt: FieldValue.delete()   // buying again clears an old refund
        }, { merge: true });
        return 'created';
      });
    },

    // Account deletion: remove the user record; keep payment records but cut the link to the person
    // (accounting and tax rules require keeping them).
    async deleteUser(uid) {
      const batch = db.batch();
      batch.delete(db.collection('users').doc(uid));
      const payments = await db.collection('payments').where('uid', '==', uid).get();
      payments.forEach(doc => {
        batch.update(doc.ref, { uid: 'deleted-user', deletedAt: FieldValue.serverTimestamp() });
      });
      await batch.commit();
    },

    // Full refund: mark the payment refunded and take Pro away again.
    async markRefunded(paymentId) {
      const payRef = db.collection('payments').doc(paymentId);
      return db.runTransaction(async (t) => {
        const snap = await t.get(payRef);
        if (!snap.exists) return 'unknown';
        const uid = snap.get('uid');
        t.update(payRef, { status: 'refunded', refundedAt: FieldValue.serverTimestamp() });
        t.set(db.collection('users').doc(uid), { pro: false, refundedAt: FieldValue.serverTimestamp() }, { merge: true });
        return 'refunded';
      });
    }
  };
}

function createFirebaseAuth(admin) {
  return {
    async verifyIdToken(token) {
      const decoded = await admin.auth().verifyIdToken(token);
      return decoded.uid;
    },

    // Removes the sign-in account itself. "Already gone" counts as success, so retrying is safe.
    async deleteUser(uid) {
      try {
        await admin.auth().deleteUser(uid);
      } catch (e) {
        if (!(e && e.code === 'auth/user-not-found')) throw e;
      }
    }
  };
}

module.exports = { createFirebaseStore, createFirebaseAuth };
