#!/usr/bin/env node
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const resolveRootPath = (configuredPath, fallbackPath) => {
  const selected = configuredPath || fallbackPath;
  return path.isAbsolute(selected) ? selected : path.join(root, selected);
};
const readJson = (configuredPath, fallbackPath) =>
  JSON.parse(fs.readFileSync(resolveRootPath(configuredPath, fallbackPath), 'utf8'));

const quoteFixture = readJson(process.env.PHASE1_QUOTE_FIXTURE_PATH, 'fixtures/quote-valid.json');
const positionFixture = readJson(process.env.PHASE1_POSITION_FIXTURE_PATH, 'fixtures/position-t1-locked.json');
const modelFixture = readJson(null, 'fixtures/model-valid.json');
const workflowOutputPath = resolveRootPath(
  process.env.PHASE1_WORKFLOW_OUTPUT,
  'workflows/phase1/SW-01-POC-Manual-Pipeline.json'
);
const workflowId = process.env.PHASE1_WORKFLOW_ID || 'SW01Phase1POC';
const workflowName = process.env.PHASE1_WORKFLOW_NAME || 'SW-01 POC Manual Pipeline';
if (!/^[A-Za-z0-9_-]+$/.test(workflowId)) {
  throw new Error('PHASE1_WORKFLOW_ID must contain only letters, digits, underscore, or hyphen');
}

const sql = `
WITH payload AS (
  SELECT $1::jsonb AS p
),
job_row AS (
  INSERT INTO sw_analysis_job (
    job_key,
    trigger_source,
    symbol,
    market,
    fixture_name,
    model_provider,
    status,
    started_at,
    completed_at
  )
  SELECT
    p->>'job_key',
    p->>'trigger_source',
    p->>'symbol',
    p->>'market',
    p->>'fixture_name',
    p->>'model_provider',
    p->>'job_status',
    (p->>'started_at')::timestamptz,
    (p->>'completed_at')::timestamptz
  FROM payload
  ON CONFLICT (job_key) DO UPDATE SET
    status = EXCLUDED.status,
    completed_at = EXCLUDED.completed_at
  RETURNING id
),
packet_row AS (
  INSERT INTO sw_market_fact_packet (
    job_id,
    packet_hash,
    symbol,
    market,
    quote_time,
    source_kind,
    quality_status,
    fact_registry,
    packet
  )
  SELECT
    job_row.id,
    p->'packet'->>'packet_hash',
    p->>'symbol',
    p->>'market',
    (p->'packet'->>'quote_time')::timestamptz,
    p->'packet'->>'source_kind',
    p->>'quality_status',
    p->'fact_registry',
    p->'packet'
  FROM payload, job_row
  ON CONFLICT (packet_hash) DO UPDATE SET
    job_id = EXCLUDED.job_id,
    fact_registry = EXCLUDED.fact_registry,
    packet = EXCLUDED.packet,
    quality_status = EXCLUDED.quality_status
  RETURNING id
),
model_row AS (
  INSERT INTO sw_model_run (
    job_id,
    packet_id,
    provider,
    status,
    raw_response
  )
  SELECT
    job_row.id,
    packet_row.id,
    p->>'model_provider',
    p->>'model_status',
    p->'behavior_view'
  FROM payload, job_row, packet_row
  ON CONFLICT (packet_id, provider) DO UPDATE SET
    job_id = EXCLUDED.job_id,
    status = EXCLUDED.status,
    raw_response = EXCLUDED.raw_response,
    created_at = now()
  RETURNING id
),
view_row AS (
  INSERT INTO sw_behavior_view (
    model_run_id,
    view
  )
  SELECT
    model_row.id,
    p->'behavior_view'
  FROM payload, model_row
  ON CONFLICT (model_run_id) DO UPDATE SET
    view = EXCLUDED.view,
    created_at = now()
  RETURNING id
),
validation_row AS (
  INSERT INTO sw_structural_validation (
    model_run_id,
    status,
    errors,
    validation
  )
  SELECT
    model_row.id,
    p->>'validation_status',
    p->'validation_errors',
    p->'validation'
  FROM payload, model_row
  ON CONFLICT (model_run_id) DO UPDATE SET
    status = EXCLUDED.status,
    errors = EXCLUDED.errors,
    validation = EXCLUDED.validation,
    created_at = now()
  RETURNING id
),
report_row AS (
  INSERT INTO sw_protection_report (
    job_id,
    packet_id,
    model_run_id,
    validation_id,
    report_type,
    status,
    markdown,
    risk_items
  )
  SELECT
    job_row.id,
    packet_row.id,
    model_row.id,
    validation_row.id,
    p->>'report_type',
    p->>'report_status',
    p->>'report_markdown',
    p->'risk_items'
  FROM payload, job_row, packet_row, model_row, validation_row
  ON CONFLICT (packet_id) DO UPDATE SET
    job_id = EXCLUDED.job_id,
    model_run_id = EXCLUDED.model_run_id,
    validation_id = EXCLUDED.validation_id,
    status = EXCLUDED.status,
    markdown = EXCLUDED.markdown,
    risk_items = EXCLUDED.risk_items,
    created_at = now()
  RETURNING id
)
SELECT
  job_row.id AS job_id,
  packet_row.id AS packet_id,
  model_row.id AS model_run_id,
  validation_row.id AS validation_id,
  report_row.id AS report_id,
  (SELECT p->'packet'->>'packet_hash' FROM payload) AS packet_hash,
  (SELECT p->>'report_type' FROM payload) AS report_type
FROM job_row, packet_row, model_row, validation_row, report_row;
`.trim();

