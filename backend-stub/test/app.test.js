'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createApp } = require('../src/app');
const { loadPages } = require('../src/pages');
const H = require('../test-helpers/helpers');

async function setup(configOverrides, clock) {
  const fakes = H.makeFakes();
  const config = Object.assign(H.baseConfig(), configOverrides || {});
  const handler = createApp({ config, polar: fakes.polar, store: fakes.store, auth: fakes.auth, now: clock, pages: loadPages(config) });
  const server = await H.listen(handler);
  return { fakes, config, server };
}

const post = (server, path, opts) => fetch(server.url + path, Object.assign({ method: 'POST' }, opts));
const withToken = (t) => ({ headers: { Authorization: 'Bearer ' + (t || 'good-token') } });
const webhook = (server, event, opts) => {
  const body = JSON.stringify(event);
  return post(server, '/webhook/polar', { body, headers: H.polarHeaders(body, (opts && opts.secret) || H.SECRET, opts) });
};

test('health check answers ok; unknown address gives 404', async () => {
  const { server } = await setup();
  const r = await fetch(server.url + '/health');
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true });
  assert.equal((await fetch(server.url + '/nope')).status, 404);
  await server.close();
});

test('thank-you page opens the app through softalarm://paid', async () => {
  const { server } = await setup();
  const r = await fetch(server.url + '/thanks');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-type'), /text\/html/);
  assert.match(await r.text(), /softalarm:\/\/paid/);
  await server.close();
});

// ---------- create checkout ----------

test('create-checkout without a token, or with a bad token, is refused (401)', async () => {
  const { server, fakes } = await setup();
  assert.equal((await post(server, '/api/create-checkout')).status, 401);
  assert.equal((await post(server, '/api/create-checkout', withToken('nonsense'))).status, 401);
  assert.equal(fakes.calls.createCheckout.length, 0);
  await server.close();
});

test('create-checkout with a good token returns the Polar payment page address', async () => {
  const { server, fakes } = await setup();
  const r = await post(server, '/api/create-checkout', withToken());
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.checkoutUrl, 'https://sandbox.polar.sh/checkout/co_1');
  assert.equal(JSON.stringify(j).includes('polar_oat'), false, 'the access token must never be sent to the app');
  assert.deepEqual(fakes.calls.createCheckout[0], {
    productId: H.PRODUCT_ID, uid: H.UID, successUrl: 'https://softalarm.example.com/thanks'
  });
  await server.close();
});

test('the product (price) is decided by the server: whatever the app sends is ignored', async () => {
  const { server, fakes } = await setup();
  await post(server, '/api/create-checkout', {
    headers: { Authorization: 'Bearer good-token', 'Content-Type': 'application/json' },
    body: JSON.stringify({ productId: 'cheap-product', amount: 1, uid: 'someone-else' })
  });
  assert.equal(fakes.calls.createCheckout[0].productId, H.PRODUCT_ID);
  assert.equal(fakes.calls.createCheckout[0].uid, H.UID);
  await server.close();
});

test('an already-Pro user gets 409 and no checkout is created', async () => {
  const { server, fakes } = await setup();
  fakes.users.set(H.UID, { pro: true });
  const r = await post(server, '/api/create-checkout', withToken());
  assert.equal(r.status, 409);
  assert.equal((await r.json()).alreadyPro, true);
  assert.equal(fakes.calls.createCheckout.length, 0);
  await server.close();
});

test('tapping twice within 20 minutes reuses the same checkout page', async () => {
  let t = 1_000_000_000_000;
  const { server, fakes } = await setup({}, () => t);
  const a = await (await post(server, '/api/create-checkout', withToken())).json();
  t += 60 * 1000;
  const b = await (await post(server, '/api/create-checkout', withToken())).json();
  assert.equal(a.checkoutUrl, b.checkoutUrl);
  assert.equal(fakes.calls.createCheckout.length, 1);
  t += 30 * 60 * 1000;   // 30 minutes later: a fresh one
  const c = await (await post(server, '/api/create-checkout', withToken())).json();
  assert.notEqual(c.checkoutUrl, a.checkoutUrl);
  assert.equal(fakes.calls.createCheckout.length, 2);
  await server.close();
});

