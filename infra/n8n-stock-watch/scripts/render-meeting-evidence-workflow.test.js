'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildWorkflow } = require('./render-meeting-evidence-workflow');

test('all search and review calls can queue beyond the bridge cooldown', () => {
  const calls = buildWorkflow().nodes.filter(node => node.type === 'n8n-nodes-base.httpRequest');
  assert.equal(calls.length, 6);
  for (const call of calls) {
    const match = call.parameters.jsonBody.match(/queueTimeoutMs:\s*(\d+)/);
    assert.ok(match, `${call.name} has no queue timeout`);
    assert.ok(Number(match[1]) > 45_000, `${call.name} queues for less than the bridge cooldown`);
    assert.match(call.parameters.jsonBody, /requireSearchEvidence: true/);
    assert.equal(call.parameters.options.response.response.neverError, true);
  }
});
