'use strict';

const fs = require('fs');
const path = require('path');

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Reads the page templates and fills in who publishes the app and how to reach you.
// The values come from the server settings (CONTACT_EMAIL, PUBLISHER_NAME, POLICY_UPDATED).
function loadPages(config) {
  const values = {
    PUBLISHER: escapeHtml(config.publisherName),
    CONTACT_EMAIL: escapeHtml(config.contactEmail),
    UPDATED: escapeHtml(config.policyUpdated)
  };
  const fill = (name) => {
    const html = fs.readFileSync(path.join(__dirname, '..', 'public', name), 'utf8');
    return html.replace(/\{\{([A-Z_]+)\}\}/g, (m, key) => (key in values ? values[key] : m));
  };
  return { privacy: fill('privacy.html'), deleteAccount: fill('delete-account.html') };
}

module.exports = { loadPages };
