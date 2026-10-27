'use strict';

const http = require('http');
const https = require('https');
const crypto = require('crypto');

function errorDetail(json) {
  if (!json) return 'unknown error';
  if (typeof json.detail === 'string') return json.detail;
  if (Array.isArray(json.detail)) return json.detail.map(d => (d && d.msg) || '').filter(Boolean).join('; ') || 'validation error';
  if (typeof json.error === 'string') return json.error;
  return 'unknown error';
}

// Talks to Polar's REST API directly (no extra package needed).
// baseUrl is https://api.polar.sh (real) or https://sandbox-api.polar.sh (testing); tests use a fake.
function createPolarClient({ accessToken, baseUrl }) {
  const u = new URL(baseUrl);
  const lib = u.protocol === 'http:' ? http : https;

  function call(method, apiPath, payload) {
    return new Promise((resolve, reject) => {
      const body = payload ? JSON.stringify(payload) : '';
      const req = lib.request({
        hostname: u.hostname,
        port: u.port || (u.protocol === 'http:' ? 80 : 443),
        path: apiPath,
        method,
        timeout: 15000,
        headers: {
          Authorization: 'Bearer ' + accessToken,
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'Content-Length': Buffer.byteLength(body)
        }
      }, res => {
        let data = '';
        res.on('data', c => { data += c; });
        res.on('end', () => {
          try {
            resolve({ status: res.statusCode, json: JSON.parse(data) });
          } catch (e) {
            reject(new Error('Polar returned a non-JSON answer (HTTP ' + res.statusCode + ')'));
          }
        });
      });
      req.on('timeout', () => req.destroy(new Error('Polar request timed out')));
      req.on('error', reject);
      req.write(body);
      req.end();
    });
  }

  return {
    // The product (and so the price) is decided HERE on the server, never by the app.
    // The user id travels in the checkout "metadata"; Polar copies it onto the resulting order.
    async createCheckout({ productId, uid, successUrl }) {
      const r = await call('POST', '/v1/checkouts', {
        products: [productId],
        metadata: { uid: uid, app: 'softalarm' },
        success_url: successUrl
      });
      if ((r.status !== 200 && r.status !== 201) || !r.json || !r.json.url) {
        throw new Error('Polar error (HTTP ' + r.status + '): ' + errorDetail(r.json));
      }
      return { id: r.json.id, url: r.json.url };
    }
  };
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

// Polar signs webhooks with the "Standard Webhooks" scheme:
//   headers: webhook-id, webhook-timestamp, webhook-signature ("v1,<base64>" , maybe several, space separated)
//   signature = base64( HMAC-SHA256( key, webhook-id + "." + webhook-timestamp + "." + body ) )
// Polar uses the secret text itself as the key. A standard base64-decoded "whsec_" key is also tried,
// so a small difference in how the secret is shown in the dashboard cannot break payments.
function verifyPolarWebhook(rawBody, headers, secret, nowMs, toleranceSec) {
  const id = headers['webhook-id'];
  const ts = headers['webhook-timestamp'];
  const sigHeader = headers['webhook-signature'];
  if (!secret || !id || !ts || !sigHeader) return false;
  if (!/^\d{9,12}$/.test(String(ts))) return false;

  const tolerance = toleranceSec === undefined ? 300 : toleranceSec;
  const now = nowMs === undefined ? Date.now() : nowMs;
  if (Math.abs(now / 1000 - Number(ts)) > tolerance) return false;

  const provided = String(sigHeader).split(' ')
    .map(p => p.trim())
    .filter(p => p.startsWith('v1,'))
    .map(p => p.slice(3));
  if (provided.length === 0) return false;

  const keys = [Buffer.from(secret, 'utf8')];
  if (secret.startsWith('whsec_')) keys.push(Buffer.from(secret.slice(6), 'base64'));

  const signedContent = id + '.' + ts + '.' + rawBody;
  return keys.some(key => {
    const expected = crypto.createHmac('sha256', key).update(signedContent).digest('base64');
    return provided.some(sig => safeEqual(sig, expected));
  });
}

module.exports = { createPolarClient, verifyPolarWebhook };
