'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { publicSearchResult } = require('./deepseek-public-result');

test('business results exclude physical account and browser topology', () => {
  const result = publicSearchResult({
    answerText: 'business answer',
    contentStatus: 'success',
    targetId: 'conversation-target',
    finalUrl: 'https://chat.deepseek.com/a/chat/s/private-example',
    accountKey: 'technical',
    debugPort: 9333,
    profileRoot: '/private/profile',
    brokerRouting: { accountKey: 'technical', attempt: 1 },
    futureInternalRoutingField: 'must-not-leak'
  });

  assert.deepEqual(result, {
    answerText: 'business answer',
    contentStatus: 'success'
  });
});
