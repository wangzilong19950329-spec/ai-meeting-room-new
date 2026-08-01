#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const readText = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8');
const readJson = (relativePath) => JSON.parse(readText(relativePath));

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function stableStringify(value) {
  if (Array.isArray(value)) return '[' + value.map(stableStringify).join(',') + ']';
  if (value && typeof value === 'object') {
    return '{' + Object.keys(value).sort().map((key) => JSON.stringify(key) + ':' + stableStringify(value[key])).join(',') + '}';
  }
  return JSON.stringify(value);
}

function fnv1a32(input) {
  let hash = 0x811c9dc5;
  for (let i = 0; i < input.length; i += 1) {
    hash ^= input.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

function normalizeQuote(q) {
  for (const name of ['last_price', 'previous_close', 'open', 'high', 'low', 'volume']) {
    assert(typeof q[name] === 'number' && Number.isFinite(q[name]), `${name} must be finite`);
  }
  assert(q.field_semantics.amount === 'unknown', 'amount semantics must stay unknown in Phase1');
  assert(q.field_semantics.main_inflow === 'unknown', 'main_inflow semantics must stay unknown in Phase1');
  return { ...q };
}

function quoteQualityGate(q) {
  const freshnessSeconds = Math.round((Date.parse(q.fetched_at) - Date.parse(q.quote_time)) / 1000);
  const isRealQuote = q.source_kind === 'eastmoney_real';
  const allowedSessionStates = ['OPEN', 'MIDDAY_BREAK', 'CLOSED'];
  const hasValidSessionState = allowedSessionStates.includes(q.market_session_state);
  const isClosedSession = isRealQuote && hasValidSessionState && q.market_session_state !== 'OPEN';
  assert(!isRealQuote || hasValidSessionState, 'market_session_state is invalid');
  assert(freshnessSeconds >= 0, 'fetched_at is earlier than quote_time');
  assert(freshnessSeconds <= 300 || isClosedSession, 'quote is stale');
  assert(q.high >= q.low, 'high is below low');
  assert(q.last_price >= q.low && q.last_price <= q.high, 'last_price outside day range');
  return {
    freshnessSeconds,
    qualityStatus: isRealQuote
      ? (isClosedSession ? 'closed_session_snapshot' : 'fresh_real')
      : 'fresh_fixture',
    limitations: isClosedSession && freshnessSeconds > 300
      ? ['QUOTE_STALE_OUTSIDE_TRADING_SESSION']
      : []
  };
}

function buildFacts(quote, position, freshnessSeconds) {
  assert(position.symbol === quote.symbol && position.market === quote.market, 'position fixture does not match quote identity');
  const round4 = (value) => Math.round(value * 10000) / 10000;
  return [
    { id: 'fact.last_price', value: quote.last_price, unit: quote.currency, source: quote.source_kind, quality: 'fixture' },
    { id: 'fact.previous_close', value: quote.previous_close, unit: quote.currency, source: quote.source_kind, quality: 'fixture' },
    { id: 'fact.price_change_pct', value: round4(((quote.last_price - quote.previous_close) / quote.previous_close) * 100), unit: 'percent', source: quote.source_kind, quality: 'derived_from_fixture' },
    { id: 'fact.day_range_pct', value: round4(((quote.high - quote.low) / quote.previous_close) * 100), unit: 'percent', source: quote.source_kind, quality: 'derived_from_fixture' },
    { id: 'fact.quote_freshness_seconds', value: freshnessSeconds, unit: 'seconds', source: quote.source_kind, quality: 'quality_gate' },
    { id: 'fact.amount_semantics', value: quote.field_semantics.amount, unit: 'enum', source: 'api_discovery', quality: 'unknown_semantics' },
    { id: 'fact.position_total_quantity', value: position.total_quantity, unit: 'shares', source: position.fixture_name, quality: 'fixture' },
    { id: 'fact.available_quantity', value: position.available_quantity, unit: 'shares', source: position.fixture_name, quality: 'fixture' },
    { id: 'fact.position_locked_quantity', value: position.locked_quantity, unit: 'shares', source: position.fixture_name, quality: 'fixture' }
  ];
}

function registryFromFacts(facts) {
  const registry = {};
  for (const fact of facts) {
    assert(!registry[fact.id], `duplicate fact id ${fact.id}`);
    registry[fact.id] = fact;
  }
  return registry;
}

function buildPacket(quote, facts) {
  const packetCore = {
    schema_version: 'phase1.market_fact_packet.v1',
    source_kind: quote.source_kind,
    symbol: quote.symbol,
    market: quote.market,
    quote_time: quote.quote_time,
    facts,
    model_input: {
      symbol: quote.symbol,
      market: quote.market,
      quote_time: quote.quote_time,
      facts,
      approximation_note: 'Bars are approximate fixture-derived context, not full-session market bars.'
    }
  };
  return {
    ...packetCore,
    packet_hash: 'fnv1a32:' + fnv1a32(stableStringify(packetCore)),
    hash_algorithm: 'fnv1a32-local-stable'
  };
}

function parseStrictJson(text) {
  const parsed = JSON.parse(text);
  assert(parsed && typeof parsed === 'object' && !Array.isArray(parsed), 'model output must be an object');
  return parsed;
}

function validateBehaviorView(view, registry) {
  const errors = [];
  const forbiddenPatterns = [
    /\bwin\s*rate\b/i,
    /\bcvar\b/i,
    /\btarget\s*position\b/i,
    /\bautomated\s*sizing\b/i,
    /\bautomated\s*action\b/i,
    /\bmarket\s*manipulation\b/i,
    /\bhidden\s*actor/i,
    /\bbuy\b/i,
    /\bsell\b/i
  ];

  if (view.schema_version !== 'phase1.market_behavior_view.v1') errors.push('schema_version mismatch');
  if (!Array.isArray(view.claim_set)) errors.push('claim_set must be an array');
  if (!Array.isArray(view.uncertainty)) errors.push('uncertainty must be an array');
  if (!Array.isArray(view.numeric_outputs)) errors.push('numeric_outputs must be an array');
  if (view.prohibited_capabilities_used !== false) errors.push('prohibited_capabilities_used must be false');
  if (view.recommendation !== null) errors.push('recommendation must be null');

  for (const claim of view.claim_set || []) {
    if (!Array.isArray(claim.fact_refs) || claim.fact_refs.length === 0) {
      errors.push(`claim ${claim.claim_id} has no fact_refs`);
      continue;
    }
    for (const ref of claim.fact_refs) {
      if (!registry[ref]) errors.push(`unknown fact_ref: ${ref}`);
    }
    for (const pattern of forbiddenPatterns) {
      if (pattern.test(claim.statement || '')) errors.push(`forbidden content detected: ${pattern.source}`);
    }
  }
  for (const output of view.numeric_outputs || []) {
    const fact = registry[output.fact_ref];
    if (!fact) {
      errors.push(`numeric output references unknown fact_ref: ${output.fact_ref}`);
      continue;
    }
    if (typeof output.value !== 'number' || !Number.isFinite(output.value)) {
      errors.push(`numeric output is not finite for fact_ref: ${output.fact_ref}`);
      continue;
    }
    if (typeof fact.value !== 'number' || !Number.isFinite(fact.value) || output.value !== fact.value) {
      errors.push(`numeric output does not match fact value: ${output.fact_ref}`);
    }
  }

  if (errors.length) {
    const error = new Error(errors.join('; '));
    error.validationErrors = errors;
    throw error;
  }
}

function runTest(name, fn) {
  try {
    fn();
    console.log(`PASS ${name}`);
  } catch (error) {
    console.error(`FAIL ${name}: ${error.message}`);
    process.exitCode = 1;
  }
}

const renderedWorkflow = readJson('workflows/phase1/SW-01-POC-Manual-Pipeline.json');

function runRenderedCodeNode(name, json) {
  const node = renderedWorkflow.nodes.find((candidate) => candidate.name === name);
  assert(node && node.parameters && node.parameters.jsCode, `rendered node not found: ${name}`);
  const input = { first: () => ({ json }) };
  return new Function('$input', node.parameters.jsCode)(input)[0].json;
}

const validQuote = normalizeQuote(readJson('fixtures/quote-valid.json'));
const position = readJson('fixtures/position-t1-locked.json');
const validQuality = quoteQualityGate(validQuote);
const facts = buildFacts(validQuote, position, validQuality.freshnessSeconds);
const registry = registryFromFacts(facts);
const packet = buildPacket(validQuote, facts);

runTest('valid quote passes quality gate', () => {
  assert(validQuality.freshnessSeconds === 30, `expected freshness 30, got ${validQuality.freshnessSeconds}`);
  assert(validQuality.qualityStatus === 'fresh_fixture', `unexpected status ${validQuality.qualityStatus}`);
});

runTest('rendered workflow default quote passes quality gate', () => {
  const normalized = runRenderedCodeNode('Normalize Quote', { quote_fixture: validQuote });
  const quality = runRenderedCodeNode('Quote Quality Gate', normalized);
  assert(quality.quality_status === 'fresh_fixture', `unexpected rendered status ${quality.quality_status}`);
  assert(quality.quote_freshness_seconds === 30, 'rendered workflow freshness changed');
});

runTest('stale quote is rejected', () => {
  const stale = normalizeQuote(readJson('fixtures/quote-stale.json'));
  let rejected = false;
  try {
    quoteQualityGate(stale);
  } catch (error) {
    rejected = /stale/.test(error.message);
  }
  assert(rejected, 'stale quote was not rejected');
});

runTest('closed-session real quote retains stale snapshot boundary', () => {
  const closedQuote = normalizeQuote({
    ...validQuote,
    source_kind: 'eastmoney_real',
    market_session_state: 'CLOSED',
    quote_time: '2026-07-31T07:34:42.000Z',
    fetched_at: '2026-08-01T07:34:42.000Z'
  });
  const quality = quoteQualityGate(closedQuote);
  assert(quality.qualityStatus === 'closed_session_snapshot', `unexpected status ${quality.qualityStatus}`);
  assert(quality.limitations.includes('QUOTE_STALE_OUTSIDE_TRADING_SESSION'), 'missing stale snapshot limitation');
});

runTest('open-session stale real quote is rejected', () => {
  const staleOpenQuote = normalizeQuote({
    ...validQuote,
    source_kind: 'eastmoney_real',
    market_session_state: 'OPEN',
    quote_time: '2026-07-31T02:00:00.000Z',
    fetched_at: '2026-07-31T02:10:00.000Z'
  });
  let rejected = false;
  try {
    quoteQualityGate(staleOpenQuote);
  } catch (error) {
    rejected = /stale/.test(error.message);
  }
  assert(rejected, 'open-session stale real quote was not rejected');
});

runTest('rendered workflow rejects open-session stale real quote', () => {
  const normalized = runRenderedCodeNode('Normalize Quote', {
    quote_fixture: {
      ...validQuote,
      source_kind: 'eastmoney_real',
      market_session_state: 'OPEN',
      quote_time: '2026-07-31T02:00:00.000Z',
      fetched_at: '2026-07-31T02:10:00.000Z'
    }
  });
  let rejected = false;
  try {
    runRenderedCodeNode('Quote Quality Gate', normalized);
  } catch (error) {
    rejected = /stale/.test(error.message);
  }
  assert(rejected, 'rendered workflow accepted an open-session stale quote');
});

runTest('invalid real market session state is rejected', () => {
  let rejected = false;
  try {
    quoteQualityGate({
      ...validQuote,
      source_kind: 'eastmoney_real',
      market_session_state: 'NOT_OPEN',
      quote_time: '2026-07-31T02:00:00.000Z',
      fetched_at: '2026-07-31T02:10:00.000Z'
    });
  } catch (error) {
    rejected = /market_session_state/.test(error.message);
  }
  assert(rejected, 'invalid real market session state was not rejected');
});

runTest('rendered workflow rejects invalid real market session state', () => {
  const normalized = runRenderedCodeNode('Normalize Quote', {
    quote_fixture: {
      ...validQuote,
      source_kind: 'eastmoney_real',
      market_session_state: 'NOT_OPEN',
      quote_time: '2026-07-31T02:00:00.000Z',
      fetched_at: '2026-07-31T02:10:00.000Z'
    }
  });
  let rejected = false;
  try {
    runRenderedCodeNode('Quote Quality Gate', normalized);
  } catch (error) {
    rejected = /market_session_state/.test(error.message);
  }
  assert(rejected, 'rendered workflow accepted an invalid market session state');
});

runTest('packet hash is stable', () => {
  const packet2 = buildPacket(validQuote, facts);
  assert(packet.packet_hash === packet2.packet_hash, 'packet hash changed for identical input');
});

runTest('valid model output is accepted', () => {
  validateBehaviorView(readJson('fixtures/model-valid.json'), registry);
});

runTest('rendered MOCK derives CATL direction and numeric output from facts', () => {
  const catlRegistry = {
    ...registry,
    'fact.price_change_pct': {
      ...registry['fact.price_change_pct'],
      value: -1.6373,
      source: 'eastmoney_real',
      quality: 'derived_from_source_quote'
    }
  };
  const mocked = runRenderedCodeNode('MOCK Market Behavior', {
    normalized_quote: { ...validQuote, source_kind: 'eastmoney_real' },
    fact_registry: catlRegistry
  });
  const parsed = runRenderedCodeNode('Parse Strict JSON', mocked);
  const output = parsed.behavior_view.numeric_outputs[0];
  assert(output.value === -1.6373, `MOCK numeric output drifted: ${output.value}`);
  assert(parsed.behavior_view.claim_set[0].statement.includes('below'), 'MOCK did not derive the negative direction');
  runRenderedCodeNode('Structural Claim Validator', parsed);
});

runTest('numeric model output inconsistent with fact is rejected', () => {
  const inconsistent = JSON.parse(JSON.stringify(readJson('fixtures/model-valid.json')));
  inconsistent.numeric_outputs[0].value = -999;
  let rejected = false;
  try {
    validateBehaviorView(inconsistent, registry);
  } catch (error) {
    rejected = /does not match fact value/.test(error.message);
  }
  assert(rejected, 'inconsistent numeric model output was not rejected');
});

runTest('rendered validator rejects numeric output inconsistent with fact', () => {
  const inconsistent = JSON.parse(JSON.stringify(readJson('fixtures/model-valid.json')));
  inconsistent.numeric_outputs[0].value = -999;
  let rejected = false;
  try {
    runRenderedCodeNode('Structural Claim Validator', {
      behavior_view: inconsistent,
      fact_registry: registry
    });
  } catch (error) {
    rejected = /does not match fact value/.test(error.message);
  }
  assert(rejected, 'rendered validator accepted inconsistent numeric output');
});

runTest('invalid JSON is rejected', () => {
  let rejected = false;
  try {
    parseStrictJson(readText('fixtures/model-invalid-json.txt'));
  } catch (error) {
    rejected = true;
  }
  assert(rejected, 'invalid JSON parsed successfully');
});

runTest('unknown fact reference is rejected', () => {
  let rejected = false;
  try {
    validateBehaviorView(readJson('fixtures/model-invalid-fact-ref.json'), registry);
  } catch (error) {
    rejected = /fact.not_in_packet/.test(error.message);
  }
  assert(rejected, 'unknown fact_ref was not rejected');
});

runTest('forbidden action text is rejected', () => {
  let rejected = false;
  try {
    validateBehaviorView(readJson('fixtures/model-invalid-forbidden.json'), registry);
  } catch (error) {
    rejected = /forbidden content/.test(error.message);
  }
  assert(rejected, 'forbidden action text was not rejected');
});

runTest('T plus one fixture produces locked state', () => {
  assert(position.total_quantity === 1000, 'unexpected total quantity');
  assert(position.available_quantity === 0, 'expected no available quantity');
  assert(position.locked_quantity === 1000, 'expected locked quantity');
});

runTest('position fixture cannot be reused across symbols', () => {
  let rejected = false;
  try {
    buildFacts({ ...validQuote, symbol: '300750' }, position, validQuality.freshnessSeconds);
  } catch (error) {
    rejected = /does not match/.test(error.message);
  }
  assert(rejected, 'cross-symbol position fixture was not rejected');
});

runTest('rendered workflow rejects cross-symbol position fixture', () => {
  let rejected = false;
  try {
    runRenderedCodeNode('Calculate Facts', {
      normalized_quote: { ...validQuote, symbol: '300750' },
      quote_freshness_seconds: validQuality.freshnessSeconds
    });
  } catch (error) {
    rejected = /does not match/.test(error.message);
  }
  assert(rejected, 'rendered workflow reused a cross-symbol position fixture');
});
