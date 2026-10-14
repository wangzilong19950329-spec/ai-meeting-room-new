#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const workflowDir = path.join(root, 'workflows', 'phase2');
const domainPath = path.join(__dirname, 'phase2-domain.js');
const domainSource = fs.readFileSync(domainPath, 'utf8')
  .replace(/^'use strict';\s*/, '')
  .replace(/\nmodule\.exports = \{[\s\S]*?\};\s*$/, '');

function embedded(body) {
  return `${domainSource}\n\n${body.trim()}`;
}

function codeNode(name, jsCode, position) {
  return {
    parameters: { mode: 'runOnceForAllItems', language: 'javaScript', jsCode },
    id: name.replace(/[^A-Za-z0-9]/g, ''),
    name,
    type: 'n8n-nodes-base.code',
    typeVersion: 2,
    position
  };
}

function postgresNode(name, query, replacement, position) {
  return {
    parameters: {
      operation: 'executeQuery',
      query: query.trim(),
      options: {
        queryBatching: 'independently',
        ...(replacement ? { queryReplacement: replacement } : {})
      }
    },
    id: name.replace(/[^A-Za-z0-9]/g, ''),
    name,
    type: 'n8n-nodes-base.postgres',
    typeVersion: 2.6,
    position,
    credentials: { postgres: { id: 'SWPostgresLocal', name: 'SW PostgreSQL' } }
  };
}

function scheduleNode(position) {
  return {
    parameters: { rule: { interval: [{ field: 'minutes', minutesInterval: 1 }] } },
    id: 'ScheduleTrigger',
    name: 'Schedule Trigger',
    type: 'n8n-nodes-base.scheduleTrigger',
    typeVersion: 1.2,
    position
  };
}

function manualNode(position) {
  return {
    parameters: {},
    id: 'ManualQATrigger',
    name: 'Manual QA Trigger',
    type: 'n8n-nodes-base.manualTrigger',
    typeVersion: 1,
    position
  };
}

function connect(connections, from, to, output = 0) {
  if (!connections[from]) connections[from] = { main: [] };
  while (connections[from].main.length <= output) connections[from].main.push([]);
  connections[from].main[output].push({ node: to, type: 'main', index: 0 });
}

function workflow(id, name, nodes, connections, errorWorkflow = true) {
  return {
    id,
    name,
    active: false,
    nodes,
    connections,
    settings: {
      executionOrder: 'v1',
      ...(errorWorkflow ? { errorWorkflow: 'SW00ErrorHandler' } : {})
    },
    staticData: null,
    pinData: {},
    versionId: `phase2-local-generated-${id}`,
    triggerCount: 0,
    tags: []
  };
}

function renderErrorWorkflow() {
  const nodes = [
    {
      parameters: {},
      id: 'ErrorTrigger',
      name: 'Error Trigger',
      type: 'n8n-nodes-base.errorTrigger',
      typeVersion: 1,
      position: [0, 0]
    },
    codeNode('Normalize Error', `
const input = $input.first().json || {};
const error = input.execution && input.execution.error ? input.execution.error : (input.error || input);
const message = String(error.message || error.description || 'unknown workflow error');
const knownCodes = [
  'QUOTE_HTTP_ERROR', 'QUOTE_EMPTY', 'QUOTE_STALE', 'QUOTE_INVALID', 'BAR_INCOMPLETE',
  'DB_ERROR', 'MODEL_TIMEOUT', 'MODEL_EMPTY', 'MODEL_INVALID_JSON', 'INVALID_FACT_REF',
  'CAPABILITY_VIOLATION', 'POLICY_VIOLATION', 'REPORT_ERROR', 'JOB_TIMEOUT',
    'JOB_MAX_ATTEMPTS', 'MODEL_PROVIDER_OUTCOME_UNKNOWN', 'MODEL_RETRY_SAFETY_UNPROVEN'
];
const errorCode = knownCodes.find((code) => message.includes(code)) || 'UNCLASSIFIED_WORKFLOW_ERROR';
return [{ json: {
  workflow_name: input.workflow && input.workflow.name ? input.workflow.name : 'unknown',
  execution_id: input.execution && input.execution.id ? String(input.execution.id) : null,
  error_code: errorCode,
  error_message: message.slice(0, 2000),
  retryable: ['QUOTE_HTTP_ERROR', 'MODEL_TIMEOUT', 'DB_ERROR', 'JOB_TIMEOUT'].includes(errorCode),
  context: { lastNodeExecuted: input.execution && input.execution.lastNodeExecuted || null }
} }];
`, [240, 0]),
    postgresNode('Persist Run Failure', `
INSERT INTO sw_run_failure (
  workflow_name, execution_id, error_code, error_message, retryable, context
) VALUES (
  ($1::jsonb)->>'workflow_name',
  ($1::jsonb)->>'execution_id',
  ($1::jsonb)->>'error_code',
  ($1::jsonb)->>'error_message',
  COALESCE((($1::jsonb)->>'retryable')::boolean, false),
  COALESCE(($1::jsonb)->'context', '{}'::jsonb)
) RETURNING id, error_code, created_at;
`, '={{ JSON.stringify($json) }}', [500, 0])
  ];
  const connections = {};
  connect(connections, 'Error Trigger', 'Normalize Error');
  connect(connections, 'Normalize Error', 'Persist Run Failure');
  return workflow('SW00ErrorHandler', 'SW-00 Error Handler', nodes, connections, false);
}

