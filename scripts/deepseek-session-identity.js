'use strict';

const crypto = require('crypto');

function normalizeSessionKey(sessionKey) {
  const value = String(sessionKey || '').trim();
  if (!value) return '';
  if (/^[a-zA-Z0-9_.:-]{1,120}$/.test(value)) return value;
  return `session-${crypto.createHash('sha256').update(value, 'utf8').digest('hex')}`;
}

module.exports = { normalizeSessionKey };
