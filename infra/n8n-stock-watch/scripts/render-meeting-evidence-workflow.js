#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const domainSource = fs.readFileSync(path.join(__dirname, 'meeting-evidence-domain.js'), 'utf8')
  .replace(/^'use strict';\s*/, '')
  .replace(/\nmodule\.exports = \{[\s\S]*?\};\s*$/, '');

function code(name, body, x, y) {
  return { id: name.replace(/[^A-Za-z0-9]/g, ''), name,
    type: 'n8n-nodes-base.code', typeVersion: 2, position: [x, y],
    parameters: { mode: 'runOnceForAllItems', language: 'javaScript',
      jsCode: `${domainSource}\n${body.trim()}` } };
}

function branch(name, field, x, y) {
  return { id: name.replace(/[^A-Za-z0-9]/g, ''), name,
    type: 'n8n-nodes-base.if', typeVersion: 2, position: [x, y],
    parameters: { conditions: { options: { caseSensitive: true, leftValue: '',
      typeValidation: 'strict', version: 2 }, combinator: 'and', conditions: [{
      id: name.replace(/[^A-Za-z0-9]/g, ''),
      leftValue: `={{ $json.${field} }}`, rightValue: true,
      operator: { type: 'boolean', operation: 'true', singleValue: true }
    }] }, options: {} } };
}

function call(name, queryField, x, y) {
  return { id: name.replace(/[^A-Za-z0-9]/g, ''), name,
    type: 'n8n-nodes-base.httpRequest', typeVersion: 4.3, position: [x, y],
    parameters: {
      method: 'POST', url: '={{ $json.bridgeEndpoint }}',
      authentication: 'genericCredentialType', genericAuthType: 'httpHeaderAuth',
      sendBody: true, specifyBody: 'json',
      // The single-account bridge waits at least 45 seconds after a completed request.
      // Review must be allowed to wait through that cooldown without repeating search.
      jsonBody: `={{ JSON.stringify({ query: $json.${queryField}, mode: 'expert', expectedJson: true, requireSearchEvidence: true, capability: 'general.analysis', purpose: 'meeting.evidence-gap', isolatedSession: true, forceNewSession: true, closeTargetAfter: true, queueTimeoutMs: 90000 }) }}`,
      options: { timeout: '={{ Math.max(1000, Math.min(240000, $json.deadlineAt - Date.now())) }}',
        response: { response: { responseFormat: 'json', fullResponse: true, neverError: true } } }
    },
    credentials: { httpHeaderAuth: { id: 'SWDeepSeekFacadeHeaderAuth', name: 'SW DeepSeek Facade Header' } }
  };
}

function connect(connections, from, to, output = 0) {
  if (!connections[from]) connections[from] = { main: [] };
  while (connections[from].main.length <= output) connections[from].main.push([]);
  connections[from].main[output].push({ node: to, type: 'main', index: 0 });
}

function buildWorkflow() {
  const nodes = [{ id: 'MeetingEvidenceInput', name: 'Meeting Evidence Input',
    type: 'n8n-nodes-base.executeWorkflowTrigger', typeVersion: 1.2,
    position: [0, 0], parameters: { inputSource: 'passthrough' } },
  code('Normalize Gap', 'return [{ json: normalizeRequest($input.first().json) }];', 220, 0)];
  const connections = {};
  connect(connections, 'Meeting Evidence Input', 'Normalize Gap');
  let previous = 'Normalize Gap';
  for (let attempt = 1; attempt <= 3; attempt++) {
    const x = 500 + (attempt - 1) * 1540;
    const prepareSearch = `Prepare Search ${attempt}`;
    const searchGate = `Search Budget ${attempt}`;
    const callSearch = `Call Search ${attempt}`;
    const prepareReview = `Prepare Review ${attempt}`;
    const reviewGate = `Review Gate ${attempt}`;
    const callReview = `Call Review ${attempt}`;
    const applyReview = `Apply Review ${attempt}`;
    const retryGate = `Retry Gap ${attempt}`;
    nodes.push(code(prepareSearch,
      'return [{ json: prepareSearch($input.first().json) }];', x, 0));
    nodes.push(branch(searchGate, 'canSearch', x + 200, 0));
    nodes.push(call(callSearch, 'searchQuery', x + 400, 0));
    nodes.push(code(prepareReview,
      `const prior = $('${prepareSearch}').first().json;\nreturn [{ json: prepareReview(parseSearch(prior, $input.first().json)) }];`, x + 600, 0));
    nodes.push(branch(reviewGate, 'canReview', x + 800, 0));
    nodes.push(call(callReview, 'reviewQuery', x + 1000, 0));
    nodes.push(code(applyReview,
      `const prior = $('${prepareReview}').first().json;\nreturn [{ json: applyReview(prior, $input.first().json) }];`, x + 1200, 0));
    nodes.push(branch(retryGate, 'statusIsPending', x + 1400, 0));
    // Keep the IF condition deterministic: statusIsPending is computed here.
    nodes[nodes.length - 1].parameters.conditions.conditions[0].leftValue = "={{ $json.status === 'PENDING' }}";
    connect(connections, previous, prepareSearch);
    connect(connections, prepareSearch, searchGate);
    connect(connections, searchGate, callSearch, 0);
    connect(connections, searchGate, 'Final Evidence Packet', 1);
    connect(connections, callSearch, prepareReview);
    connect(connections, prepareReview, reviewGate);
    connect(connections, reviewGate, callReview, 0);
    connect(connections, reviewGate, 'Final Evidence Packet', 1);
    connect(connections, callReview, applyReview);
    connect(connections, applyReview, retryGate);
    if (attempt === 3) {
      connect(connections, retryGate, 'Final Evidence Packet', 0);
    } else {
      previous = retryGate;
    }
    connect(connections, retryGate, 'Final Evidence Packet', 1);
  }
  nodes.push(code('Final Evidence Packet', 'return [{ json: finalPacket($input.first().json) }];', 5200, 0));
  return { id: 'MR60EvidenceGapCrossSearch', name: 'MR-60 Evidence Gap Cross-Search',
    active: false, nodes, connections, settings: { executionOrder: 'v1' },
    staticData: null, pinData: {}, versionId: 'mr60-evidence-gap-v1', triggerCount: 0, tags: [] };
}

const output = path.join(root, 'workflows', 'meeting', 'MR-60-Evidence-Gap-Cross-Search.json');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(buildWorkflow(), null, 2) + '\n');
console.log(output);

module.exports = { buildWorkflow };
