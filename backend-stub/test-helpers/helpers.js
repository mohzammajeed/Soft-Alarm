'use strict';

const http = require('http');
const crypto = require('crypto');

const UID = 'abcDEF1234567890xyzUID000001';      // looks like a real Firebase uid
const OTHER_UID = 'zzzDEF1234567890xyzUID000002';
const PRODUCT_ID = '11111111-2222-4333-8444-555555555555';
const ORDER_ID = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
const SECRET = 'polar_whs_test_secret_value_123456';

const baseConfig = () => ({
  polarAccessToken: 'polar_oat_fake_token_value_123',
  polarProductId: PRODUCT_ID,
  polarWebhookSecret: SECRET,
  environment: 'sandbox',
  publicUrl: 'https://softalarm.example.com',
  contactEmail: 'help@softalarm.example.com',
  publisherName: 'Test Publisher Ltd',
  policyUpdated: '1 January 2027',
  maxCheckoutsPerHour: 10
});

// In-memory stand-ins for Polar, Firestore and Firebase Auth
function makeFakes() {
  const calls = { createCheckout: [], recordPayment: [], markRefunded: [], deleteUser: [], deleteAuthUser: [] };
  const users = new Map();
  const payments = new Map();
  let n = 0;
  const polar = {
    async createCheckout(params) {
      calls.createCheckout.push(params);
      n += 1;
      return { id: 'co_' + n, url: 'https://sandbox.polar.sh/checkout/co_' + n };
    }
  };
  const store = {
    async getUser(uid) { return users.get(uid) || null; },
    async recordPayment(p) {
      calls.recordPayment.push(p);
      if (payments.has(p.id)) return 'duplicate';
      payments.set(p.id, p);
      users.set(p.uid, { pro: true, refunded: false });
      return 'created';
    },
    async deleteUser(uid) { calls.deleteUser.push(uid); users.delete(uid); },
    async markRefunded(id) {
      calls.markRefunded.push(id);
      const p = payments.get(id);
      if (!p) return 'unknown';
      users.set(p.uid, { pro: false, refunded: true });
      return 'refunded';
    }
  };
  const auth = {
    async verifyIdToken(token) {
      if (token === 'good-token') return UID;
      if (token === 'other-token') return OTHER_UID;
      throw new Error('bad token');
    },
    async deleteUser(uid) { calls.deleteAuthUser.push(uid); }
  };
  return { polar, store, auth, calls, users, payments };
}

// Standard Webhooks headers, signed the way Polar signs them
function polarHeaders(body, secret, opts) {
  const o = opts || {};
  const id = o.id || 'msg_test_1';
  const ts = String(Math.floor((o.nowMs === undefined ? Date.now() : o.nowMs) / 1000));
  const key = o.key || Buffer.from(secret, 'utf8');
  const sig = crypto.createHmac('sha256', key).update(id + '.' + ts + '.' + body).digest('base64');
  return { 'webhook-id': id, 'webhook-timestamp': ts, 'webhook-signature': 'v1,' + sig };
}

function orderPaidEvent(overrides) {
  const order = Object.assign({
    id: ORDER_ID,
    status: 'paid',
    paid: true,
    net_amount: 100,
    total_amount: 100,
    currency: 'usd',
    product_id: PRODUCT_ID,
    metadata: { uid: UID, app: 'softalarm' }
  }, overrides || {});
  return { type: 'order.paid', timestamp: '2026-10-22T10:00:00Z', data: order };
}

function listen(handler) {
  return new Promise(resolve => {
    const server = http.createServer(handler);
    server.unref();   // a failing test must never leave the test runner waiting on an open server
    server.listen(0, '127.0.0.1', () => {
      resolve({
        url: 'http://127.0.0.1:' + server.address().port,
        close: () => new Promise(r => server.close(r))
      });
    });
  });
}

module.exports = { UID, OTHER_UID, PRODUCT_ID, ORDER_ID, SECRET, baseConfig, makeFakes, polarHeaders, orderPaidEvent, listen };
