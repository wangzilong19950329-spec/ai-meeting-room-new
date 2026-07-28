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
  assert(freshnessSeconds >= 0, 'fetched_at is earlier than quote_time');
  assert(freshnessSeconds <= 300, 'quote is stale');
  assert(q.high >= q.low, 'high is below low');
  assert(q.last_price >= q.low && q.last_price <= q.high, 'last_price outside day range');
  return freshnessSeconds;
}

function buildFacts(quote, position, freshnessSeconds) {
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
    if (!registry[output.fact_ref]) errors.push(`numeric output references unknown fact_ref: ${output.fact_ref}`);
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

const validQuote = normalizeQuote(readJson('fixtures/quote-valid.json'));
const position = readJson('fixtures/position-t1-locked.json');
const validFreshness = quoteQualityGate(validQuote);
const facts = buildFacts(validQuote, position, validFreshness);
const registry = registryFromFacts(facts);
const packet = buildPacket(validQuote, facts);

runTest('valid quote passes quality gate', () => {
  assert(validFreshness === 30, `expected freshness 30, got ${validFreshness}`);
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

runTest('packet hash is stable', () => {
  const packet2 = buildPacket(validQuote, facts);
  assert(packet.packet_hash === packet2.packet_hash, 'packet hash changed for identical input');
});

runTest('valid model output is accepted', () => {
  validateBehaviorView(readJson('fixtures/model-valid.json'), registry);
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