const code = {
  loadQuoteFixture: `
const quote = ${JSON.stringify(quoteFixture, null, 2)};
return [{ json: { quote_fixture: quote } }];
`.trim(),

  normalizeQuote: `
const item = $input.first().json;
const q = item.quote_fixture;

function requireNumber(name, value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(name + ' must be a finite number');
  }
}

['last_price', 'previous_close', 'open', 'high', 'low', 'volume'].forEach((name) => requireNumber(name, q[name]));

const quoteTimeMs = Date.parse(q.quote_time);
const fetchedAtMs = Date.parse(q.fetched_at);
if (!Number.isFinite(quoteTimeMs) || !Number.isFinite(fetchedAtMs)) {
  throw new Error('quote_time and fetched_at must be ISO timestamps');
}
if (q.field_semantics.amount !== 'unknown' || q.field_semantics.main_inflow !== 'unknown') {
  throw new Error('Phase1 requires unknown semantics for amount and main_inflow before they can be used');
}

const normalized = {
  fixture_name: q.fixture_name,
  source_kind: q.source_kind,
  symbol: q.symbol,
  market: q.market,
  exchange_symbol: q.exchange_symbol,
  currency: q.currency,
  last_price: q.last_price,
  previous_close: q.previous_close,
  open: q.open,
  high: q.high,
  low: q.low,
  volume: q.volume,
  amount: q.amount,
  main_inflow: q.main_inflow,
  quote_time: new Date(quoteTimeMs).toISOString(),
  fetched_at: new Date(fetchedAtMs).toISOString(),
  previous_snapshot: q.previous_snapshot,
  field_semantics: q.field_semantics,
};

for (const optionalName of [
  'stock_name',
  'source_url',
  'market_session_state',
  'market_session_basis',
  'raw_eastmoney'
]) {
  if (q[optionalName] !== undefined) normalized[optionalName] = q[optionalName];
}

return [{ json: { ...item, normalized_quote: normalized } }];
`.trim(),

  quoteQualityGate: `
const item = $input.first().json;
const q = item.normalized_quote;
const freshnessSeconds = Math.round((Date.parse(q.fetched_at) - Date.parse(q.quote_time)) / 1000);
const errors = [];
const isRealQuote = q.source_kind === 'eastmoney_real';
const allowedSessionStates = ['OPEN', 'MIDDAY_BREAK', 'CLOSED'];
const hasValidSessionState = allowedSessionStates.includes(q.market_session_state);
const isClosedSession = isRealQuote && hasValidSessionState && q.market_session_state !== 'OPEN';
const qualityLimitations = [];

if (isRealQuote && !hasValidSessionState) errors.push('market_session_state is invalid');
if (freshnessSeconds < 0) errors.push('fetched_at is earlier than quote_time');
if (freshnessSeconds > 300 && !isClosedSession) errors.push('quote is stale');
if (q.high < q.low) errors.push('high is below low');
if (q.last_price < q.low || q.last_price > q.high) errors.push('last_price is outside day range');

if (isClosedSession && freshnessSeconds > 300) {
  qualityLimitations.push('QUOTE_STALE_OUTSIDE_TRADING_SESSION');
}

if (errors.length) {
  throw new Error('Quote Quality Gate rejected quote: ' + errors.join('; '));
}

const qualityStatus = isRealQuote
  ? (isClosedSession ? 'closed_session_snapshot' : 'fresh_real')
  : 'fresh_fixture';

return [{
  json: {
    ...item,
    quality_status: qualityStatus,
    quality_limitations: qualityLimitations,
    quote_freshness_seconds: freshnessSeconds
  }
}];
`.trim(),

  buildApproxBars: `
const item = $input.first().json;
const q = item.normalized_quote;
const previous = q.previous_snapshot || {};
const isFixture = q.source_kind === 'fixture';

const bars = [
  {
    time: previous.quote_time || q.quote_time,
    close: previous.last_price || q.previous_close,
    source: 'previous_snapshot'
  },
  {
    time: q.quote_time,
    open: q.open,
    high: q.high,
    low: q.low,
    close: q.last_price,
    volume: q.volume,
    source: 'single_quote_approximation'
  }
];

return [{
  json: {
    ...item,
    approximate_bars: bars,
    approximation_note: isFixture
      ? 'Bars are approximate fixture-derived context, not full-session market bars.'
      : 'Bars are approximate quote-derived context, not full-session market bars.'
  }
}];
`.trim(),

  calculateFacts: `
const item = $input.first().json;
const q = item.normalized_quote;
const position = ${JSON.stringify(positionFixture, null, 2)};
if (position.symbol !== q.symbol || position.market !== q.market) {
  throw new Error('Position fixture does not match quote identity');
}
const isFixture = q.source_kind === 'fixture';
const quoteQuality = isFixture ? 'fixture' : 'source_quote';
const derivedQuality = isFixture ? 'derived_from_fixture' : 'derived_from_source_quote';

const round4 = (value) => Math.round(value * 10000) / 10000;
const priceChangePct = round4(((q.last_price - q.previous_close) / q.previous_close) * 100);
const dayRangePct = round4(((q.high - q.low) / q.previous_close) * 100);

const facts = [
  { id: 'fact.last_price', value: q.last_price, unit: q.currency, source: q.source_kind, quality: quoteQuality },
  { id: 'fact.previous_close', value: q.previous_close, unit: q.currency, source: q.source_kind, quality: quoteQuality },
  { id: 'fact.price_change_pct', value: priceChangePct, unit: 'percent', source: q.source_kind, quality: derivedQuality },
  { id: 'fact.day_range_pct', value: dayRangePct, unit: 'percent', source: q.source_kind, quality: derivedQuality },
  { id: 'fact.quote_freshness_seconds', value: item.quote_freshness_seconds, unit: 'seconds', source: q.source_kind, quality: 'quality_gate' },
  { id: 'fact.amount_semantics', value: q.field_semantics.amount, unit: 'enum', source: 'api_discovery', quality: 'unknown_semantics' },
  { id: 'fact.position_total_quantity', value: position.total_quantity, unit: 'shares', source: position.fixture_name, quality: 'fixture' },
  { id: 'fact.available_quantity', value: position.available_quantity, unit: 'shares', source: position.fixture_name, quality: 'fixture' },
  { id: 'fact.position_locked_quantity', value: position.locked_quantity, unit: 'shares', source: position.fixture_name, quality: 'fixture' }
];

return [{ json: { ...item, position_fixture: position, facts } }];
`.trim(),

  buildFactRegistry: `
const item = $input.first().json;
const registry = {};
for (const fact of item.facts) {
  if (registry[fact.id]) throw new Error('Duplicate fact id: ' + fact.id);
  registry[fact.id] = fact;
}
return [{ json: { ...item, fact_registry: registry } }];
`.trim(),

  buildPacketAndHash: `
const item = $input.first().json;
const q = item.normalized_quote;

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

const modelInput = {
  symbol: q.symbol,
  market: q.market,
  quote_time: q.quote_time,
  facts: item.facts,
  approximate_bars: item.approximate_bars,
  approximation_note: item.approximation_note
};

const packetCore = {
  schema_version: 'phase1.market_fact_packet.v1',
  source_kind: q.source_kind,
  symbol: q.symbol,
  market: q.market,
  quote_time: q.quote_time,
  facts: item.facts,
  approximate_bars: item.approximate_bars,
  model_input: modelInput
};

if (q.source_kind !== 'fixture') {
  packetCore.source_metadata = {
    stock_name: q.stock_name || null,
    exchange_symbol: q.exchange_symbol,
    source_url: q.source_url || null,
    fetched_at: q.fetched_at,
    market_session_state: q.market_session_state || null,
    market_session_basis: q.market_session_basis || null,
    quote_freshness_seconds: item.quote_freshness_seconds,
    quality_status: item.quality_status,
    quality_limitations: item.quality_limitations || [],
    raw_eastmoney: q.raw_eastmoney || null
  };
}

const packetHash = 'fnv1a32:' + fnv1a32(stableStringify(packetCore));
const packet = {
  ...packetCore,
  packet_hash: packetHash,
  hash_algorithm: 'fnv1a32-local-stable'
};

return [{
  json: {
    ...item,
    packet,
    packet_hash: packetHash,
    packet_stable_material: stableStringify(packetCore)
  }
}];
`.trim(),

  resolveModelProvider: `
const item = $input.first().json;
const configured = 'MOCK';
if (configured !== 'MOCK') {
  throw new Error('Phase1 only permits MODEL_PROVIDER=MOCK');
}
return [{ json: { ...item, model_provider: configured } }];
`.trim(),

  mockMarketBehavior: `
const item = $input.first().json;
const mockView = ${JSON.stringify(modelFixture, null, 2)};
const priceChangeFact = item.fact_registry['fact.price_change_pct'];
if (!priceChangeFact || typeof priceChangeFact.value !== 'number' || !Number.isFinite(priceChangeFact.value)) {
  throw new Error('MOCK requires numeric fact.price_change_pct');
}

const relation = priceChangeFact.value > 0 ? 'above' : (priceChangeFact.value < 0 ? 'below' : 'equal to');
const priceClaim = mockView.claim_set.find((claim) => claim.claim_id === 'c1');
if (!priceClaim) throw new Error('MOCK template is missing claim c1');
priceClaim.statement = 'The latest ' + (item.normalized_quote.source_kind === 'fixture' ? 'fixture' : 'source')
  + ' price is ' + relation + ' the previous close.';

for (const output of mockView.numeric_outputs) {
  const fact = item.fact_registry[output.fact_ref];
  if (!fact || typeof fact.value !== 'number' || !Number.isFinite(fact.value)) {
    throw new Error('MOCK numeric output requires numeric fact: ' + output.fact_ref);
  }
  output.value = fact.value;
}

if (item.normalized_quote.source_kind !== 'fixture') {
  mockView.uncertainty[0] = 'single real-source quote snapshot only';
}
return [{
  json: {
    ...item,
    raw_model_response: JSON.stringify(mockView),
    model_source: 'fixture:model-valid.json'
  }
}];
`.trim(),

  parseStrictJson: `
const item = $input.first().json;
let parsed;
try {
  parsed = JSON.parse(item.raw_model_response);
} catch (error) {
  throw new Error('Strict JSON parse failed: ' + error.message);
}
if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
  throw new Error('Strict JSON parse failed: response must be an object');
}
return [{ json: { ...item, behavior_view: parsed } }];
`.trim(),

  structuralClaimValidator: `
const item = $input.first().json;
const view = item.behavior_view;
const factRegistry = item.fact_registry;
const errors = [];
const checkedFactRefs = [];
const forbiddenHits = [];
const forbiddenPatterns = [
  /\\bwin\\s*rate\\b/i,
  /\\bcvar\\b/i,
  /\\btarget\\s*position\\b/i,
  /\\bautomated\\s*action\\b/i,
  /\\bmarket\\s*manipulation\\b/i,
  /\\bhidden\\s*actor/i,
  /\\bbuy\\b/i,
  /\\bsell\\b/i
];

if (view.schema_version !== 'phase1.market_behavior_view.v1') {
  errors.push('schema_version mismatch');
}
if (!Array.isArray(view.claim_set)) errors.push('claim_set must be an array');
if (!Array.isArray(view.uncertainty)) errors.push('uncertainty must be an array');
if (!Array.isArray(view.numeric_outputs)) errors.push('numeric_outputs must be an array');
if (view.prohibited_capabilities_used !== false) errors.push('prohibited_capabilities_used must be false');
if (view.recommendation !== null) errors.push('recommendation must be null in Phase1');

for (const claim of view.claim_set || []) {
  if (!Array.isArray(claim.fact_refs) || claim.fact_refs.length === 0) {
    errors.push('claim ' + claim.claim_id + ' has no fact_refs');
    continue;
  }
  for (const ref of claim.fact_refs) {
    checkedFactRefs.push(ref);
    if (!factRegistry[ref]) errors.push('unknown fact_ref: ' + ref);
  }
  for (const pattern of forbiddenPatterns) {
    if (pattern.test(claim.statement || '')) forbiddenHits.push(pattern.source);
  }
}

for (const output of view.numeric_outputs || []) {
  checkedFactRefs.push(output.fact_ref);
  const fact = factRegistry[output.fact_ref];
  if (!fact) {
    errors.push('numeric output references unknown fact_ref: ' + output.fact_ref);
    continue;
  }
  if (typeof output.value !== 'number' || !Number.isFinite(output.value)) {
    errors.push('numeric output is not finite for fact_ref: ' + output.fact_ref);
    continue;
  }
  if (typeof fact.value !== 'number' || !Number.isFinite(fact.value) || output.value !== fact.value) {
    errors.push('numeric output does not match fact value: ' + output.fact_ref);
  }
}

if (forbiddenHits.length) errors.push('forbidden content detected: ' + forbiddenHits.join(','));

const validation = {
  status: errors.length ? 'rejected' : 'accepted',
  errors,
  checked_fact_refs: Array.from(new Set(checkedFactRefs)),
  forbidden_content_hits: Array.from(new Set(forbiddenHits))
};

if (validation.status !== 'accepted') {
  throw new Error('Structural Claim Validator rejected model output: ' + errors.join('; '));
}

return [{
  json: {
    ...item,
    validation,
    validation_status: validation.status,
    validation_errors: validation.errors
  }
}];
`.trim(),

  calculateStressOnly: `
const item = $input.first().json;
const position = item.position_fixture;
const locked = Number(position.locked_quantity || 0);
const available = Number(position.available_quantity || 0);
const riskItems = [];

if (available === 0 && locked > 0) {
  riskItems.push({
    code: 'T1_LOCKED',
    severity: 'high',
    description: 'Full fixture position is unavailable under the T plus one constraint.',
    fact_refs: ['fact.position_locked_quantity', 'fact.available_quantity']
  });
}

riskItems.push({
  code: 'FACT_LIMITED_SCOPE',
  severity: 'medium',
  description: item.normalized_quote.source_kind === 'fixture'
    ? 'Quote fixture and approximate bars are not full-session market evidence.'
    : 'Single-source quote and approximate bars are not full-session market evidence.',
  fact_refs: ['fact.quote_freshness_seconds', 'fact.amount_semantics']
});

return [{
  json: {
    ...item,
    report_type: 'STRESS_ONLY',
    report_status: 'generated',
    risk_items: riskItems
  }
}];
`.trim(),

  renderMarkdown: `
const item = $input.first().json;
const q = item.normalized_quote;
const isFixture = q.source_kind === 'fixture';
const lines = [
  '# Stock Watch Stress Snapshot',
  '',
  'Mode: STRESS_ONLY',
  'Symbol: ' + q.market + ':' + q.symbol,
  'Packet hash: ' + item.packet_hash,
  'Quality: ' + item.quality_status,
  '',
  isFixture ? '## Fixture Scope' : '## Source Scope',
  isFixture
    ? '- Source: fixture quote and fixture position only.'
    : '- Quote source: Eastmoney real quote endpoint; position remains a Phase1 fixture.',
  ...(isFixture ? [] : [
    '- Stock name: ' + (q.stock_name || 'unknown'),
    '- Market session state: ' + (q.market_session_state || 'unknown'),
    '- Quote time: ' + q.quote_time,
    '- Fetched at: ' + q.fetched_at,
    '- Quality limitations: ' + ((item.quality_limitations || []).join(', ') || 'none')
  ]),
  '- Amount and main inflow semantics remain unknown.',
  '- Approximate bars are context only.',
  '',
  '## Stress Constraints',
  '- Total quantity: ' + item.position_fixture.total_quantity,
  '- Available quantity: ' + item.position_fixture.available_quantity,
  '- Locked quantity: ' + item.position_fixture.locked_quantity,
  '- Lock reason: ' + item.position_fixture.lock_reason,
  '',
  '## Risk Items',
  ...item.risk_items.map((risk) => '- [' + risk.severity + '] ' + risk.code + ': ' + risk.description),
  '',
  '## Boundary',
  'No action instruction or numeric forecast is generated.'
];

return [{ json: { ...item, report_markdown: lines.join('\\n') } }];
`.trim(),

  buildPersistencePayload: `
const item = $input.first().json;
const completedAt = new Date().toISOString();
const jobKey = [
  'phase1',
  item.normalized_quote.market,
  item.normalized_quote.symbol,
  item.normalized_quote.quote_time,
  item.packet_hash
].join(':');

const persistPayload = {
  job_key: jobKey,
  trigger_source: 'manual_poc',
  symbol: item.normalized_quote.symbol,
  market: item.normalized_quote.market,
  fixture_name: item.normalized_quote.fixture_name,
  model_provider: item.model_provider,
  job_status: 'completed',
  started_at: item.normalized_quote.fetched_at,
  completed_at: completedAt,
  quality_status: item.quality_status,
  packet: item.packet,
  fact_registry: item.fact_registry,
  behavior_view: item.behavior_view,
  model_status: 'accepted',
  validation_status: item.validation_status,
  validation_errors: item.validation_errors,
  validation: item.validation,
  report_type: item.report_type,
  report_status: item.report_status,
  report_markdown: item.report_markdown,
  risk_items: item.risk_items
};

return [{ json: { ...item, persist_payload: persistPayload } }];
`.trim(),

  resultSummary: `
const row = $input.first().json;
return [{
  json: {
    phase: 'Phase1',
    workflow: ${JSON.stringify(workflowName)},
    status: 'completed',
    job_id: row.job_id,
    packet_id: row.packet_id,
    model_run_id: row.model_run_id,
    validation_id: row.validation_id,
    report_id: row.report_id,
    packet_hash: row.packet_hash,
    report_type: row.report_type
  }
}];
`.trim()
};

