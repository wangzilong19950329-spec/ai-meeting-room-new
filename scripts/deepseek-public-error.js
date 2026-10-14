'use strict';

const PUBLIC_BRIDGE_ERROR_MESSAGES = Object.freeze({
  account_override_forbidden: 'Physical account selection is not allowed for business requests.',
  account_override_capability_conflict: 'The requested analysis capability is unavailable.',
  session_affinity_capability_conflict: 'The logical session cannot provide the requested capability.',
  bridge_queue_timeout: 'Timed out waiting for analysis capacity.',
  bridge_invocation_failed: 'Analysis capacity is temporarily unavailable.',
  broker_upstream_unavailable: 'Analysis capacity is temporarily unavailable.',
  capability_unavailable: 'The requested analysis capability is unavailable.',
  all_workers_failed_pre_submit: 'Analysis capacity was unavailable before provider submission.',
  session_worker_unavailable: 'The logical session is temporarily unavailable.',
  duplicate_account_identity: 'Analysis capacity requires distinct verified provider identities.',
  account_identity_unverified: 'Analysis capacity identity could not be verified.',
  identity_lease_changed: 'Analysis capacity identity changed during request processing.',
  identity_lease_conflict: 'Analysis capacity identity is already in use.',
  provider_outcome_unknown: 'The provider outcome is unknown and must not be retried blindly.',
  provider_timeout: 'The analysis provider did not complete within the allowed time.',
  login_required: 'Analysis capacity requires provider login.',
  debugger_unavailable: 'Analysis capacity is temporarily unavailable.',
  submit_failed: 'The analysis request could not be submitted.',
  incomplete_generation: 'The analysis provider did not complete its response.',
  empty_answer: 'The analysis provider returned no answer.',
  search_evidence_missing: 'The current search response lacks attributable source evidence.',
  provider_response_unverified: 'The analysis provider response could not be verified.',
  search_failed: 'The analysis request failed.'
});

function publicBridgeErrorMessage(errorCode) {
  return PUBLIC_BRIDGE_ERROR_MESSAGES[String(errorCode || '')]
    || PUBLIC_BRIDGE_ERROR_MESSAGES.search_failed;
}

function publicLedgerErrorMessage(status) {
  if (String(status || '').toUpperCase() === 'DIGEST_MISMATCH') {
    return 'The submission key was already used for a different operation.';
  }
  return 'The provider outcome is not retryable; query status with the same submission identity.';
}

module.exports = {
  PUBLIC_BRIDGE_ERROR_MESSAGES,
  publicBridgeErrorMessage,
  publicLedgerErrorMessage
};
