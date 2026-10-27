'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadConfig } = require('../src/config');

const sa = { project_id: 'p', client_email: 'a@b.iam.gserviceaccount.com', private_key: '-----BEGIN PRIVATE KEY-----\nx\n-----END PRIVATE KEY-----\n' };
const good = () => ({
  POLAR_ACCESS_TOKEN: 'polar_oat_abcdefghijklmnopqrstuvwxyz',
  POLAR_PRODUCT_ID: '11111111-2222-4333-8444-555555555555',
  POLAR_WEBHOOK_SECRET: 'polar_whs_abcdefghijklmnop',
  PUBLIC_URL: 'https://softalarm.example.com/',
  FIREBASE_SERVICE_ACCOUNT_BASE64: Buffer.from(JSON.stringify(sa)).toString('base64')
});

test('a complete configuration loads with safe defaults (sandbox, trailing slash removed)', () => {
  const c = loadConfig(good());
  assert.equal(c.environment, 'sandbox');
  assert.equal(c.polarApiBase, 'https://sandbox-api.polar.sh');
  assert.equal(c.publicUrl, 'https://softalarm.example.com');
  assert.equal(c.port, 3000);
  assert.equal(c.serviceAccount.project_id, 'p');
});

test('production mode uses the real Polar address; the address can be overridden', () => {
  assert.equal(loadConfig(Object.assign(good(), { POLAR_ENVIRONMENT: 'production', CONTACT_EMAIL: 'me@example.com', PUBLISHER_NAME: 'Me Ltd' })).polarApiBase, 'https://api.polar.sh');
  assert.equal(loadConfig(Object.assign(good(), { POLAR_API_BASE_URL: 'https://other.example/' })).polarApiBase, 'https://other.example');
});

test('every missing or wrong setting is listed in one clear message', () => {
  assert.throws(() => loadConfig({}), (e) => e.problems.length >= 5);
  assert.throws(() => loadConfig(Object.assign(good(), { POLAR_ACCESS_TOKEN: '' })), /POLAR_ACCESS_TOKEN/);
  assert.throws(() => loadConfig(Object.assign(good(), { POLAR_PRODUCT_ID: 'my-product' })), /POLAR_PRODUCT_ID/);
  assert.throws(() => loadConfig(Object.assign(good(), { POLAR_WEBHOOK_SECRET: 'short' })), /POLAR_WEBHOOK_SECRET/);
  assert.throws(() => loadConfig(Object.assign(good(), { POLAR_ENVIRONMENT: 'live' })), /POLAR_ENVIRONMENT/);
  assert.throws(() => loadConfig(Object.assign(good(), { PUBLIC_URL: 'http://insecure.example' })), /PUBLIC_URL/);
  assert.throws(() => loadConfig(Object.assign(good(), { FIREBASE_SERVICE_ACCOUNT_BASE64: 'not-base64-json' })), /Firebase service account/);
});

test('the service account can also be given as plain JSON', () => {
  const env = good(); delete env.FIREBASE_SERVICE_ACCOUNT_BASE64; env.FIREBASE_SERVICE_ACCOUNT_JSON = JSON.stringify(sa);
  assert.equal(loadConfig(env).serviceAccount.client_email, sa.client_email);
});

test('production needs the publisher details for the privacy policy; sandbox does not', () => {
  const prod = Object.assign(good(), { POLAR_ENVIRONMENT: 'production' });
  assert.throws(() => loadConfig(prod), /CONTACT_EMAIL/);
  assert.throws(() => loadConfig(Object.assign({}, prod, { CONTACT_EMAIL: 'not-an-email', PUBLISHER_NAME: 'Me Ltd' })), /CONTACT_EMAIL/);
  assert.throws(() => loadConfig(Object.assign({}, prod, { CONTACT_EMAIL: 'me@example.com' })), /PUBLISHER_NAME/);
  const ok = loadConfig(Object.assign({}, prod, { CONTACT_EMAIL: 'me@example.com', PUBLISHER_NAME: 'Me Ltd', POLICY_UPDATED: '5 May 2027' }));
  assert.equal(ok.contactEmail, 'me@example.com');
  assert.equal(ok.publisherName, 'Me Ltd');
  assert.equal(ok.policyUpdated, '5 May 2027');
  assert.ok(loadConfig(good()).contactEmail);   // sandbox falls back to a visible reminder
});
