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
      metadata,
      createdAt: now,
      updatedAt: now
    };
    this.entries[submissionKey] = entry;
    this.persist();
    return { status: 'IN_FLIGHT', entry, created: true };
  }

  complete(submissionKey, requestDigest, result) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    existing.status = 'SUCCESS';
    existing.result = result;
    existing.error = null;
    existing.updatedAt = new Date().toISOString();
    this.persist();
    return existing;
  }

  markUnknown(submissionKey, requestDigest, error) {
    const existing = this.requireMatching(submissionKey, requestDigest);
    existing.status = 'UNKNOWN';
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
    fs.writeFileSync(temporaryPath, JSON.stringify(this.entries, null, 2));
    fs.renameSync(temporaryPath, this.filePath);
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
