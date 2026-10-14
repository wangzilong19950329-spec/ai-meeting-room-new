#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"
load_env
mkdir -p "$ROOT_DIR/runtime/workflows" "$ROOT_DIR/runtime/smoke"

node "$SCRIPT_DIR/render-general-search-workflow.js" >/dev/null
fixture_log="$ROOT_DIR/runtime/smoke/general-search-fixture.log"
execution_log="$ROOT_DIR/runtime/smoke/general-search-execution.log"
fixture_pid=''
published=false
cleanup() {
  if [ "$published" = true ]; then
    compose run --rm n8n-import unpublish:workflow --id=GEN10GeneralEvidenceSearch >/dev/null 2>&1 || true
  fi
  if [ -n "$fixture_pid" ]; then
    kill "$fixture_pid" >/dev/null 2>&1 || true
    wait "$fixture_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

node "$SCRIPT_DIR/general-search-fixture.js" > "$fixture_log" 2>&1 &
fixture_pid=$!
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS http://127.0.0.1:8794/health >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS http://127.0.0.1:8794/health >/dev/null

node > "$ROOT_DIR/runtime/workflows/GEN-10-Fixture-Runner.json" <<'NODE'
const base = { fixtureMode: true, bridgeEndpoint: 'http://host.docker.internal:8794/search' };
const inputs = [
  { ...base, requestId: 'fixture-pass', question: 'fixture_pass 全球 AI Token 用量？' },
  { ...base, requestId: 'fixture-retry', question: 'fixture_retry 全球 AI Token 增速？' },
  { ...base, requestId: 'fixture-review-proof-retry', question: 'fixture_review_proof_retry 通用事实？' },
  { ...base, requestId: 'fixture-search-proof-retry', question: 'fixture_search_proof_retry 通用事实？' },
  { ...base, requestId: 'fixture-coverage-gap', question: 'fixture_coverage_gap 全球用量？' },
  { ...base, requestId: 'fixture-uncertain', question: 'fixture_uncertain 某平台用量？' },
  { ...base, requestId: 'fixture-contradicted', question: 'fixture_contradicted 某平台用量？' },
  { ...base, requestId: 'fixture-http-503', question: 'fixture_http_503 通用资料？' },
  { ...base, requestId: 'fixture-timeout', question: 'fixture_timeout 通用资料？', httpTimeoutMs: 1000 }
];
const workflow = {
  id: 'GEN10FixtureRunner', name: 'GEN-10 Fixture Runner', active: false,
  nodes: [
    { id: 'Manual', name: 'Manual Trigger', type: 'n8n-nodes-base.manualTrigger', typeVersion: 1, position: [0, 0], parameters: {} },
    { id: 'Inputs', name: 'Fixture Inputs', type: 'n8n-nodes-base.code', typeVersion: 2, position: [240, 0], parameters: { mode: 'runOnceForAllItems', language: 'javaScript', jsCode: `return ${JSON.stringify(inputs)}.map(json => ({ json }));` } },
    { id: 'Call', name: 'Call GEN-10', type: 'n8n-nodes-base.executeWorkflow', typeVersion: 1.2, position: [480, 0], parameters: { source: 'database', workflowId: { __rl: true, value: 'GEN10GeneralEvidenceSearch', mode: 'id' }, mode: 'each', options: { waitForSubWorkflow: true } } }
  ],
  connections: {
    'Manual Trigger': { main: [[{ node: 'Fixture Inputs', type: 'main', index: 0 }]] },
    'Fixture Inputs': { main: [[{ node: 'Call GEN-10', type: 'main', index: 0 }]] }
  },
  settings: { executionOrder: 'v1' }, staticData: null, pinData: {},
  versionId: 'gen10-fixture-v1', triggerCount: 0, tags: []
};
process.stdout.write(JSON.stringify(workflow, null, 2));
NODE

prior_state=$(compose exec -T postgres sh -lc \
  'PGPASSWORD="$N8N_DB_PASSWORD" psql -U "$N8N_DB_USER" -d "$N8N_DB_NAME" -Atc "select active from workflow_entity where id = '\''GEN10GeneralEvidenceSearch'\''"')
[ -n "$prior_state" ] || prior_state=f
if [ "$prior_state" != t ]; then
  compose run --rm n8n-import import:workflow \
    --input=/workflows/general/GEN-10-General-Evidence-Search.json --activeState=false >/dev/null
  compose run --rm n8n-import publish:workflow --id=GEN10GeneralEvidenceSearch >/dev/null
  published=true
fi
compose run --rm n8n-import import:workflow \
  --input=/runtime/workflows/GEN-10-Fixture-Runner.json --activeState=false >/dev/null

if ! compose run --rm n8n-import execute --id=GEN10FixtureRunner --rawOutput > "$execution_log" 2>&1; then
  tail -n 100 "$execution_log" >&2
  exit 1
fi

node - "$execution_log" <<'NODE'
const fs = require('fs');
const raw = fs.readFileSync(process.argv[2], 'utf8');
const jsonStart = raw.indexOf('\n{\n');
if (jsonStart < 0) throw new Error('N8N_EXECUTION_JSON_MISSING');
const execution = JSON.parse(raw.slice(jsonStart + 1));
if (execution.status !== 'success') throw new Error('N8N_EXECUTION_FAILED');
const items = execution.data.resultData.runData['Call GEN-10'][0].data.main[0];
const packets = new Map(items.map(item => [item.json.requestId, item.json]));
const pass = packets.get('fixture-pass');
const retry = packets.get('fixture-retry');
const proofRetry = packets.get('fixture-review-proof-retry');
const searchProofRetry = packets.get('fixture-search-proof-retry');
const coverageGap = packets.get('fixture-coverage-gap');
const uncertain = packets.get('fixture-uncertain');
const contradicted = packets.get('fixture-contradicted');
const http503 = packets.get('fixture-http-503');
const timeout = packets.get('fixture-timeout');
if (!pass || !retry || !proofRetry || !searchProofRetry || !coverageGap ||
    !uncertain || !contradicted || !http503 || !timeout) {
  throw new Error('MISSING_FIXTURE_OUTPUTS');
}
if (pass.status !== 'CROSS_CHECKED' || pass.claims.length !== 1 || pass.audit.reviewCalls !== 1) {
  throw new Error('GENERAL_PASS_FAILED: ' + JSON.stringify(pass));
}
if (retry.status !== 'CROSS_CHECKED' || retry.audit.searchCalls !== 2 ||
    retry.audit.reviewCalls !== 2 || retry.claims[0]?.value !== '10') {
  throw new Error('TARGETED_RETRY_FAILED: ' + JSON.stringify(retry));
}
if (proofRetry.status !== 'CROSS_CHECKED' || proofRetry.audit.searchCalls !== 1 ||
    proofRetry.audit.reviewCalls !== 2 || proofRetry.claims.length !== 1) {
  throw new Error('REVIEW_PROOF_RETRY_FAILED: ' + JSON.stringify(proofRetry));
}
if (searchProofRetry.status !== 'CROSS_CHECKED' ||
    searchProofRetry.audit.searchCalls !== 2 || searchProofRetry.audit.reviewCalls !== 1 ||
    searchProofRetry.claims.length !== 1) {
  throw new Error('SEARCH_PROOF_RETRY_FAILED: ' + JSON.stringify(searchProofRetry));
}
if (coverageGap.status !== 'CROSS_CHECKED' || coverageGap.audit.searchCalls !== 2 ||
    coverageGap.audit.reviewCalls !== 2 || coverageGap.claims.length !== 2 ||
    coverageGap.claims[1]?.id !== 'global_usage') {
  throw new Error('COVERAGE_GAP_RETRY_FAILED: ' + JSON.stringify(coverageGap));
}
if (uncertain.status !== 'UNVERIFIED' || uncertain.claims.length !== 0 ||
    uncertain.provisionalClaims.length !== 1 ||
    uncertain.provisionalClaims[0].reviewVerdict !== 'UNKNOWN' ||
    uncertain.provisionalClaims[0].confidenceScore !== 0.25 ||
    uncertain.audit.trail.filter(item => item.phase === 'review' && item.verdict === 'UNKNOWN').length !== 3) {
  throw new Error('LOW_CONFIDENCE_UNKNOWN_FAILED: ' + JSON.stringify(uncertain));
}
if (contradicted.status !== 'UNVERIFIED' || contradicted.claims.length !== 0 ||
    contradicted.provisionalClaims.length !== 0 ||
    contradicted.audit.trail.filter(item => item.phase === 'review' && item.verdict === 'REJECT').length !== 3) {
  throw new Error('CONTRADICTED_CLAIM_LEAKED: ' + JSON.stringify(contradicted));
}
if (http503.status !== 'ERROR' || http503.claims.length ||
    http503.provisionalClaims.length || http503.limitations[0] !== 'BRIDGE_HTTP_503' ||
    http503.audit.reviewCalls !== 0) {
  throw new Error('HTTP_503_NOT_CONTAINED: ' + JSON.stringify(http503));
}
if (timeout.status !== 'ERROR' || timeout.claims.length ||
    timeout.provisionalClaims.length ||
    timeout.limitations[0] !== 'BRIDGE_TRANSPORT_OUTCOME_UNKNOWN' ||
    timeout.audit.searchCalls !== 1 || timeout.audit.reviewCalls !== 0) {
  throw new Error('TIMEOUT_NOT_CONTAINED: ' + JSON.stringify(timeout));
}
console.log('GEN-10 n8n smoke passed: generic pass, targeted/coverage retries, low-confidence UNKNOWN, contradicted exclusion, search/review proof retries, HTTP 503, and transport timeout');
NODE

if [ "$published" = true ]; then
  compose run --rm n8n-import unpublish:workflow --id=GEN10GeneralEvidenceSearch >/dev/null
  published=false
fi
state=$(compose exec -T postgres sh -lc \
  'PGPASSWORD="$N8N_DB_PASSWORD" psql -U "$N8N_DB_USER" -d "$N8N_DB_NAME" -Atc "select active from workflow_entity where id = '\''GEN10GeneralEvidenceSearch'\''"')
[ "$state" = "$prior_state" ] || { echo '[error] GEN-10 publish state changed during smoke test' >&2; exit 1; }