test('too many new checkouts in one hour gives 429 (other users unaffected)', async () => {
  let t = 1_000_000_000_000;
  const { server } = await setup({ maxCheckoutsPerHour: 2 }, () => t);
  const go = async (token) => { const r = await post(server, '/api/create-checkout', withToken(token)); t += 21 * 60 * 1000; return r.status; };
  assert.equal(await go(), 200);   // 0:00  new checkout
  assert.equal(await go(), 200);   // 0:21  new checkout (the 20-minute reuse has ended)
  assert.equal(await go(), 429);   // 0:42  a 3rd new checkout inside one hour is refused
  assert.equal(await go('other-token'), 200);   // another user is not affected
  await server.close();
});

test('if Polar fails, the app gets a friendly 502 without any details', async () => {
  const { server, fakes } = await setup();
  fakes.polar.createCheckout = async () => { throw new Error('Polar error (HTTP 401): bad token polar_oat_REAL'); };
  const r = await post(server, '/api/create-checkout', withToken());
  assert.equal(r.status, 502);
  const text = await r.text();
  assert.equal(text.includes('polar_oat'), false);
  assert.equal(text.includes('bad token'), false);
  await server.close();
});

// ---------- status ----------

test('status shows pro false, then true after payment, and needs a token', async () => {
  const { server, fakes } = await setup();
  const status = async () => (await (await fetch(server.url + '/api/status', withToken())).json()).pro;
  assert.equal(await status(), false);
  fakes.users.set(H.UID, { pro: true });
  assert.equal(await status(), true);
  assert.equal((await fetch(server.url + '/api/status')).status, 401);
  await server.close();
});

// ---------- webhook ----------

test('a forged webhook signature is rejected (401) and nothing is saved', async () => {
  const { server, fakes } = await setup();
  const body = JSON.stringify(H.orderPaidEvent());
  const headers = H.polarHeaders(body, H.SECRET);
  headers['webhook-signature'] = 'v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=';
  assert.equal((await post(server, '/webhook/polar', { body, headers })).status, 401);
  assert.equal(fakes.calls.recordPayment.length, 0);
  await server.close();
});

test('a webhook signed with the wrong secret, with missing headers, or replayed late is rejected', async () => {
  const { server, fakes } = await setup();
  assert.equal((await webhook(server, H.orderPaidEvent(), { secret: 'someone_elses_secret_value_999' })).status, 401);
  const body = JSON.stringify(H.orderPaidEvent());
  assert.equal((await post(server, '/webhook/polar', { body })).status, 401);
  assert.equal((await webhook(server, H.orderPaidEvent(), { nowMs: Date.now() - 20 * 60 * 1000 })).status, 401);
  assert.equal(fakes.calls.recordPayment.length, 0);
  await server.close();
});

test('a real order.paid unlocks Pro and saves the payment', async () => {
  const { server, fakes } = await setup();
  const r = await webhook(server, H.orderPaidEvent());
  assert.equal(r.status, 200);
  assert.deepEqual(fakes.calls.recordPayment, [{ id: H.ORDER_ID, uid: H.UID, amount: 100, currency: 'usd', environment: 'sandbox' }]);
  assert.equal(fakes.users.get(H.UID).pro, true);
  await server.close();
});

test('a paid order in another currency still unlocks (Polar converts for the buyer)', async () => {
  const { server, fakes } = await setup();
  await webhook(server, H.orderPaidEvent({ currency: 'PKR', net_amount: 28000, total_amount: 28000 }));
  assert.equal(fakes.calls.recordPayment[0].currency, 'pkr');
  assert.equal(fakes.users.get(H.UID).pro, true);
  await server.close();
});

