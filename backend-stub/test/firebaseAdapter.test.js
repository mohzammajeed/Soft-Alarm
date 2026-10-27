'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createFirebaseStore, createFirebaseAuth } = require('../src/firebaseAdapter');

// A minimal in-memory imitation of the parts of firebase-admin that the adapter uses
function fakeAdmin() {
  const data = new Map();   // "collection/id" -> object
  const SERVER_TS = { __serverTimestamp: true };
  const DELETE = { __delete: true };
  const applyMerge = (old, d) => { const out = Object.assign({}, old); for (const k of Object.keys(d)) { if (d[k] === DELETE) delete out[k]; else out[k] = d[k]; } return out; };
  const ref = (col, id) => ({ path: col + '/' + id, col, id });
  const snapOf = (path) => ({
    exists: data.has(path),
    get: (f) => (data.has(path) ? data.get(path)[f] : undefined)
  });
  const firestore = () => ({
    collection: (col) => ({
      doc: (id) => Object.assign(ref(col, id), { get: async () => snapOf(col + '/' + id) }),
      where: (field, op, value) => ({
        get: async () => ({
          forEach: (fn) => {
            for (const [path, d] of data.entries()) {
              if (path.startsWith(col + '/') && op === '==' && d[field] === value) fn({ id: path.slice(col.length + 1), ref: ref(col, path.slice(col.length + 1)), get: (f) => d[f] });
            }
          }
        })
      })
    }),
    batch: () => {
      const writes = [];
      return {
        delete: (r) => writes.push(() => data.delete(r.path)),
        update: (r, d) => writes.push(() => data.set(r.path, applyMerge(data.get(r.path) || {}, d))),
        commit: async () => writes.forEach(w => w())
      };
    },
    runTransaction: async (fn) => {
      const writes = [];
      const tx = {
        get: async (r) => snapOf(r.path),
        set: (r, d, opts) => writes.push(() => data.set(r.path, opts && opts.merge ? applyMerge(data.get(r.path) || {}, d) : d)),
        update: (r, d) => writes.push(() => { if (!data.has(r.path)) throw new Error('NOT_FOUND'); data.set(r.path, Object.assign({}, data.get(r.path), d)); })
      };
      const result = await fn(tx);
      writes.forEach(w => w());   // writes apply only if the function finished without error
      return result;
    }
  });
  firestore.FieldValue = { serverTimestamp: () => SERVER_TS, delete: () => DELETE };
  const admin = {
    deleted: [],
    firestore,
    auth: () => ({
      verifyIdToken: async (t) => { if (t === 'ok') return { uid: 'U1' }; throw new Error('invalid'); },
      deleteUser: async (uid) => {
        if (uid === 'MISSING') { const e = new Error('no user'); e.code = 'auth/user-not-found'; throw e; }
        if (uid === 'BROKEN') throw new Error('boom');
        admin.deleted.push(uid);
      }
    })
  };
  return { data, admin };
}

test('recordPayment saves the payment AND unlocks the user, once', async () => {
  const { admin, data } = fakeAdmin();
  const store = createFirebaseStore(admin);
  const p = { id: 'ord_1', uid: 'U1', amount: 100, currency: 'usd', environment: 'sandbox' };

  assert.equal(await store.getUser('U1'), null);
  assert.equal(await store.recordPayment(p), 'created');

  const pay = data.get('payments/ord_1');
  assert.equal(pay.uid, 'U1'); assert.equal(pay.amount, 100); assert.equal(pay.status, 'succeeded'); assert.equal(pay.provider, 'polar'); assert.equal(pay.environment, 'sandbox');
  assert.equal(JSON.stringify(pay).includes('card'), false, 'no card data is stored');
  assert.equal(data.get('users/U1').pro, true);
  assert.equal(data.get('users/U1').lastPaymentId, 'ord_1');
  assert.deepEqual(await store.getUser('U1'), { pro: true, refunded: false });

  assert.equal(await store.recordPayment(p), 'duplicate');   // webhook retry
  await store.recordPayment({ id: 'ord_2', uid: 'U2', amount: 100, currency: 'usd', environment: 'sandbox' });
  assert.equal(data.get('users/U2').pro, true);
  assert.equal(data.get('users/U1').lastPaymentId, 'ord_1');  // other users untouched
});

