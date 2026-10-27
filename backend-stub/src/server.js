'use strict';

const http = require('http');
const { loadConfig } = require('./config');
const { createPolarClient } = require('./polarApi');
const { createApp } = require('./app');
const { createFirebaseStore, createFirebaseAuth } = require('./firebaseAdapter');
const { loadPages } = require('./pages');

let config;
try {
  config = loadConfig(process.env);
} catch (e) {
  console.error(e.message);
  process.exit(1);
}

const admin = require('firebase-admin');
admin.initializeApp({ credential: admin.credential.cert(config.serviceAccount) });

const handler = createApp({
  config: config,
  polar: createPolarClient({ accessToken: config.polarAccessToken, baseUrl: config.polarApiBase }),
  store: createFirebaseStore(admin),
  auth: createFirebaseAuth(admin),
  pages: loadPages(config),
  log: (entry) => console.log(JSON.stringify(entry))   // never log keys or card data
});

http.createServer(handler).listen(config.port, () => {
  console.log('SoftAlarm payment server running on port ' + config.port +
    ' (Polar ' + config.environment.toUpperCase() + ' mode)');
});