test('the same webhook sent twice is harmless', async () => {
  const { server, fakes } = await setup();
  assert.equal((await webhook(server, H.orderPaidEvent())).status, 200);
  assert.equal((await webhook(server, H.orderPaidEvent(), { id: 'msg_test_2' })).status, 200);
  assert.equal(fakes.payments.size, 1);
  await server.close();
});

for (const [name, override] of [
  ['an order that is not paid yet', { status: 'pending', paid: false }],
  ['a different product', { product_id: '99999999-2222-4333-8444-555555555555' }],
  ['no user id in the order', { metadata: { app: 'softalarm' } }],
  ['a fake-looking user id', { metadata: { uid: '../../etc', app: 'softalarm' } }],
  ['an order without an id', { id: undefined }]
]) {
  test('order is ignored (still answers 200): ' + name, async () => {
    const { server, fakes } = await setup();
    const r = await webhook(server, H.orderPaidEvent(override));
    assert.equal(r.status, 200);
    assert.equal(fakes.calls.recordPayment.length, 0);
    assert.equal(fakes.users.has(H.UID), false);
    await server.close();
  });
}

test('a full refund takes Pro away; a partial refund does not', async () => {
  const { server, fakes } = await setup();
  await webhook(server, H.orderPaidEvent());
  assert.equal(fakes.users.get(H.UID).pro, true);

  await webhook(server, { type: 'order.refunded', data: { id: H.ORDER_ID, status: 'partially_refunded' } });
  assert.equal(fakes.users.get(H.UID).pro, true);

  assert.equal((await webhook(server, { type: 'order.refunded', data: { id: H.ORDER_ID, status: 'refunded' } })).status, 200);
  assert.equal(fakes.users.get(H.UID).pro, false);
  await server.close();
});

test('if saving fails the server answers 500 so Polar retries', async () => {
  const { server, fakes } = await setup();
  fakes.store.recordPayment = async () => { throw new Error('Firestore down'); };
  assert.equal((await webhook(server, H.orderPaidEvent())).status, 500);
  await server.close();
});

test('an unrelated event type is accepted and ignored', async () => {
  const { server, fakes } = await setup();
  assert.equal((await webhook(server, { type: 'customer.created', data: {} })).status, 200);
  assert.equal(fakes.calls.recordPayment.length, 0);
  await server.close();
});

test('a huge request body is refused', async () => {
  const { server } = await setup();
  const r = await post(server, '/webhook/polar', { body: 'x'.repeat(200 * 1024) }).catch(() => ({ status: 'closed' }));
  assert.ok(r.status === 413 || r.status === 'closed');
  await server.close();
});

test('status says refunded after a full refund, and the user can buy again', async () => {
  const { server, fakes } = await setup();
  const status = async () => (await (await fetch(server.url + '/api/status', withToken())).json());
  assert.deepEqual(await status(), { pro: false, refunded: false });

  await webhook(server, H.orderPaidEvent());
  assert.deepEqual(await status(), { pro: true, refunded: false });

  await webhook(server, { type: 'order.refunded', data: { id: H.ORDER_ID, status: 'refunded' } });
  assert.deepEqual(await status(), { pro: false, refunded: true });

  const again = await post(server, '/api/create-checkout', withToken());
  assert.equal(again.status, 200, 'a refunded user may buy again');
  assert.equal(fakes.calls.createCheckout.length, 1);
  await server.close();
});

test('a contradictory record (Pro and refunded) never tells the app to remove Pro', async () => {
  const { server, fakes } = await setup();
  fakes.users.set(H.UID, { pro: true, refunded: true });
  const j = await (await fetch(server.url + '/api/status', withToken())).json();
  assert.deepEqual(j, { pro: true, refunded: false });
  await server.close();
});

// ---------- delete account + legal pages ----------

const del = (server, token) => fetch(server.url + '/api/account', Object.assign({ method: 'DELETE' }, token === null ? {} : withToken(token)));

