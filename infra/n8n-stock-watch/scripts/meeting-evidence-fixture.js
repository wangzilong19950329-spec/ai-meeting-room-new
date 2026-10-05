#!/usr/bin/env node
'use strict';

const http = require('http');
const port = Number(process.env.MEETING_EVIDENCE_FIXTURE_PORT || 8794);
const apiKey = process.env.DEEPSEEK_FACADE_API_KEY;
if (!apiKey) throw new Error('DEEPSEEK_FACADE_API_KEY_REQUIRED');
const counts = new Map();

function result(answer, url) {
  return { ok: true, result: {
    answerText: JSON.stringify(answer), contentStatus: 'success',
    answerSource: 'new_assistant_block', jsonComplete: true,
    modeState: { bridgeMode: 'expert', modeVerified: true, searchEnabled: true },
    isolatedSession: true, searchCount: 2, links: [{ href: url }]
  } };
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }
  if (req.method !== 'POST' || req.url !== '/search' ||
      req.headers.authorization !== `Bearer ${apiKey}`) {
    res.writeHead(401); res.end(); return;
  }
  let raw = '';
  req.on('data', chunk => { raw += chunk; });
  req.on('end', () => {
    let body;
    try { body = JSON.parse(raw); } catch (_) { res.writeHead(400); res.end(); return; }
    const query = String(body.query || '');
    const review = query.startsWith('请开启全新会话');
    const failure = query.includes('fixture_reject');
    const key = `${failure ? 'reject' : 'pass'}:${review ? 'review' : 'search'}`;
    const count = (counts.get(key) || 0) + 1;
    counts.set(key, count);
    const wrong = count === 1 || failure;
    const url = wrong ? 'https://example.invalid/fictional-delivery' :
      'https://example.invalid/fictional-framework';
    const claim = { subject: '300750', eventDate: '2026-09-20',
      metric: '储能项目规模', value: wrong ? '120' : '12', unit: 'GWh',
      qualification: wrong ? '已交付' : '框架协议', sourceUrls: [url] };
    const answer = review
      ? (wrong ? { verdict: 'REJECT', observed: null, reason: '只有框架协议，没有已交付 120 GWh 的依据' }
        : { verdict: 'PASS', observed: claim, reason: '独立搜索一致' })
      : { status: 'FOUND', claim, reason: '测试资料' };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(result(answer, url)));
  });
});

server.listen(port, '0.0.0.0', () => console.log(`meeting evidence fixture on ${port}`));
