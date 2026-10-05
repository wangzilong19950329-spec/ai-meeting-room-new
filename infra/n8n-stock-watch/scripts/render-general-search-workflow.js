#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const domainSource = fs.readFileSync(path.join(__dirname, 'general-search-domain.js'), 'utf8')
  .replace(/^'use strict';\s*/, '')
  .replace(/\nmodule\.exports = \{[\s\S]*?\};\s*$/, '');

function code(name, body, x, y) {
  return { id: name.replace(/[^A-Za-z0-9]/g, ''), name,
    type: 'n8n-nodes-base.code', typeVersion: 2, position: [x, y],
    parameters: { mode: 'runOnceForAllItems', language: 'javaScript',
      jsCode: `${domainSource}\n${body.trim()}` } };
}

function branch(name, expression, x, y) {
  return { id: name.replace(/[^A-Za-z0-9]/g, ''), name,
    type: 'n8n-nodes-base.if', typeVersion: 2, position: [x, y],
    parameters: { conditions: { options: { caseSensitive: true, leftValue: '',
      typeValidation: 'strict', version: 2 }, combinator: 'and', conditions: [{
      id: name.replace(/[^A-Za-z0-9]/g, ''), leftValue: `={{ ${expression} }}`,
      rightValue: true, operator: { type: 'boolean', operation: 'true', singleValue: true }
    }] }, options: {} } };
}

function call(name, queryField, x, y) {
  return { id: name.replace(/[^A-Za-z0-9]/g, ''), name,
    type: 'n8n-nodes-base.httpRequest', typeVersion: 4.3, position: [x, y],
    onError: 'continueRegularOutput',
    parameters: {
      method: 'POST', url: '={{ $json.bridgeEndpoint }}',
      authentication: 'genericCredentialType', genericAuthType: 'httpHeaderAuth',
      sendBody: true, specifyBody: 'json',
      jsonBody: `={{ JSON.stringify({ query: $json.${queryField}, mode: 'expert', expectedJson: true, capability: 'general.analysis', purpose: 'general.evidence-search', isolatedSession: true, forceNewSession: true, closeTargetAfter: true, queueTimeoutMs: 90000 }) }}`,
      options: {
        timeout: '={{ $json.requestTimeoutMs }}',
        response: { response: { responseFormat: 'json', fullResponse: true, neverError: true } }
      }
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
  const nodes = [
    { id: 'GeneralSearchInput', name: 'General Search Input',
      type: 'n8n-nodes-base.executeWorkflowTrigger', typeVersion: 1.2,
      position: [0, 0], parameters: { inputSource: 'passthrough' } },
    code('Normalize Request', 'return [{ json: normalizeRequest($input.first().json) }];', 220, 0)
  ];
  const connections = {};
  connect(connections, 'General Search Input', 'Normalize Request');
  let previous = 'Normalize Request';
  for (let attempt = 1; attempt <= 3; attempt++) {
    const x = 480 + (attempt - 1) * 1540;
    const prepareSearch = `Prepare Search ${attempt}`;
    const searchGate = `Search Budget ${attempt}`;
    const callSearch = `Call Search ${attempt}`;
    const prepareReview = `Prepare Review ${attempt}`;
    const reviewGate = `Review Budget ${attempt}`;
    const searchProofRetryGate = `Search Proof Retry ${attempt}`;
    const callReview = `Call Review ${attempt}`;
    const applyReview = `Apply Review ${attempt}`;
    const proofRetryGate = `Review Proof Retry ${attempt}`;
    const prepareProofRetry = `Prepare Review Proof Retry ${attempt}`;
    const callProofRetry = `Call Review Proof Retry ${attempt}`;
    const applyProofRetry = `Apply Review Proof Retry ${attempt}`;
    const retryGate = `Retry Rejected Claims ${attempt}`;
    nodes.push(code(prepareSearch,
      'return [{ json: prepareSearch($input.first().json) }];', x, 0));
    nodes.push(branch(searchGate, '$json.canSearch === true', x + 200, 0));
    nodes.push(call(callSearch, 'searchQuery', x + 400, 0));
    nodes.push(code(prepareReview,
      `const prior = $('${prepareSearch}').first().json;\nreturn [{ json: prepareReview(parseSearch(prior, $input.first().json)) }];`, x + 600, 0));
    nodes.push(branch(reviewGate, '$json.canReview === true', x + 800, 0));
    nodes.push(branch(searchProofRetryGate, '$json.retrySearchProof === true', x + 1000, 240));
    nodes.push(call(callReview, 'reviewQuery', x + 1000, 0));
    nodes.push(code(applyReview,
      `const prior = $('${prepareReview}').first().json;\nreturn [{ json: applyReview(prior, $input.first().json) }];`, x + 1200, 0));
    nodes.push(branch(proofRetryGate, '$json.retryReviewProof === true', x + 1400, 0));
    nodes.push(code(prepareProofRetry,
      'return [{ json: prepareReview($input.first().json) }];', x + 1600, -220));
    nodes.push(call(callProofRetry, 'reviewQuery', x + 1800, -220));
    nodes.push(code(applyProofRetry,
      `const prior = $('${prepareProofRetry}').first().json;\nreturn [{ json: applyReview(prior, $input.first().json) }];`, x + 2000, -220));
    nodes.push(branch(retryGate, "$json.status === 'PENDING'", x + 1400, 0));
    connect(connections, previous, prepareSearch);
    connect(connections, prepareSearch, searchGate);
    connect(connections, searchGate, callSearch);
    connect(connections, searchGate, 'Final Search Packet', 1);
    connect(connections, callSearch, prepareReview);
    connect(connections, prepareReview, reviewGate);
    connect(connections, reviewGate, callReview);
    connect(connections, reviewGate, searchProofRetryGate, 1);
    connect(connections, searchProofRetryGate,
      attempt < 3 ? `Prepare Search ${attempt + 1}` : 'Final Search Packet');
    connect(connections, searchProofRetryGate, 'Final Search Packet', 1);
    connect(connections, callReview, applyReview);
    connect(connections, applyReview, proofRetryGate);
    connect(connections, proofRetryGate, prepareProofRetry);
    connect(connections, proofRetryGate, retryGate, 1);
    connect(connections, prepareProofRetry, callProofRetry);
    connect(connections, callProofRetry, applyProofRetry);
    connect(connections, applyProofRetry, retryGate);
    if (attempt === 3) connect(connections, retryGate, 'Final Search Packet');
    else previous = retryGate;
    connect(connections, retryGate, 'Final Search Packet', 1);
  }
  nodes.push(code('Final Search Packet',
    'return [{ json: finalPacket($input.first().json) }];', 5190, 0));
  return { id: 'GEN10GeneralEvidenceSearch', name: 'GEN-10 General Evidence Search',
    active: false, nodes, connections, settings: { executionOrder: 'v1' },
    staticData: null, pinData: {}, versionId: 'gen10-general-evidence-v1',
    triggerCount: 0, tags: [] };
}

const output = path.join(root, 'workflows', 'general', 'GEN-10-General-Evidence-Search.json');
fs.mkdirSync(path.dirname(output), { recursive: true });
fs.writeFileSync(output, JSON.stringify(buildWorkflow(), null, 2) + '\n');
console.log(output);

module.exports = { buildWorkflow };
