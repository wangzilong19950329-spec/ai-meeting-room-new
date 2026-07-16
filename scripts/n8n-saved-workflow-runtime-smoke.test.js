'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {
  findSavedWorkflowOutputContent
} = require('./n8n-saved-workflow-runtime-smoke');

test('n8n saved workflow output ignores prompt markers in inputOverride', () => {
  const runData = {
    'Basic LLM Chain': [
      {
        inputOverride: {
          ai_languageModel: [
            [
              {
                json: {
                  messages: [
                    'Human: Return JSON exactly like {"ok":true,"source":"n8n-saved-workflow-smoke"}.'
                  ]
                }
              }
            ]
          ]
        },
        data: {
          main: [[]]
        }
      }
    ]
  };

  assert.equal(findSavedWorkflowOutputContent(runData), '');
});

test('n8n saved workflow output accepts Basic LLM Chain JSON output', () => {
  const runData = {
    'Basic LLM Chain': [
      {
        data: {
          main: [
            [
              {
                json: {
                  ok: true,
                  source: 'n8n-saved-workflow-smoke'
                }
              }
            ]
          ]
        }
      }
    ]
  };

  assert.equal(
    findSavedWorkflowOutputContent(runData),
    '{"ok":true,"source":"n8n-saved-workflow-smoke"}'
  );
});
