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
    assert.equal(call.parameters.options.timeout, '={{ $json.requestTimeoutMs }}');
  }
});