const nodes = [];

function addNode(node) {
  nodes.push(node);
}

function codeNode(name, jsCode, x, y) {
  addNode({
    parameters: {
      mode: 'runOnceForAllItems',
      language: 'javaScript',
      jsCode
    },
    id: name.replace(/[^A-Za-z0-9]/g, ''),
    name,
    type: 'n8n-nodes-base.code',
    typeVersion: 2,
    position: [x, y]
  });
}

addNode({
  parameters: {},
  id: 'ManualTrigger',
  name: 'Manual Trigger',
  type: 'n8n-nodes-base.manualTrigger',
  typeVersion: 1,
  position: [0, 0]
});

codeNode('Load Quote Fixture', code.loadQuoteFixture, 220, 0);
codeNode('Normalize Quote', code.normalizeQuote, 440, 0);
codeNode('Quote Quality Gate', code.quoteQualityGate, 660, 0);
codeNode('Build Approx Bars', code.buildApproxBars, 880, 0);
codeNode('Calculate Facts', code.calculateFacts, 1100, 0);
codeNode('Build Fact Registry', code.buildFactRegistry, 1320, 0);
codeNode('Build Packet + Hash', code.buildPacketAndHash, 1540, 0);
codeNode('Resolve MODEL_PROVIDER', code.resolveModelProvider, 1760, 0);
codeNode('MOCK Market Behavior', code.mockMarketBehavior, 1980, 0);
codeNode('Parse Strict JSON', code.parseStrictJson, 2200, 0);
codeNode('Structural Claim Validator', code.structuralClaimValidator, 2420, 0);
codeNode('Calculate STRESS_ONLY', code.calculateStressOnly, 2640, 0);
codeNode('Render Markdown', code.renderMarkdown, 2860, 0);
codeNode('Build Persistence Payload', code.buildPersistencePayload, 3080, 0);

