#!/usr/bin/env node
'use strict';

const fs = require('fs');
const https = require('https');
const path = require('path');

const root = path.resolve(__dirname, '..');
const symbol = process.argv[2] || '300750';
const market = (process.argv[3] || inferMarket(symbol)).toLowerCase();
const configuredOutput = process.argv[4] || process.env.PHASE1_REAL_QUOTE_OUTPUT;
const outputPath = configuredOutput
  ? (path.isAbsolute(configuredOutput) ? configuredOutput : path.join(root, configuredOutput))
  : path.join(root, 'runtime', 'quotes', `quote-real-${symbol}-${market}.json`);

if (!/^\d{6}$/.test(symbol)) {
  throw new Error(`symbol must contain exactly six digits: ${symbol}`);
}
if (!['sh', 'sz'].includes(market)) {
  throw new Error(`market must be sh or sz: ${market}`);
}

const marketId = market === 'sh' ? '1' : '0';
const fields = [
  'f2', 'f3', 'f4', 'f5', 'f6', 'f8', 'f10', 'f12', 'f13', 'f14',
  'f15', 'f16', 'f17', 'f18', 'f62', 'f124', 'f184'
].join(',');
const query = new URLSearchParams({
  fltt: '2',
  secids: `${marketId}.${symbol}`,
  fields,
  ut: 'bd1d9ddb04089700cf9c27f6f7426281'
});
const endpointHosts = ['push2delay.eastmoney.com', 'push2.eastmoney.com'];

function inferMarket(value) {
  return /^6/.test(value) ? 'sh' : 'sz';
}

function requireFinite(name, value) {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Eastmoney field ${name} must be a finite number`);
  }
  return value;
}

function chinaClockParts(date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    weekday: values.weekday,
    minuteOfDay: Number(values.hour) * 60 + Number(values.minute)
  };
}

function marketSessionState(date) {
  const { weekday, minuteOfDay } = chinaClockParts(date);
  if (weekday === 'Sat' || weekday === 'Sun') return 'CLOSED';
  if (minuteOfDay >= 570 && minuteOfDay <= 690) return 'OPEN';
  if (minuteOfDay > 690 && minuteOfDay < 780) return 'MIDDAY_BREAK';
  if (minuteOfDay >= 780 && minuteOfDay <= 900) return 'OPEN';
  return 'CLOSED';
}

function getJson(url) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, {
      headers: {
        Accept: 'application/json,text/plain,*/*',
        Referer: 'https://quote.eastmoney.com/',
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36'
      },
      timeout: 10000
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        if (response.statusCode < 200 || response.statusCode >= 300) {
          reject(new Error(`${url.hostname} returned HTTP ${response.statusCode}: ${body.slice(0, 200)}`));
          return;
        }
        try {
          resolve(JSON.parse(body));
        } catch (error) {
          reject(new Error(`${url.hostname} returned invalid JSON: ${error.message}`));
        }
      });
    });
    request.on('timeout', () => request.destroy(new Error(`${url.hostname} timed out`)));
    request.on('error', reject);
  });
}

async function fetchQuote() {
  const failures = [];
  for (const host of endpointHosts) {
    const url = new URL(`https://${host}/api/qt/ulist.np/get?${query.toString()}`);
    try {
      const response = await getJson(url);
      const quote = response && response.data && Array.isArray(response.data.diff)
        ? response.data.diff[0]
        : null;
      if (!quote || response.rc !== 0) {
        throw new Error(`unexpected response rc=${response && response.rc}`);
      }
      return { quote, sourceUrl: url.toString() };
    } catch (error) {
      failures.push(error.message);
    }
  }
  throw new Error(`all Eastmoney endpoints failed: ${failures.join(' | ')}`);
}

async function main() {
  const { quote, sourceUrl } = await fetchQuote();
  if (String(quote.f12) !== symbol || Number(quote.f13) !== Number(marketId)) {
    throw new Error(`Eastmoney identity mismatch: expected ${marketId}.${symbol}, got ${quote.f13}.${quote.f12}`);
  }

  const quoteEpochSeconds = requireFinite('f124', quote.f124);
  const quoteTime = new Date(quoteEpochSeconds * 1000);
  if (!Number.isFinite(quoteTime.getTime())) throw new Error('Eastmoney f124 is not a valid timestamp');

  const fetchedAt = new Date();
  const previousClose = requireFinite('f18', quote.f18);
  const normalized = {
    fixture_name: `eastmoney-real-${symbol}-${market}`,
    source_kind: 'eastmoney_real',
    symbol,
    market,
    exchange_symbol: `${symbol}.${market}`,
    currency: 'CNY',
    stock_name: String(quote.f14 || ''),
    last_price: requireFinite('f2', quote.f2),
    previous_close: previousClose,
    open: requireFinite('f17', quote.f17),
    high: requireFinite('f15', quote.f15),
    low: requireFinite('f16', quote.f16),
    volume: requireFinite('f5', quote.f5) * 100,
    amount: null,
    main_inflow: null,
    quote_time: quoteTime.toISOString(),
    fetched_at: fetchedAt.toISOString(),
    market_session_state: marketSessionState(fetchedAt),
    market_session_basis: 'Asia/Shanghai weekday clock; exchange holiday calendar not consulted',
    previous_snapshot: {
      last_price: previousClose,
      quote_time: new Date(quoteTime.getTime() - 60000).toISOString(),
      source: 'previous_close_proxy'
    },
    field_semantics: {
      amount: 'unknown',
      main_inflow: 'unknown',
      volume: 'eastmoney_f5_hands_converted_to_shares'
    },
    source_url: sourceUrl,
    raw_eastmoney: {
      f2_last_price: quote.f2,
      f3_change_pct: quote.f3,
      f4_change_amount: quote.f4,
      f5_volume_hands: quote.f5,
      f6_amount_raw: quote.f6,
      f8_turnover_rate: quote.f8,
      f10_volume_ratio: quote.f10,
      f12_symbol: quote.f12,
      f13_market_id: quote.f13,
      f14_name: quote.f14,
      f15_high: quote.f15,
      f16_low: quote.f16,
      f17_open: quote.f17,
      f18_previous_close: quote.f18,
      f62_main_inflow_raw: quote.f62,
      f124_quote_epoch_seconds: quote.f124,
      f184_main_inflow_pct_raw: quote.f184
    }
  };

  if (!normalized.stock_name) throw new Error('Eastmoney stock name is empty');
  if (normalized.high < normalized.low) throw new Error('Eastmoney high is below low');
  if (normalized.last_price < normalized.low || normalized.last_price > normalized.high) {
    throw new Error('Eastmoney last price is outside day range');
  }

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.writeFileSync(outputPath, `${JSON.stringify(normalized, null, 2)}\n`);

  console.log(`quote_output=${outputPath}`);
  console.log(`symbol=${normalized.exchange_symbol}`);
  console.log(`stock_name=${normalized.stock_name}`);
  console.log(`quote_time=${normalized.quote_time}`);
  console.log(`fetched_at=${normalized.fetched_at}`);
  console.log(`market_session_state=${normalized.market_session_state}`);
  console.log(`source_host=${new URL(normalized.source_url).hostname}`);
}

main().catch((error) => {
  console.error(`[error] ${error.message}`);
  process.exitCode = 1;
});
