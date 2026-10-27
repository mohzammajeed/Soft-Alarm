'use strict';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Reads the settings from environment variables and checks them, so a mistake is
// reported clearly at start-up instead of failing later during a payment.
function loadConfig(env) {
  const problems = [];

  const polarAccessToken = (env.POLAR_ACCESS_TOKEN || '').trim();
  if (polarAccessToken.length < 20) problems.push('POLAR_ACCESS_TOKEN is missing (create an Organization Access Token in Polar > Settings)');

  const polarProductId = (env.POLAR_PRODUCT_ID || '').trim();
  if (!UUID_RE.test(polarProductId)) problems.push('POLAR_PRODUCT_ID is missing or is not a product id (it looks like 1a2b3c4d-....)');

  const polarWebhookSecret = (env.POLAR_WEBHOOK_SECRET || '').trim();
  if (polarWebhookSecret.length < 16) problems.push('POLAR_WEBHOOK_SECRET is missing or too short (copy it from Polar > Settings > Webhooks)');

  const environment = (env.POLAR_ENVIRONMENT || 'sandbox').trim().toLowerCase();
  if (environment !== 'sandbox' && environment !== 'production') problems.push('POLAR_ENVIRONMENT must be "sandbox" or "production"');

  const publicUrl = (env.PUBLIC_URL || '').trim().replace(/\/+$/, '');
  if (!/^https:\/\/[^\s/]+/.test(publicUrl)) problems.push('PUBLIC_URL is missing or is not an https address (example: https://my-server.onrender.com)');

  const contactEmail = (env.CONTACT_EMAIL || '').trim();
  const publisherName = (env.PUBLISHER_NAME || '').trim();
  if (environment === 'production') {
    if (!/^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(contactEmail)) problems.push('CONTACT_EMAIL is required in production (it is shown in the privacy policy)');
    if (publisherName.length < 2) problems.push('PUBLISHER_NAME is required in production (your name or company, shown in the privacy policy)');
  }

  let serviceAccount = null;
  try {
    if (env.FIREBASE_SERVICE_ACCOUNT_BASE64) {
      serviceAccount = JSON.parse(Buffer.from(env.FIREBASE_SERVICE_ACCOUNT_BASE64, 'base64').toString('utf8'));
    } else if (env.FIREBASE_SERVICE_ACCOUNT_JSON) {
      serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
    }
  } catch (e) {
    problems.push('The Firebase service account value is not valid JSON/base64');
  }
  if (!serviceAccount) problems.push('FIREBASE_SERVICE_ACCOUNT_JSON (or FIREBASE_SERVICE_ACCOUNT_BASE64) is missing');
  else if (!serviceAccount.private_key || !serviceAccount.client_email || !serviceAccount.project_id) {
    problems.push('The Firebase service account JSON must contain project_id, client_email and private_key');
  }

  if (problems.length) {
    const err = new Error('Configuration problems:\n - ' + problems.join('\n - '));
    err.problems = problems;
    throw err;
  }

  const defaultBase = environment === 'production' ? 'https://api.polar.sh' : 'https://sandbox-api.polar.sh';
  return {
    polarAccessToken,
    polarProductId,
    polarWebhookSecret,
    environment,
    polarApiBase: (env.POLAR_API_BASE_URL || defaultBase).replace(/\/+$/, ''),
    publicUrl,
    contactEmail: contactEmail || 'set-CONTACT_EMAIL@example.com',
    publisherName: publisherName || 'SoftAlarm (set PUBLISHER_NAME)',
    policyUpdated: (env.POLICY_UPDATED || '23 October 2026').trim(),
    serviceAccount,
    maxCheckoutsPerHour: 10,
    port: Number(env.PORT) || 3000
  };
}

module.exports = { loadConfig, UUID_RE };
