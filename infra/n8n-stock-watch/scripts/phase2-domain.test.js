'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const domain = require('./phase2-domain');

function quote(overrides = {}) {
  return {
    symbol: '300750',
    market: 'sz',
    provider: 'EASTMONEY',
    source: 'Eastmoney quote',
    quote_time: '2026-07-31T01:31:00.000Z',
    fetched_at: '2026-07-31T01:31:05.000Z',
    price: 100,
    open: 99,
    day_high: 101,
    day_low: 98,
    previous_close: 99,
    cumulative_volume_raw: 100,
    cumulative_volume_shares: 10000,
    volume_unit: 'LOT_100_SHARES',
    cumulative_amount_raw: 1000000,
    amount_unit: 'CNY',
    unit_contract_status: 'EMPIRICAL_A_SHARE_ONLY',
    limitations: [],
    ...overrides
  };
}

test('trading session guard uses Asia/Shanghai weekday windows', () => {
  assert.equal(domain.tradingSessionState('2026-07-31T01:31:00Z').isTradingSession, true);
  assert.equal(domain.tradingSessionState('2026-07-31T04:00:00Z').isTradingSession, false);
  assert.equal(domain.tradingSessionState('2026-08-01T01:31:00Z').isTradingSession, false);
  assert.equal(domain.analysisSlot('2026-07-31T02:00:00Z').slot, '10:00');
});

test('Java envelope rejects stale source status and never falls back to fake data', () => {
  assert.throws(() => domain.normalizeJavaQuoteEnvelope({
    schemaVersion: 'stock-watch.n8n.quote.v1',
    items: [{ quality: { status: 'STALE', errors: ['STALE_QUOTE'] } }]
  }), /QUOTE_REJECTED:STALE/);
});

test('snapshot delta creates approximate bar from adjacent prices', () => {
  const result = domain.calculateSnapshotDelta(
    quote({ price: 99, cumulative_volume_raw: 90, cumulative_volume_shares: 9000, cumulative_amount_raw: 900000 }),
    quote({ quote_time: '2026-07-31T01:32:00.000Z' })
  );
  assert.equal(result.bar.open, 99);
  assert.equal(result.bar.high, 100);
  assert.equal(result.bar.low, 99);
  assert.equal(result.bar.close, 100);
  assert.equal(result.bar.volume_delta_shares, 1000);
  assert.equal(result.bar.amount_delta, 100000);
  assert.equal(result.bar.source, 'SNAPSHOT_ROLLUP');
  assert.equal(result.bar.quality_status, 'APPROXIMATE');
  assert.equal(result.bar.vwap, null);
  assert.ok(result.bar.limitations.includes('UNIT_CONTRACT_NOT_AUTHORITATIVE'));
});

test('counter reset and unit change produce no negative delta', () => {
  const reset = domain.calculateSnapshotDelta(
    quote({ quote_time: '2026-07-31T03:30:00Z', cumulative_volume_raw: 500, cumulative_volume_shares: 50000 }),
    quote({ quote_time: '2026-07-31T05:01:00Z', cumulative_volume_raw: 10, cumulative_volume_shares: 1000 })
  );
  assert.equal(reset.bar.volume_delta_raw, null);
  assert.ok(reset.bar.limitations.includes('MIDDAY_VOLUME_COUNTER_RESET'));

  const changed = domain.calculateSnapshotDelta(
    quote(),
    quote({ quote_time: '2026-07-31T01:32:00Z', volume_unit: 'SHARE' })
  );
  assert.equal(changed.bar.volume_delta_raw, null);
  assert.ok(changed.bar.limitations.includes('VOLUME_UNIT_CHANGED'));
});

test('duplicate timestamp is idempotently excluded from bar persistence', () => {
  const result = domain.calculateSnapshotDelta(quote(), quote({ price: 101 }));
  assert.equal(result.persistBar, false);
  assert.equal(result.bar.quality_status, 'DUPLICATE');
});

test('provider submission identity changes per attempt while packet identity stays stable', () => {
  const packetHash = 'fnv1a32:stable-packet';
  const first = domain.providerSubmissionKey(
    { job_key: 'job.300750.2026-07-31.10:30.MARKET_BEHAVIOR', attempt_count: 1 },
    packetHash,
    'DEEPSEEK_WEB',
    'stock-watch-market-behavior-v2'
  );
  const second = domain.providerSubmissionKey(
    { job_key: 'job.300750.2026-07-31.10:30.MARKET_BEHAVIOR', attempt_count: 2 },
    packetHash,
    'DEEPSEEK_WEB',
    'stock-watch-market-behavior-v2'
  );

  assert.notEqual(first, second);
  assert.match(first, /:attempt-1$/);
  assert.match(second, /:attempt-2$/);
  assert.ok(first.includes(packetHash));
  assert.ok(second.includes(packetHash));
});

