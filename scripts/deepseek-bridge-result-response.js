'use strict';

const { publicBridgeErrorMessage } = require('./deepseek-public-error');

const UNPROVEN_COMPLETION_REASONS = new Set([
  'quick_budget_reached', 'signal_budget_reached',
  'timeout_with_last_state', 'timeout_incomplete_json'
]);

function bridgeResultResponse(result, ledgerOutcome = null) {
  const contentStatus = String(result && result.contentStatus || '').trim().toLowerCase();
  const completionReason = String(result && result.completionReason || '').trim().toLowerCase();
  const answerText = String(result && result.answerText || '').trim();
  const completionUnproven = contentStatus === 'incomplete'
    || UNPROVEN_COMPLETION_REASONS.has(completionReason)
    || (ledgerOutcome && ledgerOutcome.error === 'provider_response_incomplete');
  const verified = contentStatus === 'success' && !!answerText && !completionUnproven
    && (!ledgerOutcome || ledgerOutcome.status === 'SUCCESS');
  if (verified) return { statusCode: 200, payload: { ok: true, result } };

  const error = completionUnproven ? 'incomplete_generation'
    : contentStatus === 'search_evidence_missing' ? 'search_evidence_missing'
      : !answerText ? 'empty_answer' : 'provider_response_unverified';
  return {
    statusCode: completionUnproven ? 504 : 502,
    payload: {
      ok: false,
      error,
      message: publicBridgeErrorMessage(error),
      contentStatus: contentStatus || null,
      completionReason: completionReason || null,
      ...(ledgerOutcome && ledgerOutcome.status ? { ledgerStatus: ledgerOutcome.status } : {}),
      retrySafe: false
    }
  };
}

module.exports = { bridgeResultResponse };