test('DELETE /api/account needs a valid sign-in token', async () => {
  const { server, fakes } = await setup();
  assert.equal((await del(server, null)).status, 401);
  assert.equal((await del(server, 'nonsense')).status, 401);
  assert.equal(fakes.calls.deleteUser.length, 0);
  assert.equal(fakes.calls.deleteAuthUser.length, 0);
  await server.close();
});

test('deleting an account removes the user record and the sign-in account, for that user only', async () => {
  const { server, fakes } = await setup();
  fakes.users.set(H.UID, { pro: true, refunded: false });
  fakes.users.set(H.OTHER_UID, { pro: true, refunded: false });
  const r = await del(server);
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { deleted: true });
  assert.deepEqual(fakes.calls.deleteUser, [H.UID]);
  assert.deepEqual(fakes.calls.deleteAuthUser, [H.UID]);
  assert.equal(fakes.users.has(H.UID), false);
  assert.equal(fakes.users.has(H.OTHER_UID), true, 'another user is untouched');
  await server.close();
});

test('the user id in the request body cannot be used to delete someone else', async () => {
  const { server, fakes } = await setup();
  await fetch(server.url + '/api/account?uid=' + H.OTHER_UID, {
    method: 'DELETE',
    headers: { Authorization: 'Bearer good-token', 'Content-Type': 'application/json' },
    body: JSON.stringify({ uid: H.OTHER_UID })
  });
  assert.deepEqual(fakes.calls.deleteUser, [H.UID]);
  await server.close();
});

test('if the database deletion fails the answer is 500 and the sign-in account is kept (so it can be retried)', async () => {
  const { server, fakes } = await setup();
  fakes.store.deleteUser = async () => { throw new Error('Firestore down'); };
  assert.equal((await del(server)).status, 500);
  assert.equal(fakes.calls.deleteAuthUser.length, 0);
  await server.close();
});

test('after deleting, the same sign-in token no longer gets a stale checkout page', async () => {
  const { server, fakes } = await setup();
  await post(server, '/api/create-checkout', withToken());
  assert.equal(fakes.calls.createCheckout.length, 1);
  await del(server);
  await post(server, '/api/create-checkout', withToken());
  assert.equal(fakes.calls.createCheckout.length, 2, 'the cached checkout page was forgotten');
  await server.close();
});

test('privacy policy and delete-account pages are served with the publisher details filled in', async () => {
  const { server } = await setup();
  for (const path of ['/privacy', '/delete-account']) {
    const r = await fetch(server.url + path);
    assert.equal(r.status, 200);
    assert.match(r.headers.get('content-type'), /text\/html/);
    const html = await r.text();
    assert.match(html, /help@softalarm\.example\.com/);
    assert.match(html, /Test Publisher Ltd/);
    assert.match(html, /1 January 2027/);
    assert.equal(html.includes('{{'), false, 'no template placeholder may be left in the page');
  }
  await server.close();
});

test('the privacy policy tells the truth about what the app collects and shares', async () => {
  const { server } = await setup();
  const html = await (await fetch(server.url + '/privacy')).text();
  for (const must of ['Firebase', 'Polar', 'Delete my account', 'never sell', 'no ads', 'anonymised', 'not directed at children']) {
    assert.ok(html.toLowerCase().includes(must.toLowerCase()), 'policy should mention: ' + must);
  }
  await server.close();
});

test('page text cannot inject HTML through the settings', async () => {
  const fakes = H.makeFakes();
  const config = Object.assign(H.baseConfig(), { publisherName: '<script>alert(1)</script>', contactEmail: 'a"b@x.com' });
  const server = await H.listen(createApp({ config, polar: fakes.polar, store: fakes.store, auth: fakes.auth, pages: loadPages(config) }));
  const html = await (await fetch(server.url + '/privacy')).text();
  assert.equal(html.includes('<script>alert(1)'), false);
  assert.ok(html.includes('&lt;script&gt;'));
  await server.close();
});