test('model validator rejects invalid JSON, unknown facts, L0 capability claims, and hallucinated numbers', () => {
  const packet = { facts: { F_STOCK_RETURN_30M: { value: 0.02 } } };
  assert.equal(domain.validateModelOutput('not-json', packet).structure_status, 'INVALID_OUTPUT');

  const invalid = {
    schema_version: 'stock-watch.market-behavior.v1',
    analysis_status: 'VALID',
    hypotheses: [{
      hypothesis_id: 'H1',
      type: 'SPOOFING_LIKE',
      evidence_band: 'HIGH',
      support_fact_refs: ['F_UNKNOWN'],
      counter_fact_refs: [],
      alternatives: ['Move reaches 88 percent'],
      next_confirmation_conditions: []
    }],
    material_changes: [],
    unexplained_fact_refs: [],
    limitations: []
  };
  const result = domain.validateModelOutput(JSON.stringify(invalid), packet);
  const codes = new Set(result.errors.map((error) => error.code));
  assert.ok(codes.has('CAPABILITY_VIOLATION'));
  assert.ok(codes.has('INVALID_FACT_REF'));
  assert.ok(codes.has('POLICY_VIOLATION'));
});

test('model validator rejects extra structure and missing hypothesis fields', () => {
  const packet = { facts: { F_STOCK_RETURN_30M: { value: 0.02 } } };
  const output = {
    schema_version: 'stock-watch.market-behavior.v1',
    analysis_status: 'VALID',
    hypotheses: [{
      hypothesis_id: 'H1',
      type: 'NORMAL_EXECUTION',
      evidence_band: 'LOW',
      support_fact_refs: ['F_STOCK_RETURN_30M'],
      counter_fact_refs: [],
      alternatives: [],
      extra_claim: true
    }],
    material_changes: [],
    unexplained_fact_refs: [],
    limitations: [],
    recommendation: 'none'
  };
  const result = domain.validateModelOutput(JSON.stringify(output), packet);
  assert.equal(result.status, 'rejected');
  assert.ok(result.errors.some((error) => error.detail === 'root.extra.recommendation'));
  assert.ok(result.errors.some((error) => error.detail === 'hypothesis.missing.next_confirmation_conditions'));
});

test('model validator rejects non-string root arrays and action instructions', () => {
  const packet = { facts: { F_STOCK_RETURN_30M: { value: 0.02 } } };
  const base = {
    schema_version: 'stock-watch.market-behavior.v1',
    analysis_status: 'VALID',
    hypotheses: [],
    material_changes: [],
    unexplained_fact_refs: [],
    limitations: []
  };
  const invalidType = domain.validateModelOutput(JSON.stringify({
    ...base,
    material_changes: [{ invented: true }]
  }), packet);
  assert.equal(invalidType.status, 'rejected');
  assert.ok(invalidType.errors.some((error) => error.code === 'MODEL_SCHEMA_INVALID'));

  const action = domain.validateModelOutput(JSON.stringify({
    ...base,
    material_changes: ['建议加仓']
  }), packet);
  assert.equal(action.status, 'rejected');
  assert.ok(action.errors.some((error) => error.code === 'POLICY_VIOLATION'));
  for (const instruction of ['add-position', 'add position']) {
    const englishAction = domain.validateModelOutput(JSON.stringify({
      ...base,
      material_changes: [instruction]
    }), packet);
    assert.equal(englishAction.status, 'rejected');
    assert.ok(englishAction.errors.some((error) => error.code === 'POLICY_VIOLATION'));
  }

  for (const primitive of ['null', 'false', '0']) {
    assert.equal(domain.validateModelOutput(primitive, packet).status, 'rejected');
  }
  const invalidHypothesis = domain.validateModelOutput(JSON.stringify({
    ...base,
    hypotheses: [null]
  }), packet);
  assert.equal(invalidHypothesis.status, 'rejected');
  assert.ok(invalidHypothesis.errors.some((error) => error.detail === 'hypothesis must be an object'));
  assert.equal(domain.validateModelOutput(JSON.stringify({
    ...base,
    hypotheses: {}
  }), packet).status, 'rejected');
  assert.equal(domain.validateModelOutput(JSON.stringify({
    ...base,
    unexplained_fact_refs: {}
  }), packet).status, 'rejected');
});

test('model input is de-identified and MOCK output remains structurally valid', () => {
  const bars = [];
  for (let index = 0; index <= 30; index += 1) {
    bars.push({
      minute_start: new Date(Date.parse('2026-07-31T01:30:00Z') + index * 60000).toISOString(),
      open: 100 + index / 100,
      high: 100.1 + index / 100,
      low: 99.9 + index / 100,
      close: 100 + index / 100,
      vwap: 100 + index / 100
    });
  }
  const packet = domain.buildFactPacket({
    bars,
    snapshot: quote({ quote_time: bars[bars.length - 1].minute_start }),
    now: bars[bars.length - 1].minute_start,
    job_id: 'job.300750.2026-07-31.10:00.MARKET_BEHAVIOR',
    symbol: '300750',
    market: 'sz',
    trade_date: '2026-07-31',
    run_slot: '10:00'
  });
  const serializedInput = JSON.stringify(packet.model_input);
  assert.equal(serializedInput.includes('300750'), false);
  assert.equal(serializedInput.includes('position'), false);
  assert.equal(packet.provenance.source_kind, 'JAVA_ADAPTER');
  const mock = domain.mockBehaviorView(packet);
  assert.equal(domain.validateModelOutput(JSON.stringify(mock), packet).status, 'accepted');
});

