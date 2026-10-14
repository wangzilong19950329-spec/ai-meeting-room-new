#!/usr/bin/env node
'use strict';

const http = require('http');
const apiKey = process.env.DEEPSEEK_FACADE_API_KEY;
if (!apiKey) throw new Error('DEEPSEEK_FACADE_API_KEY_REQUIRED');
const counts = new Map();
const port = Number(process.env.GENERAL_SEARCH_FIXTURE_PORT || 8794);

function send(res, code, payload) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(payload));
}

function bridgeAnswer(answer, url) {
  return { ok: true, result: {
    answerText: JSON.stringify(answer), contentStatus: 'success',
    answerSource: 'new_assistant_block', jsonComplete: true,
    modeState: { searchEnabled: true }, isolatedSession: true,
    submittedTurnMatched: true,
    searchCount: 2, links: [{ href: url }]
  } };
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') return send(res, 200, { ok: true });
  if (req.method !== 'POST' || req.url !== '/search' ||
      req.headers.authorization !== `Bearer ${apiKey}`) return send(res, 401, { ok: false });
  let raw = '';
  req.on('data', chunk => { raw += chunk; });
  req.on('end', () => {
    let body;
    try { body = JSON.parse(raw); } catch (_) { return send(res, 400, { ok: false }); }
    const query = String(body.query || '');
    if (query.includes('fixture_http_503')) return send(res, 503, { ok: false, error: 'login_required' });
    if (query.includes('fixture_timeout')) {
      setTimeout(() => send(res, 200, { ok: false }), 2200);
      return;
    }
    const isReview = query.startsWith('请独立联网搜索、找反证');
    const isRetry = query.includes('fixture_retry');
    const isReviewProofRetry = query.includes('fixture_review_proof_retry');
    const isSearchProofRetry = query.includes('fixture_search_proof_retry');
    const isCoverageGap = query.includes('fixture_coverage_gap');
    const isUncertain = query.includes('fixture_uncertain');
    const isContradicted = query.includes('fixture_contradicted');
    const key = `${isRetry ? 'retry' : isReviewProofRetry ? 'review-proof' :
      isSearchProofRetry ? 'search-proof' : isCoverageGap ? 'coverage' :
      'pass'}:${isReview ? 'review' : 'search'}`;
    const count = (counts.get(key) || 0) + 1;
    counts.set(key, count);
    const wrong = isRetry && count === 1;
    const url = isReview ? 'https://another.invalid/official' : 'https://example.invalid/official';
    const secondCoverage = isCoverageGap && count > 1;
    const claim = { id: secondCoverage ? 'global_usage' : 'token_usage',
      statement: secondCoverage ? '全球月处理量为 20 万亿 Token' :
        wrong ? '某平台月处理量为 100 万亿 Token' : '某平台月处理量为 10 万亿 Token',
      scope: secondCoverage ? '全球' : '某平台', period: '2026-05',
      value: secondCoverage ? '20' : wrong ? '100' : '10',
      unit: '万亿 Token/月', qualification: '平台自身披露', sourceUrls: [url] };
    if (!isReview && isSearchProofRetry && count === 1) {
      const unproven = bridgeAnswer({ status: 'FOUND', claims: [claim] }, url);
      unproven.result.submittedTurnMatched = false;
      unproven.result.searchCount = null;
      unproven.result.links = [];
      return send(res, 200, unproven);
    }
    if (isReview && isReviewProofRetry && count === 1) {
      const unproven = bridgeAnswer({ coverage: 'COMPLETE', coverageReason: '',
        verdicts: [{ id: claim.id, verdict: 'PASS', observed: claim }] }, url);
      unproven.result.submittedTurnMatched = false;
      unproven.result.searchCount = null;
      unproven.result.links = [];
      return send(res, 200, unproven);
    }
    if (isReview && wrong) return send(res, 200, bridgeAnswer({ coverage: 'PARTIAL', coverageReason: '数值不符', verdicts: [{
      id: 'token_usage', verdict: 'REJECT', observed: null,
      reason: '原始披露为 10，不是 100', missingFields: ['value'],
      requestedSource: '平台原始披露'
    }] }, url));
    if (isReview && isUncertain) return send(res, 200, bridgeAnswer({
      coverage: 'UNKNOWN', coverageReason: '没有独立同口径资料',
      verdicts: [{ id: claim.id, verdict: 'UNKNOWN', observed: null,
        reason: '独立搜索未找到同口径数字' }]
    }, url));
    if (isReview && isContradicted) return send(res, 200, bridgeAnswer({
      coverage: 'UNKNOWN', coverageReason: '数字与独立来源矛盾',
      verdicts: [{ id: claim.id, verdict: 'REJECT', observed: null,
        reason: '独立搜索数字不一致' }]
    }, url));
    if (isReview && isCoverageGap && count === 1) return send(res, 200, bridgeAnswer({
      coverage: 'PARTIAL', coverageReason: '缺少全球总量',
      verdicts: [{ id: claim.id, verdict: 'PASS', observed: claim,
        reason: '平台样本准确但不足以回答全球总量' }]
    }, url));
    const answer = isReview
      ? { coverage: 'COMPLETE', coverageReason: '',
        verdicts: [{ id: claim.id, verdict: 'PASS', observed: claim, reason: '独立搜索一致' }] }
      : { status: 'FOUND', claims: [claim], reason: 'fixture source' };
    return send(res, 200, bridgeAnswer(answer, url));
  });
});

server.listen(port, '0.0.0.0', () => console.log(`general search fixture on ${port}`));
