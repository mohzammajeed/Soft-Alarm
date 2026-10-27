'use strict';

const { verifyPolarWebhook } = require('./polarApi');

const UID_RE = /^[A-Za-z0-9]{10,128}$/;   // Firebase user ids
const MAX_BODY = 100 * 1024;
const CHECKOUT_REUSE_MS = 20 * 60 * 1000;

const THANKS_HTML = '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">' +
  '<meta name="viewport" content="width=device-width, initial-scale=1"><title>SoftAlarm Pro</title>' +
  '<style>body{font-family:system-ui,sans-serif;background:linear-gradient(160deg,#FFF8F2,#FFDCC2,#FFB6AE);min-height:100vh;margin:0;display:flex;align-items:center;justify-content:center;color:#3D2C3A}' +
  '.c{background:rgba(255,255,255,.85);border-radius:24px;padding:32px 24px;max-width:360px;margin:16px;text-align:center}' +
  'a{display:inline-block;background:#FF7A5C;color:#fff;text-decoration:none;border-radius:14px;padding:14px 28px;font-weight:600;margin-top:12px}</style></head>' +
  '<body><div class="c"><h1>Thank you!</h1><p>Your payment was received. Go back to SoftAlarm to enjoy your Pro voices.</p>' +
  '<a href="softalarm://paid">Open SoftAlarm</a></div>' +
  '<script>setTimeout(function(){location.href="softalarm://paid"},800)</script></body></html>';

