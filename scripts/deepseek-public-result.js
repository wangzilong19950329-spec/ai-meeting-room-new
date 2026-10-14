'use strict';

const PUBLIC_RESULT_FIELDS = Object.freeze([
  'searchCount',
  'searchToolCallCount',
  'openedPageCount',
  'searchEvidenceSource',
  'answerText',
  'links',
  'providerTimingMs',
  'modeState',
  'isolatedSession',
  'sessionKey',
  'contentStatus',
  'contentNote',
  'answerSource',
  'candidateBlockCount',
  'assistantBlockDeltaCount',
  'newLinkCount',
  'bodyDeltaLength',
  'waitDurationMs',
  'completionReason',
  'answerLength',
  'jsonComplete',
  'networkAnswerCaptured',
  'generationActive',
  'submittedTurnMatched',
  'allAssistantBlockCount',
  'targetReuseMode',
  'conversationRestored'
]);

function publicSearchResult(result) {
  const source = result && typeof result === 'object' ? result : {};
  const publicResult = {};
  for (const field of PUBLIC_RESULT_FIELDS) {
    if (Object.prototype.hasOwnProperty.call(source, field)) {
      publicResult[field] = source[field];
    }
  }
  return publicResult;
}

module.exports = { PUBLIC_RESULT_FIELDS, publicSearchResult };