function renderIngestWorkflow() {
  const nodes = [
    scheduleNode([0, -100]),
    manualNode([0, 100]),
    postgresNode('Load Ingest Control', `
SELECT ingest_enabled, java_base_url, manual_test_mode
FROM sw_runtime_control
WHERE id = 1;
`, null, [240, 0]),
    codeNode('China Trading Session Guard', embedded(`
const control = $input.first().json;
const state = tradingSessionState(new Date());
if (!control.ingest_enabled) return [];
if (!state.isTradingSession && !control.manual_test_mode) return [];
return [{ json: { ...control, session: state } }];
`), [480, 0]),
    postgresNode('Load Watchlist', `
SELECT watch.symbol, watch.market, $1::text AS java_base_url
FROM sw_watchlist watch
WHERE watch.enabled = true
ORDER BY watch.id;
`, '={{ $json.java_base_url }}', [720, 0]),
    {
      parameters: {
        method: 'GET',
        url: "={{ $json.java_base_url + '/api/stock-watch/n8n/quotes?symbols=' + encodeURIComponent($json.symbol + '.' + $json.market) }}",
        authentication: 'genericCredentialType',
        genericAuthType: 'httpHeaderAuth',
        options: { timeout: 15000, response: { response: { responseFormat: 'json' } } }
      },
      id: 'FetchJavaQuote',
      name: 'Fetch Java Quote',
      type: 'n8n-nodes-base.httpRequest',
      typeVersion: 4.3,
      position: [960, 0],
      credentials: { httpHeaderAuth: { id: 'SWJavaAdapterHeaderAuth', name: 'SW Java Adapter Header' } }
    },
    codeNode('Normalize Quote + Quality Gate', embedded(`
const snapshot = normalizeJavaQuoteEnvelope($input.first().json);
return [{ json: snapshot }];
`), [1200, 0]),
    postgresNode('Load Previous Snapshot', `
WITH current_input AS (
  SELECT $1::jsonb AS current
)
SELECT
  current_input.current,
  previous.row_data AS previous
FROM current_input
LEFT JOIN LATERAL (
  SELECT to_jsonb(snapshot) - 'raw_payload' AS row_data
  FROM sw_quote_snapshot snapshot
  WHERE snapshot.symbol = current_input.current->>'symbol'
    AND snapshot.market = current_input.current->>'market'
    AND snapshot.quote_time <= (current_input.current->>'quote_time')::timestamptz
  ORDER BY snapshot.quote_time DESC
  LIMIT 1
) previous ON true;
`, '={{ JSON.stringify($json) }}', [1440, 0]),
    codeNode('Calculate Snapshot Delta + Approx Bar', embedded(`
const row = $input.first().json;
return [{ json: calculateSnapshotDelta(row.previous || null, row.current) }];
`), [1680, 0]),
    postgresNode('Persist Snapshot + Approx Bar', `
WITH payload AS (
  SELECT $1::jsonb AS p
), snapshot_row AS (
  INSERT INTO sw_quote_snapshot (
    symbol, market, quote_time, fetched_at, provider, source, quality_status,
    price, open, day_high, day_low, previous_close,
    cumulative_volume_raw, cumulative_volume_shares, volume_unit,
    cumulative_amount_raw, amount_unit, limitations, raw_payload
  )
  SELECT
    p->'snapshot'->>'symbol', p->'snapshot'->>'market',
    (p->'snapshot'->>'quote_time')::timestamptz,
    (p->'snapshot'->>'fetched_at')::timestamptz,
    p->'snapshot'->>'provider', p->'snapshot'->>'source',
    p->'snapshot'->>'quality_status',
    (p->'snapshot'->>'price')::numeric,
    NULLIF(p->'snapshot'->>'open', '')::numeric,
    NULLIF(p->'snapshot'->>'day_high', '')::numeric,
    NULLIF(p->'snapshot'->>'day_low', '')::numeric,
    NULLIF(p->'snapshot'->>'previous_close', '')::numeric,
    NULLIF(p->'snapshot'->>'cumulative_volume_raw', '')::numeric,
    NULLIF(p->'snapshot'->>'cumulative_volume_shares', '')::numeric,
    p->'snapshot'->>'volume_unit',
    NULLIF(p->'snapshot'->>'cumulative_amount_raw', '')::numeric,
    p->'snapshot'->>'amount_unit',
    COALESCE(p->'snapshot'->'limitations', '[]'::jsonb),
    p->'snapshot'->'raw_envelope'
  FROM payload
  ON CONFLICT (symbol, market, provider, quote_time) DO UPDATE SET
    fetched_at = EXCLUDED.fetched_at,
    quality_status = EXCLUDED.quality_status,
    limitations = EXCLUDED.limitations,
    raw_payload = EXCLUDED.raw_payload
  RETURNING id
), bar_row AS (
  INSERT INTO sw_bar_1m (
    symbol, market, minute_start, open, high, low, close,
    volume_delta_raw, volume_delta_shares, amount_delta, volume_unit, amount_unit,
    vwap, source, quality_status, limitations, last_snapshot_id
  )
  SELECT
    p->'bar'->>'symbol', p->'bar'->>'market', (p->'bar'->>'minute_start')::timestamptz,
    (p->'bar'->>'open')::numeric, (p->'bar'->>'high')::numeric,
    (p->'bar'->>'low')::numeric, (p->'bar'->>'close')::numeric,
    NULLIF(p->'bar'->>'volume_delta_raw', '')::numeric,
    NULLIF(p->'bar'->>'volume_delta_shares', '')::numeric,
    NULLIF(p->'bar'->>'amount_delta', '')::numeric,
    p->'bar'->>'volume_unit', p->'bar'->>'amount_unit',
    NULLIF(p->'bar'->>'vwap', '')::numeric,
    p->'bar'->>'source', p->'bar'->>'quality_status',
    COALESCE(p->'bar'->'limitations', '[]'::jsonb), snapshot_row.id
  FROM payload, snapshot_row
  WHERE COALESCE((p->>'persistBar')::boolean, false)
  ON CONFLICT (symbol, market, minute_start) DO UPDATE SET
    high = GREATEST(sw_bar_1m.high, EXCLUDED.high),
    low = LEAST(sw_bar_1m.low, EXCLUDED.low),
    close = EXCLUDED.close,
    volume_delta_raw = EXCLUDED.volume_delta_raw,
    volume_delta_shares = EXCLUDED.volume_delta_shares,
    amount_delta = EXCLUDED.amount_delta,
    vwap = EXCLUDED.vwap,
    limitations = EXCLUDED.limitations,
    last_snapshot_id = EXCLUDED.last_snapshot_id,
    updated_at = now()
  RETURNING id
)
SELECT snapshot_row.id AS snapshot_id,
       (SELECT id FROM bar_row) AS bar_id,
       (SELECT p->'snapshot'->>'symbol' FROM payload) AS symbol,
       (SELECT p->'snapshot'->>'quote_time' FROM payload) AS quote_time
FROM snapshot_row;
`, '={{ JSON.stringify($json) }}', [1920, 0])
  ];
  const connections = {};
  connect(connections, 'Schedule Trigger', 'Load Ingest Control');
  connect(connections, 'Manual QA Trigger', 'Load Ingest Control');
  connect(connections, 'Load Ingest Control', 'China Trading Session Guard');
  connect(connections, 'China Trading Session Guard', 'Load Watchlist');
  connect(connections, 'Load Watchlist', 'Fetch Java Quote');
  connect(connections, 'Fetch Java Quote', 'Normalize Quote + Quality Gate');
  connect(connections, 'Normalize Quote + Quality Gate', 'Load Previous Snapshot');
  connect(connections, 'Load Previous Snapshot', 'Calculate Snapshot Delta + Approx Bar');
  connect(connections, 'Calculate Snapshot Delta + Approx Bar', 'Persist Snapshot + Approx Bar');
  return workflow('SW10MinuteQuoteIngest', 'SW-10 Minute Quote Ingest', nodes, connections);
}