test('fact packet preserves explicit fixture provenance outside model input', () => {
  const packet = domain.buildFactPacket({
    bars: [{
      minute_start: '2026-07-31T02:00:00Z',
      open: 100,
      high: 101,
      low: 99,
      close: 100,
      source: 'LOCAL_QA_FIXTURE',
      quality_status: 'APPROXIMATE'
    }],
    snapshot: quote({
      provider: 'PHASE2_TEST',
      source: 'LOCAL_QA_FIXTURE',
      quality_status: 'PARTIAL',
      quote_time: '2026-07-31T02:00:00Z'
    }),
    now: '2026-07-31T02:00:00Z',
    job_id: 'fixture-job',
    symbol: '300750',
    market: 'sz',
    trade_date: '2026-07-31',
    run_slot: '10:00'
  });
  assert.equal(packet.provenance.source_kind, 'LOCAL_QA_FIXTURE');
  assert.equal(Object.prototype.hasOwnProperty.call(packet.model_input, 'provenance'), false);
});

test('zero bars produce an insufficient de-identified packet instead of stranding a job', () => {
  const packet = domain.buildFactPacket({
    bars: [],
    snapshot: {},
    now: '2026-07-31T02:00:00Z',
    observation_time: '2026-07-31T02:00:00Z',
    job_id: 'job.300750.2026-07-31.10:00.MARKET_BEHAVIOR',
    symbol: '300750',
    market: 'sz',
    trade_date: '2026-07-31',
    run_slot: '10:00'
  });
  assert.equal(packet.quote_time, '2026-07-31T02:00:00.000Z');
  assert.equal(packet.facts.F_STOCK_RETURN_30M.quality_status, 'UNAVAILABLE');
  assert.equal(domain.validateModelOutput(JSON.stringify(domain.mockBehaviorView(packet)), packet).status, 'accepted');
});

test('model validator classifies non-success transport and timeout responses', () => {
  const packet = { facts: {} };
  const rejected = domain.validateModelOutput({}, packet, {
    statusCode: 502,
    body: { error: 'upstream', providerOutcomeUnknown: false, retrySafe: true }
  });
  assert.equal(rejected.structure_status, 'MODEL_TRANSPORT_ERROR');
  assert.equal(rejected.retryable, true);
  const timedOut = domain.validateModelOutput({}, packet, {
    error: {
      status: 504,
      message: '504 - {"error":"timeout","providerOutcomeUnknown":false,"retrySafe":true}'
    }
  });
  assert.equal(timedOut.structure_status, 'MODEL_TIMEOUT');
  assert.equal(timedOut.retryable, true);
  const unknown = domain.validateModelOutput({}, packet, {
    error: {
      status: 504,
      message: '504 - {"error":"timeout","providerOutcomeUnknown":true,"retrySafe":false}'
    }
  });
  assert.equal(unknown.structure_status, 'MODEL_PROVIDER_OUTCOME_UNKNOWN');
  assert.equal(unknown.retryable, false);
  const unproven = domain.validateModelOutput({}, packet, { statusCode: 502 });
  assert.equal(unproven.structure_status, 'MODEL_RETRY_SAFETY_UNPROVEN');
  assert.equal(unproven.retryable, false);
  assert.equal(domain.validateModelOutput({}, packet, {
    statusCode: 429,
    body: { providerOutcomeUnknown: false, retrySafe: true }
  }).retryable, true);
  assert.equal(domain.validateModelOutput({}, packet, { statusCode: 401 }).retryable, false);
});

test('stress report keeps T1 and budget logic deterministic', () => {
  const report = domain.buildStressReport({
    packet: { packet_hash: 'test', facts: { F_BAR_COVERAGE: { value: 1 } } },
    validation_status: 'accepted',
    current_price: 100,
    day_low: 92,
    window_low: 95,
    position: { total_quantity: 100, available_quantity: 0 },
    risk_policy: { max_stress_loss_yuan: 500 }
  });
  assert.equal(report.position_state, 'FULLY_LOCKED_T1');
  assert.equal(report.hard_risk, 'RISK_BUDGET_BREACHED');
  assert.equal(report.scenarios.length, 8);
});

test('locked-position policy blocks new entry when budget is not breached', () => {
  const report = domain.buildStressReport({
    packet: { packet_hash: 'test', facts: {} },
    validation_status: 'accepted',
    current_price: 100,
    day_low: 99,
    window_low: 99,
    position: { total_quantity: 100, available_quantity: 0 },
    risk_policy: { max_stress_loss_yuan: 100000, block_new_entry_when_locked: true }
  });
  assert.equal(report.hard_risk, 'BLOCK_NEW_ENTRY');
  assert.equal(report.entry_blocked, true);
});
