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

test('does not persist budget-limited inference as a successful provider answer', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  const ledger = new RequestLedger(file);
  ledger.begin('budget-limited', 'digest-budget');
  ledger.markSubmitting('budget-limited', 'digest-budget');
  ledger.markSubmitted('budget-limited', 'digest-budget');
  ledger.complete('budget-limited', 'digest-budget', {
    answerText: '==REPLY==\n未完成的回答',
    contentStatus: 'success',
    completionReason: 'quick_budget_reached'
  });
  const reloaded = new RequestLedger(file);
  assert.equal(reloaded.lookup('budget-limited', 'digest-budget').status, 'INVALID');
  assert.equal(reloaded.lookup('budget-limited', 'digest-budget').entry.error,
    'provider_response_incomplete');
});

test('invalidates older cached successes whose completion was only a budget signal', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  fs.writeFileSync(file, JSON.stringify({
    'older-budget': {
      submissionKey: 'older-budget',
      requestDigest: 'digest-old',
      status: 'SUCCESS',
      submitPhase: 'COMPLETED',
      result: {
        answerText: 'partial answer',
        contentStatus: 'success',
        completionReason: 'quick_budget_reached'
      }
    }
  }));
  const ledger = new RequestLedger(file);
  const entry = ledger.lookup('older-budget', 'digest-old').entry;
  assert.equal(entry.status, 'INVALID');
  assert.equal(entry.error, 'provider_response_incomplete');
  assert.equal(entry.result, undefined);
  assert.equal(new RequestLedger(file).lookup('older-budget', 'digest-old').status, 'INVALID');
});

test('does not cache a response without verified search evidence as success', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const ledger = new RequestLedger(path.join(directory, 'requests.json'));
  ledger.begin('missing-proof', 'digest-proof');
  ledger.markSubmitting('missing-proof', 'digest-proof');
  ledger.markSubmitted('missing-proof', 'digest-proof');
  ledger.complete('missing-proof', 'digest-proof', {
    answerText: 'A plausible answer with no current-turn citations',
    contentStatus: 'search_evidence_missing',
    completionReason: 'complete_json_network'
  });
  assert.equal(ledger.lookup('missing-proof', 'digest-proof').status, 'INVALID');
});

test('submission keys cannot collide with object prototype properties', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'deepseek-ledger-'));
  const file = path.join(directory, 'requests.json');
  const ledger = new RequestLedger(file);
  const claim = ledger.begin('__proto__', 'digest-prototype');
  assert.equal(claim.created, true);
  assert.equal(ledger.lookup('__proto__', 'digest-prototype').status, 'IN_FLIGHT');
  assert.equal(Object.getPrototypeOf(ledger.entries), null);
});