function renderAnalysisWorkflow() {
  const nodes = [
    scheduleNode([0, -100]),
    manualNode([0, 100]),
    postgresNode('Load Analysis Control', `
SELECT analysis_enabled, model_provider, deepseek_base_url,
       manual_test_mode, manual_test_trade_date, manual_test_run_slot
FROM sw_runtime_control
WHERE id = 1;
`, null, [220, 0]),
    codeNode('Resolve Analysis Slot', embedded(`
const control = $input.first().json;
if (!control.analysis_enabled) return [];
const manual = Boolean(control.manual_test_mode);
if (manual) {
  if (!control.manual_test_trade_date || !ANALYSIS_SLOTS.has(control.manual_test_run_slot)) {
    throw new Error('INVALID_MANUAL_TEST_SLOT');
  }
  return [{ json: {
    ...control,
    trade_date: chinaDateParts(control.manual_test_trade_date).date,
    create_slot: control.manual_test_run_slot,
    trigger_source: 'manual_qa'
  } }];
}
const resolved = analysisSlot(new Date());
return [{ json: {
  ...control,
  trade_date: resolved.tradeDate,
  create_slot: resolved.slot,
  trigger_source: 'schedule'
} }];
`), [440, 0]),
    postgresNode('Recover + Create Jobs', `
WITH input AS (SELECT $1::jsonb AS p),
recovered AS (
  UPDATE sw_analysis_job
  SET status = 'RETRY', next_retry_at = now(), locked_by = NULL, locked_at = NULL,
      updated_at = now(), last_error_code = 'JOB_TIMEOUT'
  WHERE status = 'RUNNING'
    AND locked_at < now() - interval '10 minutes'
    AND attempt_count < max_attempts
), exhausted AS (
  UPDATE sw_analysis_job
  SET status = 'FAILED', updated_at = now(), last_error_code = 'JOB_MAX_ATTEMPTS'
  WHERE status IN ('RETRY', 'RUNNING') AND attempt_count >= max_attempts
), inserted AS (
  INSERT INTO sw_analysis_job (
    job_key, trigger_source, symbol, market, fixture_name, model_provider,
    status, started_at, trade_date, run_slot, analysis_mode, max_attempts
  )
  SELECT
    'job.' || watch.symbol || '.' || (p->>'trade_date') || '.' || (p->>'create_slot') || '.MARKET_BEHAVIOR',
    p->>'trigger_source', watch.symbol, watch.market, 'none', p->>'model_provider',
    'PENDING', now(), (p->>'trade_date')::date, p->>'create_slot', 'MARKET_BEHAVIOR', 3
  FROM input, sw_watchlist watch
  WHERE watch.enabled = true AND NULLIF(p->>'create_slot', '') IS NOT NULL
  ON CONFLICT (symbol, market, trade_date, run_slot, analysis_mode) WHERE trade_date IS NOT NULL AND run_slot IS NOT NULL
  DO NOTHING
  RETURNING id
)
SELECT p AS cycle, (SELECT count(*) FROM inserted) AS jobs_created
FROM input;
`, '={{ JSON.stringify($json) }}', [680, 0]),
    postgresNode('Atomic Job Claim', `
WITH cycle AS (SELECT ($1::jsonb)->'cycle' AS p), candidate AS (
  SELECT job.id
  FROM sw_analysis_job job
  WHERE job.status IN ('PENDING', 'RETRY')
    AND (job.next_retry_at IS NULL OR job.next_retry_at <= now())
    AND job.attempt_count < job.max_attempts
  ORDER BY job.created_at
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
UPDATE sw_analysis_job job
SET status = 'RUNNING',
    locked_by = 'n8n:' || pg_backend_pid() || ':' || txid_current() || ':' || (job.attempt_count + 1),
    locked_at = now(),
    attempt_count = attempt_count + 1, updated_at = now()
FROM candidate
WHERE job.id = candidate.id
RETURNING job.*, (SELECT p FROM cycle) AS cycle;
`, '={{ JSON.stringify($json) }}', [920, 0]),
    postgresNode('Load Packet Inputs', `
WITH claimed AS (SELECT $1::jsonb AS payload), boundary AS (
  SELECT
    ((job.trade_date::text || ' ' || job.run_slot)::timestamp AT TIME ZONE 'Asia/Shanghai') AS slot_end,
    to_jsonb(job) AS job_json,
    job.trade_date::text AS trade_date_text,
    job.symbol,
    job.market,
    claimed.payload,
    control.deepseek_base_url
  FROM claimed
  JOIN sw_analysis_job job ON job.id = (claimed.payload->>'id')::bigint
  CROSS JOIN sw_runtime_control control
  WHERE control.id = 1
)
SELECT
  boundary.job_json AS job,
  boundary.trade_date_text,
  boundary.slot_end,
  boundary.deepseek_base_url,
  COALESCE((
    SELECT jsonb_agg(to_jsonb(bar) ORDER BY bar.minute_start)
    FROM sw_bar_1m bar
    WHERE bar.symbol = boundary.symbol
      AND bar.market = boundary.market
      AND bar.minute_start <= boundary.slot_end
      AND bar.minute_start >= boundary.slot_end - interval '30 minutes'
  ), '[]'::jsonb) AS bars,
  COALESCE((
    SELECT to_jsonb(snapshot) - 'raw_payload'
    FROM sw_quote_snapshot snapshot
    WHERE snapshot.symbol = boundary.symbol
      AND snapshot.market = boundary.market
      AND snapshot.quote_time <= boundary.slot_end
    ORDER BY snapshot.quote_time DESC
    LIMIT 1
  ), '{}'::jsonb) AS snapshot
FROM boundary;
`, '={{ JSON.stringify($json) }}', [1160, 0]),
    codeNode('Build De-identified Fact Packet', embedded(`
const row = $input.first().json;
const job = row.job;
const packet = buildFactPacket({
  bars: row.bars,
  snapshot: row.snapshot,
  now: row.slot_end,
  job_id: job.job_key,
  symbol: job.symbol,
  market: job.market,
  trade_date: row.trade_date_text,
  run_slot: job.run_slot,
  observation_time: row.bars.length ? row.bars[row.bars.length - 1].minute_start : row.slot_end
});
return [{ json: {
  job,
  packet,
  model_provider: row.bars.length ? job.model_provider : 'MOCK',
  deepseek_base_url: row.deepseek_base_url,
  prompt_version: 'stock-watch-market-behavior-v2',
  submission_key: providerSubmissionKey(job, packet.packet_hash, job.model_provider, 'stock-watch-market-behavior-v2')
} }];
`), [1400, 0]),
    {
      parameters: {
        conditions: {
          options: { caseSensitive: true, leftValue: '', typeValidation: 'strict', version: 2 },
          conditions: [{
            id: 'provider-is-deepseek',
            leftValue: '={{ $json.model_provider }}',
            rightValue: 'DEEPSEEK_WEB',
            operator: { type: 'string', operation: 'equals' }
          }],
          combinator: 'and'
        },
        options: {}
      },
      id: 'IsDeepSeekProvider',
      name: 'Is DeepSeek Provider',
      type: 'n8n-nodes-base.if',
      typeVersion: 2.2,
      position: [1640, 0]
    },
    {
      parameters: {
        method: 'POST',
        url: "={{ $json.deepseek_base_url + '/chat/completions' }}",
        authentication: 'genericCredentialType',
        genericAuthType: 'httpHeaderAuth',
        sendBody: true,
        specifyBody: 'json',
        jsonBody: `={{ JSON.stringify({
  model: 'deepseek-web-bridge',
  messages: [
    { role: 'system', content: 'Return exactly one JSON object with keys schema_version, analysis_status, hypotheses, material_changes, unexplained_fact_refs, limitations and no extra keys. schema_version must be stock-watch.market-behavior.v1. analysis_status must be VALID, UNKNOWN, or INSUFFICIENT. hypotheses must be an array; each item must contain exactly hypothesis_id, type, evidence_band, support_fact_refs, counter_fact_refs, alternatives, next_confirmation_conditions. type must be ACCUMULATION_LIKE, DISTRIBUTION_LIKE, NORMAL_EXECUTION, or INSUFFICIENT. evidence_band must be LOW, MODERATE, HIGH, or INSUFFICIENT. All ref fields may use only supplied Fact IDs. All other listed fields must be arrays of strings. Every free-text string in alternatives, next_confirmation_conditions, material_changes, and limitations must contain no decimal digit characters. Refer to supplied facts by Fact ID and never restate, round, convert, derive, or transform their numeric values. Do not identify actors, claim manipulation, or provide actions. With L0_BAR capability never emit spoofing, wash trade, or order-book-layering hypotheses. Return JSON only.' },
    { role: 'user', content: JSON.stringify($json.packet.model_input) }
  ],
  response_format: { type: 'json_object' },
  expectedJson: true,
  temperature: 0,
  max_tokens: 1200,
  deepseek_mode: 'fast',
  metadata: {
    expected_json: true,
    deepseek_capability: 'stock.technical-analysis',
    deepseek_session_key: $json.job.job_key,
    deepseek_submission_key: $json.submission_key,
    prompt_version: $json.prompt_version,
    packet_hash: $json.packet.packet_hash
  }
}) }}`,
        options: {
          timeout: 240000,
          neverError: true,
          response: { response: { responseFormat: 'json', fullResponse: true } }
        }
      },
      id: 'CallDeepSeekFacade',
      name: 'Call DeepSeek Facade',
      type: 'n8n-nodes-base.httpRequest',
      typeVersion: 4.3,
      position: [1880, -120],
      onError: 'continueRegularOutput',
      credentials: { httpHeaderAuth: { id: 'SWDeepSeekFacadeHeaderAuth', name: 'SW DeepSeek Facade Header' } }
    },
    codeNode('Attach DeepSeek Response', `
const response = $input.first().json;
const original = $('Build De-identified Fact Packet').first().json;
const providerResponse = response && response.body !== undefined ? response.body : response;
return [{ json: { ...original, provider_response: providerResponse, provider_transport: response } }];
`, [2120, -120]),
    codeNode('MOCK Market Behavior', embedded(`
const item = $input.first().json;
const output = mockBehaviorView(item.packet);
return [{ json: {
  ...item,
  provider_response: {
    choices: [{ message: { role: 'assistant', content: JSON.stringify(output) } }],
    mock: true
  }
} }];
`), [1880, 120]),
    codeNode('Strict JSON + Structural Claim Validator', embedded(`
const item = $input.first().json;
const validation = validateModelOutput(item.provider_response, item.packet, item.provider_transport || null);
return [{ json: { ...item, validation } }];
`), [2360, 0]),
    postgresNode('Persist Analysis Result', `
WITH payload AS (SELECT $1::jsonb AS p), fenced_job AS (
  SELECT job.id
  FROM payload
  JOIN sw_analysis_job job ON job.id = (payload.p->'job'->>'id')::bigint
  WHERE job.status = 'RUNNING'
    AND job.locked_by = payload.p->'job'->>'locked_by'
    AND job.attempt_count = (payload.p->'job'->>'attempt_count')::integer
  FOR UPDATE
), packet_row AS (
  INSERT INTO sw_market_fact_packet (
    job_id, packet_hash, symbol, market, quote_time, source_kind,
    quality_status, fact_registry, packet
  )
  SELECT
    (p->'job'->>'id')::bigint, p->'packet'->>'packet_hash',
    p->'packet'->>'symbol', p->'packet'->>'market',
    (p->'packet'->>'quote_time')::timestamptz,
    p->'packet'->'provenance'->>'source_kind',
    p->'packet'->'provenance'->>'quality_status',
    p->'packet'->'facts', p->'packet'
  FROM payload, fenced_job
  ON CONFLICT (packet_hash) DO UPDATE SET
    job_id = EXCLUDED.job_id, fact_registry = EXCLUDED.fact_registry, packet = EXCLUDED.packet
  RETURNING id
), model_row AS (
  INSERT INTO sw_model_run (job_id, packet_id, provider, status, raw_response)
  SELECT
    (p->'job'->>'id')::bigint, packet_row.id, p->>'model_provider',
    p->'validation'->>'structure_status', p->'provider_response'
  FROM payload, packet_row
  ON CONFLICT (packet_id, provider) DO UPDATE SET
    status = EXCLUDED.status, raw_response = EXCLUDED.raw_response, created_at = now()
  RETURNING id
), view_row AS (
  INSERT INTO sw_behavior_view (model_run_id, view)
  SELECT model_row.id, p->'validation'->'output'
  FROM payload, model_row
  WHERE p->'validation'->>'status' = 'accepted'
  ON CONFLICT (model_run_id) DO UPDATE SET view = EXCLUDED.view, created_at = now()
  RETURNING id
), validation_row AS (
  INSERT INTO sw_structural_validation (model_run_id, status, errors, validation)
  SELECT model_row.id, p->'validation'->>'status',
         COALESCE(p->'validation'->'errors', '[]'::jsonb), p->'validation'
  FROM payload, model_row
  ON CONFLICT (model_run_id) DO UPDATE SET
    status = EXCLUDED.status, errors = EXCLUDED.errors,
    validation = EXCLUDED.validation, created_at = now()
  RETURNING id
), updated_job AS (
  UPDATE sw_analysis_job job
  SET status = CASE
        WHEN p->'validation'->>'status' <> 'accepted'
          AND COALESCE((p->'validation'->>'retryable')::boolean, false)
          AND job.attempt_count < job.max_attempts THEN 'RETRY'
        WHEN p->'validation'->>'status' <> 'accepted' THEN 'FAILED'
        WHEN p->'validation'->'output'->>'analysis_status' = 'INSUFFICIENT' THEN 'INSUFFICIENT_DATA'
        ELSE 'ANALYZED'
      END,
      completed_at = CASE
        WHEN p->'validation'->>'status' <> 'accepted'
          AND COALESCE((p->'validation'->>'retryable')::boolean, false)
          AND job.attempt_count < job.max_attempts THEN NULL
        ELSE now()
      END,
      next_retry_at = CASE
        WHEN p->'validation'->>'status' <> 'accepted'
          AND COALESCE((p->'validation'->>'retryable')::boolean, false)
          AND job.attempt_count < job.max_attempts
          THEN now() + LEAST(300, 15 * power(2, GREATEST(job.attempt_count - 1, 0))) * interval '1 second'
        ELSE NULL
      END,
      updated_at = now(), locked_by = NULL, locked_at = NULL,
      last_error_code = CASE WHEN p->'validation'->>'status' = 'accepted' THEN NULL
                             ELSE p->'validation'->>'structure_status' END
  FROM payload, fenced_job
  WHERE job.id = fenced_job.id
    AND job.status = 'RUNNING'
    AND job.locked_by = p->'job'->>'locked_by'
    AND job.attempt_count = (p->'job'->>'attempt_count')::integer
  RETURNING job.id, job.status
)
SELECT updated_job.id AS job_id, updated_job.status, packet_row.id AS packet_id,
       model_row.id AS model_run_id, validation_row.id AS validation_id,
       (SELECT p->>'model_provider' FROM payload) AS model_provider
FROM updated_job, packet_row, model_row, validation_row;
`, '={{ JSON.stringify($json) }}', [2600, 0])
  ];
  const connections = {};
  connect(connections, 'Schedule Trigger', 'Load Analysis Control');
  connect(connections, 'Manual QA Trigger', 'Load Analysis Control');
  connect(connections, 'Load Analysis Control', 'Resolve Analysis Slot');
  connect(connections, 'Resolve Analysis Slot', 'Recover + Create Jobs');
  connect(connections, 'Recover + Create Jobs', 'Atomic Job Claim');
  connect(connections, 'Atomic Job Claim', 'Load Packet Inputs');
  connect(connections, 'Load Packet Inputs', 'Build De-identified Fact Packet');
  connect(connections, 'Build De-identified Fact Packet', 'Is DeepSeek Provider');
  connect(connections, 'Is DeepSeek Provider', 'Call DeepSeek Facade', 0);
  connect(connections, 'Is DeepSeek Provider', 'MOCK Market Behavior', 1);
  connect(connections, 'Call DeepSeek Facade', 'Attach DeepSeek Response');
  connect(connections, 'Attach DeepSeek Response', 'Strict JSON + Structural Claim Validator');
  connect(connections, 'MOCK Market Behavior', 'Strict JSON + Structural Claim Validator');
  connect(connections, 'Strict JSON + Structural Claim Validator', 'Persist Analysis Result');
  return workflow('SW20HalfHourAnalysis', 'SW-20 Half-hour Analysis Pipeline', nodes, connections);
}