addNode({
  parameters: {
    operation: 'executeQuery',
    query: sql,
    options: {
      queryBatching: 'independently',
      queryReplacement: '={{ JSON.stringify($json.persist_payload) }}'
    }
  },
  id: 'PersistPhase1Records',
  name: 'Persist Phase1 Records',
  type: 'n8n-nodes-base.postgres',
  typeVersion: 2.6,
  position: [3300, 0],
  credentials: {
    postgres: {
      id: 'SWPostgresLocal',
      name: 'SW PostgreSQL'
    }
  }
});

codeNode('Phase1 Result Summary', code.resultSummary, 3520, 0);

const chain = nodes.map((node) => node.name);
const connections = {};
for (let i = 0; i < chain.length - 1; i += 1) {
  connections[chain[i]] = {
    main: [[{ node: chain[i + 1], type: 'main', index: 0 }]]
  };
}

const workflow = {
  id: workflowId,
  name: workflowName,
  active: false,
  nodes,
  connections,
  settings: {
    executionOrder: 'v1'
  },
  staticData: null,
  pinData: {},
  versionId: 'phase1-local-generated-' + workflowId,
  triggerCount: 0,
  tags: []
};

fs.mkdirSync(path.dirname(workflowOutputPath), { recursive: true });
fs.writeFileSync(workflowOutputPath, JSON.stringify(workflow, null, 2) + '\n');
console.log('rendered ' + path.relative(root, workflowOutputPath));