test('recordPayment keeps the fields the app wrote (merge, not overwrite)', async () => {
  const { admin, data } = fakeAdmin();
  data.set('users/U1', { appVersion: '1.0', lastSeen: 'x' });
  await createFirebaseStore(admin).recordPayment({ id: 'ord_1', uid: 'U1', amount: 100, currency: 'usd', environment: 'production' });
  assert.equal(data.get('users/U1').appVersion, '1.0');
  assert.equal(data.get('users/U1').pro, true);
});

test('markRefunded removes Pro, marks the payment, and ignores unknown payments', async () => {
  const { admin, data } = fakeAdmin();
  const store = createFirebaseStore(admin);
  await store.recordPayment({ id: 'ord_1', uid: 'U1', amount: 100, currency: 'usd', environment: 'sandbox' });
  assert.equal(await store.markRefunded('ord_unknown'), 'unknown');
  assert.equal(data.get('users/U1').pro, true);
  assert.equal(await store.markRefunded('ord_1'), 'refunded');
  assert.equal(data.get('payments/ord_1').status, 'refunded');
  assert.equal(data.get('users/U1').pro, false);
});

test('verifyIdToken returns the uid, and throws for a bad token', async () => {
  const { admin } = fakeAdmin();
  const auth = createFirebaseAuth(admin);
  assert.equal(await auth.verifyIdToken('ok'), 'U1');
  await assert.rejects(auth.verifyIdToken('bad'));
});

test('getUser reports refunded only after a refund, and buying again clears it', async () => {
  const { admin, data } = fakeAdmin();
  const store = createFirebaseStore(admin);
  assert.equal(await store.getUser('U1'), null);

  await store.recordPayment({ id: 'ord_1', uid: 'U1', amount: 100, currency: 'usd', environment: 'sandbox' });
  assert.deepEqual(await store.getUser('U1'), { pro: true, refunded: false });

  await store.markRefunded('ord_1');
  assert.deepEqual(await store.getUser('U1'), { pro: false, refunded: true });
  assert.ok(data.get('users/U1').refundedAt);

  await store.recordPayment({ id: 'ord_2', uid: 'U1', amount: 100, currency: 'usd', environment: 'sandbox' });
  assert.deepEqual(await store.getUser('U1'), { pro: true, refunded: false });
  assert.equal('refundedAt' in data.get('users/U1'), false, 'refundedAt is removed when the user pays again');

  data.set('users/U2', { appVersion: '1.0' });   // a user who never paid
  assert.deepEqual(await store.getUser('U2'), { pro: false, refunded: false });
});

test('deleteUser removes the user record and anonymises (keeps) only that user\'s payments', async () => {
  const { admin, data } = fakeAdmin();
  const store = createFirebaseStore(admin);
  await store.recordPayment({ id: 'ord_1', uid: 'U1', amount: 100, currency: 'usd', environment: 'sandbox' });
  await store.recordPayment({ id: 'ord_2', uid: 'U1', amount: 100, currency: 'usd', environment: 'sandbox' });
  await store.recordPayment({ id: 'ord_3', uid: 'U2', amount: 100, currency: 'usd', environment: 'sandbox' });

  await store.deleteUser('U1');

  assert.equal(data.has('users/U1'), false, 'user record is gone');
  assert.equal(data.get('payments/ord_1').uid, 'deleted-user');
  assert.equal(data.get('payments/ord_2').uid, 'deleted-user');
  assert.ok(data.get('payments/ord_1').deletedAt);
  assert.equal(data.get('payments/ord_1').amount, 100, 'the accounting record is kept');
  assert.equal(data.get('payments/ord_3').uid, 'U2', 'another user is untouched');
  assert.equal(data.has('users/U2'), true);
  assert.equal(await store.getUser('U1'), null);
  await store.deleteUser('U1');   // deleting twice is harmless
});

test('auth deleteUser removes the sign-in account; an already-missing user is fine; other errors are not hidden', async () => {
  const { admin } = fakeAdmin();
  const auth = createFirebaseAuth(admin);
  await auth.deleteUser('U1');
  assert.deepEqual(admin.deleted, ['U1']);
  await auth.deleteUser('MISSING');
  await assert.rejects(auth.deleteUser('BROKEN'), /boom/);
});
