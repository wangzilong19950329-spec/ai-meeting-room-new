#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck disable=SC1091
. "$SCRIPT_DIR/common.sh"
load_env
mkdir -p "$ROOT_DIR/runtime/workflows" "$ROOT_DIR/runtime/smoke"

node "$SCRIPT_DIR/render-meeting-evidence-workflow.js" >/dev/null
fixture_log="$ROOT_DIR/runtime/smoke/meeting-evidence-fixture.log"
execution_log="$ROOT_DIR/runtime/smoke/meeting-evidence-execution.log"
fixture_pid=''
published=false
cleanup() {
  if [ "$published" = true ]; then
    compose run --rm n8n-import unpublish:workflow --id=MR60EvidenceGapCrossSearch >/dev/null 2>&1 || true
  fi
  if [ -n "$fixture_pid" ]; then
    kill "$fixture_pid" >/dev/null 2>&1 || true
    wait "$fixture_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

node "$SCRIPT_DIR/meeting-evidence-fixture.js" > "$fixture_log" 2>&1 &
fixture_pid=$!
for i in 1 2 3 4 5 6 7 8 9 10; do
  if curl -fsS http://127.0.0.1:8794/health >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS http://127.0.0.1:8794/health >/dev/null

node > "$ROOT_DIR/runtime/workflows/MR-60-Fixture-Runner.json" <<'NODE'
const inputCode = `return [
  { json: { requestId: 'fixture-pass', symbol: '300750.sz', gap: { id: 'fixture_pass', question: '储能项目规模和状态？' }, fixtureMode: true, bridgeEndpoint: 'http://host.docker.internal:8794/search' } },
  { json: { requestId: 'fixture-reject', symbol: '300750.sz', gap: { id: 'fixture_reject', question: '储能项目规模和状态？' }, fixtureMode: true, bridgeEndpoint: 'http://host.docker.internal:8794/search' } },
  { json: { requestId: 'fixture-http-503', symbol: '300750.sz', gap: { id: 'fixture_http_503', question: '模拟提供者登录失效？' }, fixtureMode: true, bridgeEndpoint: 'http://host.docker.internal:8794/search' } }
];`;
const workflow = {
  id: 'MR60FixtureRunner', name: 'MR-60 Fixture Runner', active: false,
  nodes: [
    { id: 'Manual', name: 'Manual Trigger', type: 'n8n-nodes-base.manualTrigger', typeVersion: 1, position: [0, 0], parameters: {} },
    { id: 'Inputs', name: 'Fixture Inputs', type: 'n8n-nodes-base.code', typeVersion: 2, position: [240, 0], parameters: { mode: 'runOnceForAllItems', language: 'javaScript', jsCode: inputCode } },
    { id: 'Call', name: 'Call MR-60', type: 'n8n-nodes-base.executeWorkflow', typeVersion: 1.2, position: [480, 0], parameters: { source: 'database', workflowId: { __rl: true, value: 'MR60EvidenceGapCrossSearch', mode: 'id' }, mode: 'each', options: { waitForSubWorkflow: true } } }
  ],
  connections: {
    'Manual Trigger': { main: [[{ node: 'Fixture Inputs', type: 'main', index: 0 }]] },
    'Fixture Inputs': { main: [[{ node: 'Call MR-60', type: 'main', index: 0 }]] }
  },
  settings: { executionOrder: 'v1' }, staticData: null, pinData: {},
  versionId: 'mr60-fixture-v1', triggerCount: 0, tags: []
};
process.stdout.write(JSON.stringify(workflow, null, 2));
NODE

compose run --rm n8n-import import:workflow \
  --input=/workflows/meeting/MR-60-Evidence-Gap-Cross-Search.json --activeState=false >/dev/null
compose run --rm n8n-import import:workflow \
  --input=/runtime/workflows/MR-60-Fixture-Runner.json --activeState=false >/dev/null
compose run --rm n8n-import publish:workflow --id=MR60EvidenceGapCrossSearch >/dev/null
published=true

if ! compose run --rm n8n-import execute --id=MR60FixtureRunner --rawOutput > "$execution_log" 2>&1; then
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
const items = execution.data.resultData.runData['Call MR-60'][0].data.main[0];
const packets = new Map(items.map(item => [item.json.requestId, item.json]));
const pass = packets.get('fixture-pass');
const reject = packets.get('fixture-reject');
const http503 = packets.get('fixture-http-503');
if (!pass || !reject || !http503) throw new Error('MISSING_FIXTURE_OUTPUTS');
if (pass.status !== 'VERIFIED' || pass.reasoningPacket.verifiedFacts[0]?.value !== '12' ||
    pass.audit.attempts !== 2 || pass.audit.reviewCalls !== 2) {
  throw new Error('CORRECTED_CLAIM_NOT_VERIFIED');
}
if (reject.status !== 'UNVERIFIED' || reject.reasoningPacket.verifiedFacts.length !== 0 ||
    reject.audit.attempts !== 3 || reject.audit.reviewCalls !== 3) {
  throw new Error('BOUNDED_REJECTION_NOT_ENFORCED');
}
if (http503.status !== 'UNVERIFIED' || http503.reasoningPacket.verifiedFacts.length !== 0 ||
    http503.audit.attempts !== 1 || http503.audit.reviewCalls !== 0 ||
    !http503.reasoningPacket.limitations.includes('fixture_http_503: BRIDGE_HTTP_503')) {
  throw new Error('HTTP_503_DID_NOT_FAIL_CLOSED');
}
console.log('n8n MR-60 fixture smoke passed: verified, bounded-unverified, and HTTP 503 fail-closed outputs observed');
NODE

compose run --rm n8n-import unpublish:workflow --id=MR60EvidenceGapCrossSearch >/dev/null
published=false
state=$(compose exec -T postgres sh -lc \
  'PGPASSWORD="$N8N_DB_PASSWORD" psql -U "$N8N_DB_USER" -d "$N8N_DB_NAME" -Atc "select active from workflow_entity where id = '\''MR60EvidenceGapCrossSearch'\''"')
[ "$state" = f ] || { echo '[error] MR-60 did not return to inactive state' >&2; exit 1; }
