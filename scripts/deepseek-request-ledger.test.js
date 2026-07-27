const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');

const { RequestLedger } = require('./deepseek-request-ledger');

test('persists successful responses and replays only the matching digest', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  const ledger = new RequestLedger(file);

  const claim = ledger.begin('slot-1', 'digest-1', { purpose: 'stock-analysis-v1' });
  assert.equal(claim.created, true);
  ledger.markSubmitting('slot-1', 'digest-1');
  ledger.markSubmitted('slot-1', 'digest-1');
  ledger.complete('slot-1', 'digest-1', { answerText: '{"ok":true}', targetId: 'target-1' });

  const reloaded = new RequestLedger(file);
  assert.equal(reloaded.lookup('slot-1', 'digest-1').status, 'SUCCESS');
  assert.equal(reloaded.lookup('slot-1', 'digest-1').entry.result.targetId, 'target-1');
  assert.equal(reloaded.lookup('slot-1', 'digest-2').status, 'DIGEST_MISMATCH');
});

test('marks an interrupted in-flight provider outcome unknown after restart', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  const ledger = new RequestLedger(file);
  ledger.begin('slot-2', 'digest-2');
  ledger.markSubmitting('slot-2', 'digest-2');

  const reloaded = new RequestLedger(file);
  const result = reloaded.lookup('slot-2', 'digest-2');
  assert.equal(result.status, 'UNKNOWN');
  assert.equal(result.entry.error, 'bridge_restarted_with_provider_outcome_unknown');
});

test('keeps a non-retriable tombstone when a cached success result is evicted', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  const ledger = new RequestLedger(file, 2, 10);
  for (let index = 1; index <= 3; index += 1) {
    ledger.begin(`slot-${index}`, `digest-${index}`);
    ledger.markSubmitting(`slot-${index}`, `digest-${index}`);
    ledger.markSubmitted(`slot-${index}`, `digest-${index}`);
    ledger.complete(`slot-${index}`, `digest-${index}`, {
      answerText: `answer-${index}`,
      contentStatus: 'complete'
    });
    ledger.entries[`slot-${index}`].updatedAt = new Date(Date.UTC(2026, 0, 1, 0, 0, index)).toISOString();
  }
  ledger.persist();

  const evicted = ledger.lookup('slot-1', 'digest-1');
  assert.equal(evicted.status, 'EVICTED_SUCCESS');
  assert.equal(evicted.entry.result, undefined);
  assert.equal(ledger.begin('slot-1', 'digest-1').created, undefined);
  assert.equal(ledger.lookup('slot-1', 'different').status, 'DIGEST_MISMATCH');
});

test('keeps pre-submit failures distinct from unknown provider outcomes', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  const ledger = new RequestLedger(file);

  ledger.begin('pre-submit', 'digest-a');
  ledger.markFailedPreSubmit('pre-submit', 'digest-a', 'bridge_queue_timeout');
  assert.equal(ledger.lookup('pre-submit', 'digest-a').status, 'FAILED_PRE_SUBMIT');
  assert.equal(ledger.lookup('pre-submit', 'digest-a').entry.submitReservationCount, 0);

  ledger.begin('unknown', 'digest-b');
  ledger.markSubmitting('unknown', 'digest-b');
  ledger.markUnknown('unknown', 'digest-b', 'connection_lost');
  const unknown = ledger.lookup('unknown', 'digest-b').entry;
  assert.equal(unknown.status, 'UNKNOWN');
  assert.equal(unknown.submitReservationCount, 1);
  assert.equal(unknown.confirmedSubmitInvocationCount, 0);
  assert.equal(unknown.possibleSubmitUpperBound, 1);
});

test('does not mark incomplete or empty provider results successful', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  const ledger = new RequestLedger(file);
  ledger.begin('invalid', 'digest-c');
  ledger.markSubmitting('invalid', 'digest-c');
  ledger.markSubmitted('invalid', 'digest-c');
  ledger.complete('invalid', 'digest-c', {
    answerText: 'partial',
    contentStatus: 'incomplete'
  });
  assert.equal(ledger.lookup('invalid', 'digest-c').status, 'INVALID');
});
