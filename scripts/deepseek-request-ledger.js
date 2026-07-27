const fs = require('fs');
const path = require('path');

class RequestLedger {
  constructor(filePath, maxEntries = 500, maxTombstones = 100000) {
    this.filePath = filePath;
    this.maxEntries = Math.max(1, Number(maxEntries) || 500);
    this.maxTombstones = Math.max(this.maxEntries, Number(maxTombstones) || 100000);
    this.entries = this.load();
    this.recoverInterruptedRequests();
  }

  load() {
    if (!this.filePath || !fs.existsSync(this.filePath)) return {};
    const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'));
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  }

  recoverInterruptedRequests() {
    let changed = false;
    for (const entry of Object.values(this.entries)) {
      if (entry && entry.status === 'IN_FLIGHT') {
        entry.status = 'UNKNOWN';
        entry.error = 'bridge_restarted_with_provider_outcome_unknown';
        entry.updatedAt = new Date().toISOString();
        changed = true;
      }
    }
    if (changed) this.persist();
  }

  lookup(submissionKey, requestDigest) {
    const entry = this.entries[submissionKey];
    if (!entry) return { status: 'NOT_FOUND' };
    if (entry.requestDigest !== requestDigest) {
      return { status: 'DIGEST_MISMATCH', entry };
    }
    return { status: entry.status, entry };
  }

  begin(submissionKey, requestDigest, metadata = {}) {
    const existing = this.lookup(submissionKey, requestDigest);
    if (existing.status !== 'NOT_FOUND') return existing;
    const now = new Date().toISOString();
    const entry = {
      submissionKey,
      requestDigest,
      status: 'IN_FLIGHT',
      submitPhase: 'CLAIMED',
      submitReservationCount: 0,
      confirmedSubmitInvocationCount: 0,
      possibleSubmitUpperBound: 0,
      metadata,
      createdAt: now,
      updatedAt: now
    };
    this.entries[submissionKey] = entry;
    this.persist();
    return { status: 'IN_FLIGHT', entry, created: true };
  }

  markSubmitting(submissionKey, requestDigest, metadata = {}) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    if (existing.status !== 'IN_FLIGHT'
      || Number(existing.submitReservationCount || 0) !== 0) {
      throw new Error('request_ledger_submit_reservation_conflict');
    }
    existing.submitPhase = 'SUBMITTING';
    existing.submitReservationCount = 1;
    existing.possibleSubmitUpperBound = 1;
    existing.metadata = { ...(existing.metadata || {}), ...metadata };
    existing.updatedAt = new Date().toISOString();
    this.persist();
    return existing;
  }

  markSubmitted(submissionKey, requestDigest, metadata = {}) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    if (existing.status !== 'IN_FLIGHT'
      || Number(existing.submitReservationCount || 0) !== 1
      || Number(existing.confirmedSubmitInvocationCount || 0) !== 0) {
      throw new Error('request_ledger_submit_confirmation_conflict');
    }
    existing.submitPhase = 'SUBMITTED';
    existing.confirmedSubmitInvocationCount = 1;
    existing.metadata = { ...(existing.metadata || {}), ...metadata };
    existing.updatedAt = new Date().toISOString();
    this.persist();
    return existing;
  }

  complete(submissionKey, requestDigest, result) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    const contentStatus = String(result && result.contentStatus || '').trim().toLowerCase();
    const answerText = String(result && result.answerText || '').trim();
    if (contentStatus === 'incomplete' || !answerText) {
      return this.markInvalid(submissionKey, requestDigest,
        contentStatus === 'incomplete' ? 'provider_response_incomplete' : 'provider_response_empty');
    }
    existing.status = 'SUCCESS';
    existing.submitPhase = 'COMPLETED';
    existing.result = result;
    existing.error = null;
    existing.updatedAt = new Date().toISOString();
    this.persist();
    return existing;
  }

  markFailedPreSubmit(submissionKey, requestDigest, error) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    if (Number(existing.submitReservationCount || 0) !== 0) {
      throw new Error('request_ledger_pre_submit_failure_after_reservation');
    }
    existing.status = 'FAILED_PRE_SUBMIT';
    existing.submitPhase = 'FAILED_PRE_SUBMIT';
    existing.error = error || 'provider_not_submitted';
    existing.updatedAt = new Date().toISOString();
    this.persist();
    return existing;
  }

  markInvalid(submissionKey, requestDigest, error) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    existing.status = 'INVALID';
    existing.submitPhase = 'INVALID';
    existing.error = error || 'provider_response_invalid';
    existing.updatedAt = new Date().toISOString();
    this.persist();
    return existing;
  }

  markUnknown(submissionKey, requestDigest, error) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    existing.status = 'UNKNOWN';
    existing.submitPhase = 'UNKNOWN';
    existing.error = error || 'provider_outcome_unknown';
    existing.updatedAt = new Date().toISOString();
    this.persist();
    return existing;
  }

  requireMatching(submissionKey, requestDigest) {
    const lookup = this.lookup(submissionKey, requestDigest);
    if (lookup.status === 'NOT_FOUND' || lookup.status === 'DIGEST_MISMATCH') {
      throw new Error(`request_ledger_state_invalid:${lookup.status}`);
    }
    return lookup.entry;
  }

  persist() {
    const ordered = Object.values(this.entries)
      .sort((left, right) => String(right.updatedAt || '').localeCompare(String(left.updatedAt || '')));
    let retainedResults = 0;
    for (const entry of ordered) {
      if (entry && entry.status === 'SUCCESS' && entry.result) {
        retainedResults += 1;
        if (retainedResults > this.maxEntries) {
          entry.status = 'EVICTED_SUCCESS';
          entry.error = 'provider_succeeded_but_cached_result_evicted';
          delete entry.result;
          delete entry.metadata;
        }
      }
    }
    let retainedTombstones = 0;
    const retained = ordered.filter((entry) => {
      if (!entry || entry.status !== 'EVICTED_SUCCESS') return true;
      retainedTombstones += 1;
      return retainedTombstones <= this.maxTombstones;
    });
    this.entries = Object.fromEntries(retained.map((entry) => [entry.submissionKey, entry]));
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    const temporaryPath = `${this.filePath}.${process.pid}.tmp`;
    const fileDescriptor = fs.openSync(temporaryPath, 'w');
    try {
      fs.writeFileSync(fileDescriptor, JSON.stringify(this.entries, null, 2));
      fs.fsyncSync(fileDescriptor);
    } finally {
      fs.closeSync(fileDescriptor);
    }
    fs.renameSync(temporaryPath, this.filePath);
    const directoryDescriptor = fs.openSync(path.dirname(this.filePath), 'r');
    try {
      fs.fsyncSync(directoryDescriptor);
    } finally {
      fs.closeSync(directoryDescriptor);
    }
  }

  summary() {
    const counts = {};
    for (const entry of Object.values(this.entries)) {
      counts[entry.status] = (counts[entry.status] || 0) + 1;
    }
    return { filePath: this.filePath, entryCount: Object.keys(this.entries).length, counts };
  }
}

module.exports = { RequestLedger };
