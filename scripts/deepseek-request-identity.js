'use strict';

const crypto = require('crypto');
const { normalizeSessionKey } = require('./deepseek-session-identity');

function sha256Query(query) {
  return crypto.createHash('sha256').update(String(query || ''), 'utf8').digest('hex');
}

function canonicalOperationPayload(request = {}) {
  const sessionKey = normalizeSessionKey(request.sessionKey);
  const isolatedSession = request.isolatedSession === true || request.isolated === true;
  const closeTargetAfter = !sessionKey && isolatedSession && request.closeTargetAfter !== false;
  const fields = [
    ['query', String(request.query || '').trim()],
    ['capability', String(request.capability || '').trim().toLowerCase()],
    ['sessionKey', sessionKey],
    ['mode', String(request.mode || '').trim().toLowerCase()],
    ['expectedJson', request.expectedJson === true ? 'true' : 'false'],
    ['forceNewSession', request.forceNewSession === true ? 'true' : 'false'],
    ['isolatedSession', isolatedSession ? 'true' : 'false'],
    ['closeTargetAfter', closeTargetAfter ? 'true' : 'false'],
    ['accountKey', String(request.accountKey || '').trim()]
  ];
  if (request.requireSearchEvidence === true) {
    fields.push(['requireSearchEvidence', 'true']);
  }
  if (request.enableSearch === false) {
    fields.push(['enableSearch', 'false']);
  }
  return `deepseek-operation-v1\n${fields.map(([name, value]) => (
    `${name}:${value.length}:${value}\n`
  )).join('')}`;
}

function sha256Operation(request) {
  return sha256Query(canonicalOperationPayload(request));
}

function validateSubmissionIdentity(submissionKey, requestDigest, operation) {
  const key = String(submissionKey || '').trim();
  const digest = String(requestDigest || '').trim().toLowerCase();
  if (!key || key.length > 512 || /[\u0000-\u001f\u007f]/.test(key)) {
    const error = new Error('submissionKey must contain 1-512 printable characters');
    error.code = 'submission_key_invalid';
    throw error;
  }
  if (!/^[a-f0-9]{64}$/.test(digest)) {
    const error = new Error('requestDigest must be a SHA-256 hex digest');
    error.code = 'request_digest_invalid';
    throw error;
  }
  const expectedDigest = operation == null
    ? null
    : (typeof operation === 'string' ? sha256Query(operation) : sha256Operation(operation));
  if (expectedDigest && expectedDigest !== digest) {
    const error = new Error('requestDigest does not match the submitted operation');
    error.code = 'request_digest_mismatch';
    throw error;
  }
  return { submissionKey: key, requestDigest: digest };
}

function requiresSubmissionIdentity(capability) {
  return String(capability || '').trim().toLowerCase().startsWith('stock.');
}

module.exports = {
  canonicalOperationPayload,
  requiresSubmissionIdentity,
  sha256Operation,
  sha256Query,
  validateSubmissionIdentity
};
