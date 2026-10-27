'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const { createPolarClient, verifyPolarWebhook } = require('../src/polarApi');
const { createApp } = require('../src/app');
const H = require('../test-helpers/helpers');

// A tiny fake "Polar" web server, so the REAL Polar client code is tested end to end
function fakePolar(reply) {
  const seen = [];
  return H.listen((req, res) => {
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      seen.push({ method: req.method, url: req.url, headers: req.headers, json: body ? JSON.parse(body) : null });
      const out = reply(seen.length);
      res.writeHead(out.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(out.json));
    });
  }).then(s => Object.assign(s, { seen }));
}

test('createCheckout sends the right request to Polar', async () => {
  const fp = await fakePolar(() => ({ status: 201, json: { id: 'co_1', url: 'https://polar.sh/checkout/co_1' } }));
  const client = createPolarClient({ accessToken: 'polar_oat_abc', baseUrl: fp.url });
  const co = await client.createCheckout({ productId: H.PRODUCT_ID, uid: H.UID, successUrl: 'https://x.example/thanks' });
  assert.deepEqual(co, { id: 'co_1', url: 'https://polar.sh/checkout/co_1' });

  const req = fp.seen[0];
  assert.equal(req.method, 'POST');
  assert.equal(req.url, '/v1/checkouts');
  assert.equal(req.headers.authorization, 'Bearer polar_oat_abc');
  assert.deepEqual(req.json, {
    products: [H.PRODUCT_ID],
    metadata: { uid: H.UID, app: 'softalarm' },
    success_url: 'https://x.example/thanks'
  });
  await fp.close();
});

test('Polar errors become an Error with Polar\'s message (text and list styles)', async () => {
  let fp = await fakePolar(() => ({ status: 401, json: { detail: 'Invalid token' } }));
  let client = createPolarClient({ accessToken: 'polar_oat_bad', baseUrl: fp.url });
  await assert.rejects(client.createCheckout({ productId: H.PRODUCT_ID, uid: H.UID, successUrl: 'https://x/t' }), /HTTP 401.*Invalid token/);
  await fp.close();

  fp = await fakePolar(() => ({ status: 422, json: { detail: [{ msg: 'Field required' }, { msg: 'Bad URL' }] } }));
  client = createPolarClient({ accessToken: 'polar_oat_bad', baseUrl: fp.url });
  await assert.rejects(client.createCheckout({ productId: H.PRODUCT_ID, uid: H.UID, successUrl: 'https://x/t' }), /Field required; Bad URL/);
  await fp.close();
});

test('webhook signature: good, tampered body, wrong secret, expired, missing headers, junk', () => {
  const body = '{"type":"order.paid"}';
  const now = Date.now();
  const hdr = H.polarHeaders(body, H.SECRET, { nowMs: now });
  assert.equal(verifyPolarWebhook(body, hdr, H.SECRET, now), true);
  assert.equal(verifyPolarWebhook(body + ' ', hdr, H.SECRET, now), false, 'tampered body');
  assert.equal(verifyPolarWebhook(body, hdr, H.SECRET + 'x', now), false, 'wrong secret');
  assert.equal(verifyPolarWebhook(body, hdr, H.SECRET, now + 10 * 60 * 1000), false, 'too old');
  assert.equal(verifyPolarWebhook(body, hdr, H.SECRET, now - 10 * 60 * 1000), false, 'from the future');
  assert.equal(verifyPolarWebhook(body, {}, H.SECRET, now), false, 'no headers');
  assert.equal(verifyPolarWebhook(body, hdr, '', now), false, 'no secret');
  assert.equal(verifyPolarWebhook(body, Object.assign({}, hdr, { 'webhook-id': 'other' }), H.SECRET, now), false, 'id is part of the signature');
  assert.equal(verifyPolarWebhook(body, Object.assign({}, hdr, { 'webhook-timestamp': 'abc' }), H.SECRET, now), false);
  assert.equal(verifyPolarWebhook(body, Object.assign({}, hdr, { 'webhook-signature': 'v2,abc' }), H.SECRET, now), false);
});

test('webhook signature: several signatures in the header (key rotation) are accepted if one matches', () => {
  const body = '{"a":1}';
  const now = Date.now();
  const good = H.polarHeaders(body, H.SECRET, { nowMs: now });
  const hdr = Object.assign({}, good, { 'webhook-signature': 'v1,AAAA v1,BBBB ' + good['webhook-signature'] });
  assert.equal(verifyPolarWebhook(body, hdr, H.SECRET, now), true);
});

test('webhook signature: also accepts the standard whsec_ base64 key style', () => {
  const body = '{"a":1}';
  const now = Date.now();
  const raw = crypto.randomBytes(24);
  const secret = 'whsec_' + raw.toString('base64');
  const stdHeaders = H.polarHeaders(body, secret, { nowMs: now, key: raw });          // signed with the decoded key
  const polarHeaders = H.polarHeaders(body, secret, { nowMs: now });                   // signed with the secret text
  assert.equal(verifyPolarWebhook(body, stdHeaders, secret, now), true);
  assert.equal(verifyPolarWebhook(body, polarHeaders, secret, now), true);
});

test('END TO END: app asks to pay -> real Polar client -> fake Polar -> webhook -> user is Pro', async () => {
  const fp = await fakePolar(() => ({ status: 201, json: { id: 'co_e2e', url: 'https://sandbox.polar.sh/checkout/co_e2e' } }));
  const fakes = H.makeFakes();
  const config = H.baseConfig();
  const handler = createApp({
    config,
    polar: createPolarClient({ accessToken: config.polarAccessToken, baseUrl: fp.url }),
    store: fakes.store,
    auth: fakes.auth
  });
  const server = await H.listen(handler);
  const auth = { headers: { Authorization: 'Bearer good-token' } };

  // 1. the app asks for a payment page
  const r1 = await fetch(server.url + '/api/create-checkout', Object.assign({ method: 'POST' }, auth));
  assert.equal(r1.status, 200);
  assert.equal((await r1.json()).checkoutUrl, 'https://sandbox.polar.sh/checkout/co_e2e');
  assert.equal(fp.seen[0].json.metadata.uid, H.UID);
  assert.equal(fp.seen[0].json.products[0], H.PRODUCT_ID);

  // 2. not Pro yet
  const status = async () => (await (await fetch(server.url + '/api/status', auth)).json()).pro;
  assert.equal(await status(), false);

  // 3. Polar confirms the payment (metadata is copied from the checkout onto the order)
  const meta = fp.seen[0].json.metadata;
  const body = JSON.stringify(H.orderPaidEvent({ metadata: meta }));
  const r2 = await fetch(server.url + '/webhook/polar', { method: 'POST', body, headers: H.polarHeaders(body, config.polarWebhookSecret) });
  assert.equal(r2.status, 200);

  // 4. now Pro, and asking to pay again is refused
  assert.equal(await status(), true);
  const r3 = await fetch(server.url + '/api/create-checkout', Object.assign({ method: 'POST' }, auth));
  assert.equal(r3.status, 409);
  await server.close();
  await fp.close();
});