// All the server logic. Everything outside (Polar, Firebase, clock) is passed in,
// so the tests can use fakes and the real server uses the real services.
//   polar:  { createCheckout({productId, uid, successUrl}) -> {id, url} }
//   store:  { getUser(uid) -> {pro}|null, recordPayment({...}) -> 'created'|'duplicate', markRefunded(id) -> 'refunded'|'unknown' }
//   auth:   { verifyIdToken(token) -> uid }   (throws if the token is bad)
function createApp({ config, polar, store, auth, now, log, pages }) {
  const staticPages = pages || {};
  const clock = now || (() => Date.now());
  const logger = log || (() => {});
  const createHits = new Map();      // uid -> times of recent checkout creations
  const recentCheckouts = new Map(); // uid -> { url, at }

  function send(res, code, obj) {
    res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(obj));
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      let data = '';
      req.on('data', c => {
        data += c;
        if (data.length > MAX_BODY) {
          const e = new Error('body too large');
          e.status = 413;
          reject(e);
          req.destroy();
        }
      });
      req.on('end', () => resolve(data));
      req.on('error', reject);
    });
  }

  function bearer(req) {
    const m = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '');
    return m ? m[1] : null;
  }

  async function authenticate(req) {
    const token = bearer(req);
    if (!token) return null;
    try {
      const uid = await auth.verifyIdToken(token);
      return UID_RE.test(uid || '') ? uid : null;
    } catch (e) {
      return null;
    }
  }

  function tooManyAttempts(uid) {
    const t = clock();
    const recent = (createHits.get(uid) || []).filter(x => t - x < 3600 * 1000);
    if (recent.length >= config.maxCheckoutsPerHour) {
      createHits.set(uid, recent);
      return true;
    }
    recent.push(t);
    createHits.set(uid, recent);
    return false;
  }

  // The app asks: "give me a payment page for me". The user is identified by their Firebase token.
  async function handleCreateCheckout(req, res) {
    const uid = await authenticate(req);
    if (!uid) return send(res, 401, { error: 'Please sign in again.' });

    const user = await store.getUser(uid);
    if (user && user.pro) return send(res, 409, { alreadyPro: true });

    // Tapping the button twice shortly after should open the same page, not make many
    const cached = recentCheckouts.get(uid);
    if (cached && clock() - cached.at < CHECKOUT_REUSE_MS) return send(res, 200, { checkoutUrl: cached.url });

    if (tooManyAttempts(uid)) return send(res, 429, { error: 'Too many attempts. Please try again later.' });

    try {
      const checkout = await polar.createCheckout({
        productId: config.polarProductId,
        uid: uid,
        successUrl: config.publicUrl + '/thanks'
      });
      recentCheckouts.set(uid, { url: checkout.url, at: clock() });
      return send(res, 200, { checkoutUrl: checkout.url });
    } catch (e) {
      logger({ evt: 'polar_error', message: e.message });
      return send(res, 502, { error: 'The payment service is busy. Please try again.' });
    }
  }

  async function handleStatus(req, res) {
    const uid = await authenticate(req);
    if (!uid) return send(res, 401, { error: 'Please sign in again.' });
    const user = await store.getUser(uid);
    return send(res, 200, { pro: !!(user && user.pro), refunded: !!(user && user.refunded && !user.pro) });
  }

  // "Delete my account": removes the user record (payments are anonymised) and the sign-in account.
  async function handleDeleteAccount(req, res) {
    const uid = await authenticate(req);
    if (!uid) return send(res, 401, { error: 'Please sign in again.' });
    await store.deleteUser(uid);   // if this fails -> 500, nothing else is touched, and the app can retry
    await auth.deleteUser(uid);
    createHits.delete(uid);
    recentCheckouts.delete(uid);
    logger({ evt: 'account_deleted' });   // the user id is deliberately not logged
    return send(res, 200, { deleted: true });
  }

  function sendHtml(res, html) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(html);
  }

  // Polar tells us an order was paid / refunded. Only trusted after the signature is checked.
  async function processEvent(event) {
    const order = event.data || {};

    if (event.type === 'order.paid') {
      if (order.status !== 'paid') return 'ignored:not-paid';
      if (order.product_id !== config.polarProductId) return 'ignored:other-product';
      const meta = order.metadata || {};
      if (!meta.uid || !UID_RE.test(meta.uid)) return 'ignored:no-user';
      if (!order.id) return 'ignored:no-id';
      const amount = order.net_amount !== undefined && order.net_amount !== null ? order.net_amount : (order.total_amount || 0);
      const result = await store.recordPayment({
        id: String(order.id),
        uid: meta.uid,
        amount: amount,
        currency: String(order.currency || '').toLowerCase(),
        environment: config.environment
      });
      return 'recorded:' + result;
    }

    if (event.type === 'order.refunded') {
      if (order.status === 'refunded' && order.id) return 'refund:' + (await store.markRefunded(String(order.id)));
      return 'ignored:partial-refund';
    }

    return 'ignored:type';
  }

  async function handleWebhook(req, res) {
    const raw = await readBody(req);
    if (!verifyPolarWebhook(raw, req.headers, config.polarWebhookSecret, clock())) {
      return send(res, 401, { ok: false });
    }
    let event;
    try {
      event = JSON.parse(raw);
    } catch (e) {
      return send(res, 400, { ok: false });
    }
    // If saving fails this throws -> 500 -> Polar retries automatically.
    const result = await processEvent(event);
    logger({ evt: 'webhook', type: event.type, result: result });
    return send(res, 200, { received: true });
  }

  return async function handler(req, res) {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/health') return send(res, 200, { ok: true });
      if (req.method === 'GET' && url.pathname === '/thanks') {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        return res.end(THANKS_HTML);
      }
      if (req.method === 'GET' && url.pathname === '/privacy' && staticPages.privacy) return sendHtml(res, staticPages.privacy);
      if (req.method === 'GET' && url.pathname === '/delete-account' && staticPages.deleteAccount) return sendHtml(res, staticPages.deleteAccount);
      if (req.method === 'DELETE' && url.pathname === '/api/account') return await handleDeleteAccount(req, res);
      if (req.method === 'POST' && url.pathname === '/api/create-checkout') return await handleCreateCheckout(req, res);
      if (req.method === 'GET' && url.pathname === '/api/status') return await handleStatus(req, res);
      if (req.method === 'POST' && url.pathname === '/webhook/polar') return await handleWebhook(req, res);
      return send(res, 404, { error: 'not found' });
    } catch (e) {
      if (e && e.status === 413) return send(res, 413, { error: 'too large' });
      logger({ evt: 'error', message: e && e.message });
      return send(res, 500, { error: 'server error' });
    }
  };
}

module.exports = { createApp, UID_RE };
