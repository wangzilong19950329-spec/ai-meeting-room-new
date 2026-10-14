'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { normalizeSessionKey } = require('./deepseek-session-identity');

test('session identity preserves safe keys and hashes lossy inputs', () => {
  assert.equal(normalizeSessionKey('stock-watch-day-sz-300750-g1'), 'stock-watch-day-sz-300750-g1');
  assert.match(normalizeSessionKey('tenant/A'), /^session-[a-f0-9]{64}$/);
  assert.notEqual(normalizeSessionKey('tenant/A'), normalizeSessionKey('tenant_A'));
  assert.notEqual(normalizeSessionKey(`prefix-${'x'.repeat(200)}-left`),
    normalizeSessionKey(`prefix-${'x'.repeat(200)}-right`));
});
