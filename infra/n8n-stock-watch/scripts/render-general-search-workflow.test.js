'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildWorkflow } = require('./render-general-search-workflow');

test('every bridge call catches transport failures and preserves HTTP errors for domain review', () => {
  const workflow = buildWorkflow();
  assert.equal(workflow.id, 'GEN10GeneralEvidenceSearch');
  const calls = workflow.nodes.filter(node => node.type === 'n8n-nodes-base.httpRequest');
  assert.equal(calls.length, 9);
  for (const call of calls) {
    assert.equal(call.onError, 'continueRegularOutput');
    assert.equal(call.parameters.options.response.response.neverError, true);
    assert.match(call.parameters.jsonBody, /queueTimeoutMs: 90000/);
    assert.match(call.parameters.jsonBody, /requireSearchEvidence: true/);
    assert.equal(call.parameters.options.timeout,
      '={{ Math.max(1000, Math.min($json.httpTimeoutMs, $json.deadlineAt - Date.now() - 1000)) }}');
  }
});

test('meeting webhook requires header auth and strips caller controlled bridge endpoints', () => {
  const workflow = buildWorkflow();
  const webhook = workflow.nodes.find(node => node.name === 'Meeting Evidence Webhook');
  const normalize = workflow.nodes.find(node => node.name === 'Normalize Request');
  assert.equal(webhook.parameters.authentication, 'headerAuth');
  assert.equal(webhook.parameters.responseMode, 'lastNode');
  assert.equal(webhook.credentials.httpHeaderAuth.id, 'MeetingEvidenceWebhookHeaderAuth');
  assert.match(normalize.parameters.jsCode, /requestId: incoming\.body\.requestId/);
  assert.doesNotMatch(normalize.parameters.jsCode, /bridgeEndpoint: incoming\.body/);
  assert.equal(workflow.connections['Meeting Evidence Webhook'].main[0][0].node, 'Normalize Request');
});