function renderPublisherWorkflow() {
  const nodes = [
    scheduleNode([0, -100]),
    manualNode([0, 100]),
    postgresNode('Load Publisher Control', `
SELECT publisher_enabled, notify_enabled, notification_transport, notification_recipient
FROM sw_runtime_control
WHERE id = 1;
`, null, [220, 0]),
    codeNode('Publisher Gate', `
const control = $input.first().json;
if (!control.publisher_enabled) return [];
return [{ json: control }];
`, [440, 0]),
    postgresNode('Claim Report Candidate', `
WITH control AS (SELECT $1::jsonb AS p), expired AS (
  DELETE FROM sw_report_claim
  WHERE status = 'PENDING' AND claimed_at < now() - interval '10 minutes'
), candidate AS (
  SELECT job.id
  FROM sw_analysis_job job
  WHERE job.status IN ('ANALYZED', 'INSUFFICIENT_DATA')
    AND NOT EXISTS (SELECT 1 FROM sw_protection_report report WHERE report.job_id = job.id)
    AND NOT EXISTS (SELECT 1 FROM sw_report_claim claim WHERE claim.job_id = job.id)
  ORDER BY job.created_at
  LIMIT 1
), claimed AS (
  INSERT INTO sw_report_claim (job_id, status, claim_token)
  SELECT candidate.id, 'PENDING', 'n8n:' || pg_backend_pid()
  FROM candidate
  ON CONFLICT (job_id) DO NOTHING
  RETURNING job_id
)
SELECT
  to_jsonb(job) AS job,
  to_jsonb(fact_packet) AS packet_row,
  fact_packet.packet,
  model.id AS model_run_id,
  validation.id AS validation_id,
  validation.status AS validation_status,
  snapshot.price AS current_price,
  snapshot.day_low,
  bars.window_low,
  COALESCE(to_jsonb(position), '{}'::jsonb) AS position,
  COALESCE(to_jsonb(policy), '{}'::jsonb) AS risk_policy,
  control.p AS control
FROM claimed
JOIN sw_analysis_job job ON job.id = claimed.job_id
JOIN LATERAL (
  SELECT candidate_packet.id AS packet_id,
         candidate_model.id AS model_id,
         candidate_validation.id AS validation_id
  FROM sw_market_fact_packet candidate_packet
  JOIN sw_model_run candidate_model
    ON candidate_model.packet_id = candidate_packet.id
   AND candidate_model.job_id = job.id
  JOIN sw_structural_validation candidate_validation
    ON candidate_validation.model_run_id = candidate_model.id
   AND candidate_validation.status = 'accepted'
  WHERE candidate_packet.job_id = job.id
  ORDER BY candidate_model.created_at DESC, candidate_model.id DESC
  LIMIT 1
) selected_attempt ON true
JOIN sw_market_fact_packet fact_packet ON fact_packet.id = selected_attempt.packet_id
JOIN sw_model_run model ON model.id = selected_attempt.model_id
JOIN sw_structural_validation validation ON validation.id = selected_attempt.validation_id
LEFT JOIN LATERAL (
  SELECT price, day_low
  FROM sw_quote_snapshot
  WHERE symbol = job.symbol AND market = job.market
    AND quote_time <= fact_packet.quote_time
  ORDER BY quote_time DESC LIMIT 1
) snapshot ON true
LEFT JOIN LATERAL (
  SELECT min(low) AS window_low
  FROM sw_bar_1m
  WHERE symbol = job.symbol AND market = job.market
    AND minute_start <= fact_packet.quote_time
    AND minute_start >= fact_packet.quote_time - interval '30 minutes'
) bars ON true
LEFT JOIN sw_position position ON position.symbol = job.symbol AND position.market = job.market
LEFT JOIN sw_risk_policy policy ON policy.symbol = job.symbol AND policy.market = job.market
CROSS JOIN control;
`, '={{ JSON.stringify($json) }}', [660, 0]),
    codeNode('Calculate Stress + Render Markdown', embedded(`
const item = $input.first().json;
const report = buildStressReport({
  packet: item.packet,
  validation_status: item.validation_status,
  current_price: item.current_price,
  day_low: item.day_low,
  window_low: item.window_low,
  position: item.position,
  risk_policy: item.risk_policy
});
return [{ json: { ...item, report } }];
`), [900, 0]),
    postgresNode('Persist Protection Report', `
WITH payload AS (SELECT $1::jsonb AS p), report_row AS (
  INSERT INTO sw_protection_report (
    job_id, packet_id, model_run_id, validation_id,
    report_type, status, markdown, risk_items
  )
  SELECT
    (p->'job'->>'id')::bigint,
    (p->'packet_row'->>'id')::bigint,
    (p->>'model_run_id')::bigint,
    (p->>'validation_id')::bigint,
    'STRESS_ONLY', 'generated', p->'report'->>'markdown',
    jsonb_build_array(jsonb_build_object(
      'code', p->'report'->>'hard_risk',
      'scenarios', COALESCE(p->'report'->'scenarios', '[]'::jsonb)
    ))
  FROM payload
  ON CONFLICT (packet_id) DO UPDATE SET
    status = EXCLUDED.status, markdown = EXCLUDED.markdown,
    risk_items = EXCLUDED.risk_items, created_at = now()
  RETURNING id
), job_row AS (
  UPDATE sw_analysis_job job
  SET status = 'SUCCESS', updated_at = now(), completed_at = now()
  FROM payload, report_row
  WHERE job.id = (payload.p->'job'->>'id')::bigint
  RETURNING job.id
), claim_row AS (
  UPDATE sw_report_claim claim
  SET status = 'COMPLETED', completed_at = now(), last_error = NULL
  FROM payload, report_row
  WHERE claim.job_id = (payload.p->'job'->>'id')::bigint
  RETURNING claim.job_id
)
SELECT report_row.id AS report_id, job_row.id AS job_id,
       payload.p->'report'->>'markdown' AS markdown,
       (payload.p->'control'->>'notify_enabled')::boolean AS notify_enabled,
       payload.p->'control'->>'notification_transport' AS notification_transport,
       payload.p->'control'->>'notification_recipient' AS notification_recipient
FROM payload, report_row, job_row, claim_row;
`, '={{ JSON.stringify($json) }}', [1140, 0]),
    codeNode('Prepare Local Notification', `
const item = $input.first().json;
const recipient = String(item.notification_recipient || '');
const localOnly = item.notification_transport === 'MAILPIT' && recipient.endsWith('.invalid');
return [{ json: {
  ...item,
  email_required: Boolean(item.notify_enabled && localOnly),
  recipient_masked: recipient.replace(/^(.{2}).*(@.*)$/, '$1***$2')
} }];
`, [1380, 0]),
    postgresNode('Claim Local Delivery', `
WITH payload AS (SELECT $1::jsonb AS p), stale AS (
  UPDATE sw_notification_delivery
  SET status = 'UNKNOWN', error_message = 'SEND_RESULT_UNKNOWN_AFTER_TIMEOUT'
  WHERE status = 'PENDING' AND created_at < now() - interval '10 minutes'
), claimed AS (
  INSERT INTO sw_notification_delivery (
    report_id, transport, recipient_masked, status
  )
  SELECT
    (p->>'report_id')::bigint, 'MAILPIT', p->>'recipient_masked', 'PENDING'
  FROM payload
  WHERE COALESCE((p->>'email_required')::boolean, false)
  ON CONFLICT (report_id, transport, recipient_masked) DO NOTHING
  RETURNING id
)
SELECT
  p->>'report_id' AS report_id,
  p->>'job_id' AS job_id,
  p->>'markdown' AS markdown,
  COALESCE((p->>'notify_enabled')::boolean, false) AS notify_enabled,
  p->>'notification_transport' AS notification_transport,
  p->>'notification_recipient' AS notification_recipient,
  COALESCE((p->>'email_required')::boolean, false) AS email_required,
  p->>'recipient_masked' AS recipient_masked,
  EXISTS (SELECT 1 FROM claimed) AS email_claimed
FROM payload;
`, '={{ JSON.stringify($json) }}', [1500, 0]),
    {
      parameters: {
        conditions: {
          options: { caseSensitive: true, leftValue: '', typeValidation: 'strict', version: 2 },
          conditions: [{
            id: 'local-email-enabled',
            leftValue: '={{ $json.email_claimed }}',
            rightValue: true,
            operator: { type: 'boolean', operation: 'true', singleValue: true }
          }],
          combinator: 'and'
        },
        options: {}
      },
      id: 'IsLocalEmailEnabled',
      name: 'Is Local Email Enabled',
      type: 'n8n-nodes-base.if',
      typeVersion: 2.2,
      position: [1740, 0]
    },
    {
      parameters: {
        fromEmail: 'stock-watch@local.invalid',
        toEmail: '={{ $json.notification_recipient }}',
        subject: '={{ "[LOCAL TEST] Stock Watch report " + $json.report_id }}',
        emailFormat: 'text',
        text: '={{ $json.markdown }}',
        options: { appendAttribution: false }
      },
      id: 'SendMailpitEmail',
      name: 'Send Mailpit Email',
      type: 'n8n-nodes-base.emailSend',
      typeVersion: 2.1,
      position: [1980, -100],
      credentials: { smtp: { id: 'SWMailpitLocal', name: 'SW Mailpit Local' } }
    },
    postgresNode('Record Local Delivery', `
WITH original AS (
  SELECT $1::jsonb AS mail_result, $2::jsonb AS report
)
UPDATE sw_notification_delivery delivery
SET status = 'SENT_LOCAL_TEST', provider_message_id = original.mail_result->>'messageId',
    error_message = NULL
FROM original
WHERE delivery.report_id = (original.report->>'report_id')::bigint
  AND delivery.transport = 'MAILPIT'
  AND delivery.recipient_masked = original.report->>'recipient_masked'
  AND delivery.status = 'PENDING'
RETURNING delivery.report_id, delivery.status, delivery.provider_message_id;
`, '={{ [JSON.stringify($json), JSON.stringify($("Prepare Local Notification").first().json)] }}', [2100, -100]),
    codeNode('No Notification Summary', `
const item = $input.first().json;
return [{ json: { report_id: item.report_id, status: 'generated', notification: 'disabled' } }];
`, [1980, 120])
  ];
  const connections = {};
  connect(connections, 'Schedule Trigger', 'Load Publisher Control');
  connect(connections, 'Manual QA Trigger', 'Load Publisher Control');
  connect(connections, 'Load Publisher Control', 'Publisher Gate');
  connect(connections, 'Publisher Gate', 'Claim Report Candidate');
  connect(connections, 'Claim Report Candidate', 'Calculate Stress + Render Markdown');
  connect(connections, 'Calculate Stress + Render Markdown', 'Persist Protection Report');
  connect(connections, 'Persist Protection Report', 'Prepare Local Notification');
  connect(connections, 'Prepare Local Notification', 'Claim Local Delivery');
  connect(connections, 'Claim Local Delivery', 'Is Local Email Enabled');
  connect(connections, 'Is Local Email Enabled', 'Send Mailpit Email', 0);
  connect(connections, 'Is Local Email Enabled', 'No Notification Summary', 1);
  connect(connections, 'Send Mailpit Email', 'Record Local Delivery');
  return workflow('SW30StressReportPublisher', 'SW-30 Stress Report Publisher', nodes, connections);
}

const workflows = [
  renderErrorWorkflow(),
  renderIngestWorkflow(),
  renderAnalysisWorkflow(),
  renderPublisherWorkflow()
];

fs.mkdirSync(workflowDir, { recursive: true });
for (const item of workflows) {
  const filename = `${item.name.replace(/\s+/g, '-').replace(/[^A-Za-z0-9-]/g, '')}.json`;
  fs.writeFileSync(path.join(workflowDir, filename), `${JSON.stringify(item, null, 2)}\n`);
  console.log(`rendered workflows/phase2/${filename}`);
}
