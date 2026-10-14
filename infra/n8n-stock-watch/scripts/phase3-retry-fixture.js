#!/usr/bin/env node
'use strict';

const http = require('http');
const crypto = require('crypto');
const { RequestLedger } = require('../../../scripts/deepseek-request-ledger');
const { _internals: openAiAdapter } = require('../../../scripts/deepseek-openai-adapter');

const host = process.env.PHASE3_RETRY_FIXTURE_HOST || '0.0.0.0';
const port = Number(process.env.PHASE3_RETRY_FIXTURE_PORT || 8794);
const apiKey = process.env.DEEPSEEK_FACADE_API_KEY || '';
const ledgerPath = process.env.PHASE3_RETRY_LEDGER_PATH || '';
let attempts = 0;
const scenarioCounts = {
  safeRequests: 0,
  safeProviderInvocations: 0,
  unknownRequests: 0,
  unknownProviderInvocations: 0
};

if (!apiKey) {
  throw new Error('DEEPSEEK_FACADE_API_KEY is required for the retry fixture');
}
if (!ledgerPath) {
  throw new Error('PHASE3_RETRY_LEDGER_PATH is required for the retry fixture');
}
const ledger = new RequestLedger(ledgerPath, 10);

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

const acceptedOutput = {
  schema_version: 'stock-watch.market-behavior.v1',
  analysis_status: 'VALID',
  hypotheses: [{
    hypothesis_id: 'H_RETRY',
    type: 'NORMAL_EXECUTION',
    evidence_band: 'LOW',
    support_fact_refs: ['F_STOCK_RETURN_30M'],
    counter_fact_refs: [],
    alternatives: ['INSUFFICIENT_MICROSTRUCTURE_EVIDENCE'],
    next_confirmation_conditions: ['Observe another complete interval']
  }],
  material_changes: [],
  unexplained_fact_refs: [],
  limitations: ['BAR_DATA_ONLY']
};

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', chunk => {
      size += chunk.length;
      if (size > 1024 * 1024) {
        reject(new Error('request_too_large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch (error) {
        reject(error);
      }
    });
    req.on('error', reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    return sendJson(res, 200, { ok: true });
  }
  if (req.method === 'GET' && req.url === '/count') {
    return sendJson(res, 200, {
      attempts,
      scenarioCounts,
      entries: Object.values(ledger.entries).map(entry => ({
        submissionKey: entry.submissionKey,
        status: entry.status
      }))
    });
  }
  if (req.method !== 'POST' || req.url !== '/v1/chat/completions') {
    return sendJson(res, 404, { error: 'not_found' });
  }
  if (apiKey && req.headers.authorization !== `Bearer ${apiKey}`) {
    return sendJson(res, 401, { error: 'unauthorized' });
  }

  let body;
  try {
    body = await readJson(req);
  } catch (error) {
    return sendJson(res, 400, { error: 'invalid_json' });
  }
  const submissionKey = String(body && body.metadata && body.metadata.deepseek_submission_key || '').trim();
  if (!submissionKey) {
    return sendJson(res, 400, { error: 'submission_identity_required' });
  }
  const requestDigest = crypto.createHash('sha256')
    .update(JSON.stringify(body.messages || []), 'utf8')
    .digest('hex');
  const existing = ledger.lookup(submissionKey, requestDigest);
  if (existing.status !== 'NOT_FOUND') {
    return sendJson(res, 425, {
      error: 'provider_outcome_not_retriable',
      ledgerStatus: existing.status
    });
  }
  ledger.begin(submissionKey, requestDigest, { fixture: 'phase3-retry' });
  attempts += 1;
  const userMessage = (body.messages || []).find(message => message && message.role === 'user');
  let tradingPhase = '';
  try {
    tradingPhase = JSON.parse(userMessage && userMessage.content || '{}').trading_phase || '';
  } catch (_) {
    tradingPhase = '';
  }
  if (tradingPhase === '11:00') {
    scenarioCounts.unknownRequests += 1;
    scenarioCounts.unknownProviderInvocations += 1;
    ledger.markSubmitting(submissionKey, requestDigest, { fixturePhase: 'unknown_after_submit' });
    ledger.markSubmitted(submissionKey, requestDigest, { fixturePhase: 'unknown_after_submit' });
    ledger.markUnknown(submissionKey, requestDigest, 'provider outcome unknown after submit');
    return sendJson(res, 504, openAiAdapter.bridgeErrorToOpenAi(504, {
      error: 'provider_outcome_unknown',
      message: 'provider outcome unknown after submit',
      providerOutcomeUnknown: true,
      retrySafe: false
    }));
  }
  scenarioCounts.safeRequests += 1;
  if (scenarioCounts.safeRequests === 1) {
    ledger.markFailedPreSubmit(submissionKey, requestDigest, 'transient timeout');
    return sendJson(res, 504, openAiAdapter.bridgeErrorToOpenAi(504, {
      error: 'fixture_timeout',
      message: 'transient timeout',
      providerOutcomeUnknown: false,
      retrySafe: true
    }));
  }
  scenarioCounts.safeProviderInvocations += 1;
  ledger.complete(submissionKey, requestDigest, {
    answerText: JSON.stringify(acceptedOutput),
    contentStatus: 'complete'
  });
  return sendJson(res, 200, openAiAdapter.chatCompletionResponse({
    body,
    answerText: JSON.stringify(acceptedOutput),
    bridgeResult: { contentStatus: 'complete', completionReason: 'fixture' },
    created: Math.floor(Date.now() / 1000)
  }));
});

server.listen(port, host, () => {
  process.stdout.write(`phase3 retry fixture listening on ${host}:${port}\n`);
});
