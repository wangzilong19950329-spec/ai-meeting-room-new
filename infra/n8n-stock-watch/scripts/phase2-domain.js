'use strict';

const ANALYSIS_SLOTS = new Set([
  '10:00', '10:30', '11:00', '11:30',
  '13:30', '14:00', '14:30', '15:00'
]);
const USABLE_QUOTE_STATUSES = new Set(['VALID', 'PARTIAL']);
const ALLOWED_ANALYSIS_STATUSES = new Set(['VALID', 'UNKNOWN', 'INSUFFICIENT']);
const ALLOWED_HYPOTHESES = new Set([
  'ACCUMULATION_LIKE',
  'DISTRIBUTION_LIKE',
  'NORMAL_EXECUTION',
  'INSUFFICIENT'
]);
const L0_FORBIDDEN_HYPOTHESES = new Set([
  'SPOOFING_LIKE',
  'WASH_TRADE',
  'ORDER_BOOK_LAYERING_LIKE'
]);
const EVIDENCE_BANDS = new Set(['LOW', 'MODERATE', 'HIGH', 'INSUFFICIENT']);

function chinaDateParts(value) {
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error('INVALID_TIME');
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    weekday: 'short',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23'
  });
  const parts = {};
  for (const part of formatter.formatToParts(date)) {
    if (part.type !== 'literal') parts[part.type] = part.value;
  }
  return {
    weekday: parts.weekday,
    date: `${parts.year}-${parts.month}-${parts.day}`,
    time: `${parts.hour}:${parts.minute}`,
    seconds: Number(parts.second)
  };
}

function tradingSessionState(value) {
  const parts = chinaDateParts(value);
  const weekday = !['Sat', 'Sun'].includes(parts.weekday);
  const morning = parts.time >= '09:30' && parts.time <= '11:30';
  const afternoon = parts.time >= '13:00' && parts.time <= '15:00';
  return {
    timezone: 'Asia/Shanghai',
    tradeDate: parts.date,
    chinaTime: parts.time,
    weekday,
    isTradingSession: weekday && (morning || afternoon),
    limitation: 'NO_AUTHORITATIVE_CN_HOLIDAY_CALENDAR'
  };
}

function analysisSlot(value) {
  const state = tradingSessionState(value);
  return {
    ...state,
    slot: state.weekday && ANALYSIS_SLOTS.has(state.chinaTime) ? state.chinaTime : null
  };
}

function finiteNumber(value) {
  if (value === null || value === undefined || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function normalizeJavaQuoteEnvelope(envelope) {
  if (!envelope || envelope.schemaVersion !== 'stock-watch.n8n.quote.v1') {
    throw new Error('QUOTE_SCHEMA_INVALID');
  }
  if (!Array.isArray(envelope.items) || envelope.items.length !== 1) {
    throw new Error(envelope && Array.isArray(envelope.items) && envelope.items.length === 0
      ? 'QUOTE_EMPTY'
      : 'QUOTE_CARDINALITY_INVALID');
  }
  const item = envelope.items[0];
  const quality = item.quality || {};
  if (!USABLE_QUOTE_STATUSES.has(quality.status)) {
    const codes = Array.isArray(quality.errors) ? quality.errors.join(',') : '';
    throw new Error(`QUOTE_REJECTED:${quality.status || 'UNKNOWN'}:${codes}`);
  }
  const pricing = item.pricing || {};
  const cumulative = item.cumulative || {};
  const price = finiteNumber(pricing.last);
  const quoteTime = Date.parse(item.quoteTime);
  const fetchedAt = Date.parse(item.fetchedAt || envelope.fetchedAt);
  if (price === null || price <= 0) throw new Error('NON_POSITIVE_PRICE');
  if (!Number.isFinite(quoteTime)) throw new Error('MISSING_QUOTE_TIME');
  if (!Number.isFinite(fetchedAt)) throw new Error('MISSING_FETCHED_AT');

  return {
    symbol: String(item.symbol || ''),
    market: String(item.market || '').toLowerCase(),
    provider: String(item.provider || envelope.provider || ''),
    source: String(item.source || ''),
    quote_time: new Date(quoteTime).toISOString(),
    fetched_at: new Date(fetchedAt).toISOString(),
    price,
    open: finiteNumber(pricing.open),
    day_high: finiteNumber(pricing.dayHigh),
    day_low: finiteNumber(pricing.dayLow),
    previous_close: finiteNumber(pricing.previousClose),
    cumulative_volume_raw: finiteNumber(cumulative.volumeRaw),
    cumulative_volume_shares: finiteNumber(cumulative.volumeShares),
    volume_unit: cumulative.volumeUnit || null,
    cumulative_amount_raw: finiteNumber(cumulative.amountRaw),
    amount_unit: cumulative.amountUnit || null,
    unit_contract_status: cumulative.unitContractStatus || 'UNKNOWN',
    quality_status: quality.status,
    quality_errors: Array.isArray(quality.errors) ? quality.errors : [],
    limitations: Array.isArray(quality.limitations) ? quality.limitations : [],
    raw_envelope: envelope
  };
}

function minuteStart(value) {
  const date = new Date(value);
  date.setUTCSeconds(0, 0);
  return date.toISOString();
}

function calculateSnapshotDelta(previous, current) {
  if (!current) throw new Error('CURRENT_SNAPSHOT_REQUIRED');
  const limitations = [...(current.limitations || [])];
  let volumeDeltaRaw = null;
  let volumeDeltaShares = null;
  let amountDelta = null;
  let duplicateTimestamp = false;

  if (!previous) {
    limitations.push('PREVIOUS_SNAPSHOT_MISSING');
  } else if (Date.parse(previous.quote_time) === Date.parse(current.quote_time)) {
    duplicateTimestamp = true;
    limitations.push('DUPLICATE_QUOTE_TIME');
  } else if (previous.provider !== current.provider) {
    limitations.push('PROVIDER_CHANGED');
  } else {
    if (previous.volume_unit !== current.volume_unit) {
      limitations.push('VOLUME_UNIT_CHANGED');
    } else if (previous.cumulative_volume_raw === null || current.cumulative_volume_raw === null) {
      limitations.push('VOLUME_DELTA_UNAVAILABLE');
    } else if (current.cumulative_volume_raw < previous.cumulative_volume_raw) {
      limitations.push(isMiddayBoundary(previous.quote_time, current.quote_time)
        ? 'MIDDAY_VOLUME_COUNTER_RESET'
        : 'VOLUME_COUNTER_RESET');
    } else {
      volumeDeltaRaw = current.cumulative_volume_raw - previous.cumulative_volume_raw;
      if (current.cumulative_volume_shares !== null && previous.cumulative_volume_shares !== null) {
        volumeDeltaShares = current.cumulative_volume_shares - previous.cumulative_volume_shares;
      }
    }

    if (previous.amount_unit !== current.amount_unit) {
      limitations.push('AMOUNT_UNIT_CHANGED');
    } else if (previous.cumulative_amount_raw === null || current.cumulative_amount_raw === null) {
      limitations.push('AMOUNT_DELTA_UNAVAILABLE');
    } else if (current.cumulative_amount_raw < previous.cumulative_amount_raw) {
      limitations.push(isMiddayBoundary(previous.quote_time, current.quote_time)
        ? 'MIDDAY_AMOUNT_COUNTER_RESET'
        : 'AMOUNT_COUNTER_RESET');
    } else {
      amountDelta = current.cumulative_amount_raw - previous.cumulative_amount_raw;
    }
  }

  const previousPrice = previous && finiteNumber(previous.price);
  const open = previousPrice === null ? current.price : previousPrice;
  const unitContractAuthoritative = previous
    && previous.unit_contract_status === 'AUTHORITATIVE'
    && current.unit_contract_status === 'AUTHORITATIVE';
  if (!unitContractAuthoritative) limitations.push('UNIT_CONTRACT_NOT_AUTHORITATIVE');
  const bar = {
    symbol: current.symbol,
    market: current.market,
    minute_start: minuteStart(current.quote_time),
    open,
    high: Math.max(open, current.price),
    low: Math.min(open, current.price),
    close: current.price,
    volume_delta_raw: volumeDeltaRaw,
    volume_delta_shares: volumeDeltaShares,
    amount_delta: amountDelta,
    volume_unit: current.volume_unit,
    amount_unit: current.amount_unit,
    source: 'SNAPSHOT_ROLLUP',
    quality_status: duplicateTimestamp ? 'DUPLICATE' : 'APPROXIMATE',
    limitations: Array.from(new Set(limitations)),
    vwap: unitContractAuthoritative && volumeDeltaShares > 0 && amountDelta !== null
      ? amountDelta / volumeDeltaShares
      : null
  };
  if (bar.vwap === null) bar.limitations.push('VWAP_UNAVAILABLE');
  return {
    snapshot: current,
    bar,
    persistBar: !duplicateTimestamp
  };
}

function isMiddayBoundary(previousTime, currentTime) {
  const previous = chinaDateParts(previousTime);
  const current = chinaDateParts(currentTime);
  return previous.date === current.date
    && previous.time <= '11:30'
    && current.time >= '13:00';
}

function fnv1a32(input) {
  let hash = 0x811c9dc5;
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

function stableStringify(value) {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function providerSubmissionKey(job, packetHash, provider, promptVersion) {
  const attemptCount = Number(job && job.attempt_count);
  if (!job || !job.job_key || !Number.isInteger(attemptCount) || attemptCount < 1) {
    throw new Error('PROVIDER_SUBMISSION_IDENTITY_INVALID');
  }
  return [
    'stock-watch-n8n-v1',
    job.job_key,
    packetHash,
    provider,
    promptVersion,
    `attempt-${attemptCount}`
  ].join(':');
}

function buildFactPacket(input) {
  const bars = Array.isArray(input.bars) ? [...input.bars] : [];
  bars.sort((left, right) => Date.parse(left.minute_start) - Date.parse(right.minute_start));
  const latest = bars.length ? bars[bars.length - 1] : null;
  const facts = {};
  if (bars.length) {
    addReturnFact(facts, bars, 5, 'F_STOCK_RETURN_5M');
    addReturnFact(facts, bars, 15, 'F_STOCK_RETURN_15M');
    addReturnFact(facts, bars, 30, 'F_STOCK_RETURN_30M');
  } else {
    facts.F_STOCK_RETURN_5M = unavailableFact('NO_BARS');
    facts.F_STOCK_RETURN_15M = unavailableFact('NO_BARS');
    facts.F_STOCK_RETURN_30M = unavailableFact('NO_BARS');
  }

  const dayHigh = finiteNumber(input.snapshot && input.snapshot.day_high);
  const dayLow = finiteNumber(input.snapshot && input.snapshot.day_low);
  const close = finiteNumber(latest && latest.close);
  if (close !== null && dayHigh !== null && dayLow !== null && dayHigh > dayLow) {
    facts.F_PRICE_POSITION_DAY = fact((close - dayLow) / (dayHigh - dayLow), 'RATIO', 'SW_QUOTE_SNAPSHOT');
    facts.F_DISTANCE_DAY_HIGH = fact((dayHigh - close) / dayHigh, 'RATIO', 'SW_QUOTE_SNAPSHOT');
  } else {
    facts.F_PRICE_POSITION_DAY = unavailableFact('DAY_RANGE_UNAVAILABLE');
    facts.F_DISTANCE_DAY_HIGH = unavailableFact('DAY_HIGH_UNAVAILABLE');
  }

  const windowHigh = Math.max(...bars.map((bar) => finiteNumber(bar.high)).filter((value) => value !== null));
  facts.F_DRAWDOWN_WINDOW_HIGH = Number.isFinite(windowHigh) && close !== null && windowHigh > 0
    ? fact((close - windowHigh) / windowHigh, 'RATIO', 'SW_BAR_1M')
    : unavailableFact('WINDOW_HIGH_UNAVAILABLE');
  facts.F_BAR_COVERAGE = fact(Math.min(1, bars.length / 30), 'RATIO', 'SW_BAR_1M');
  const snapshotQuoteTime = input.snapshot && input.snapshot.quote_time;
  const parsedQuoteTime = Date.parse(snapshotQuoteTime);
  facts.F_QUOTE_AGE_SECONDS = Number.isFinite(parsedQuoteTime)
    ? fact(
      Math.max(0, Math.round((Date.parse(input.now || new Date().toISOString()) - parsedQuoteTime) / 1000)),
      'SECONDS',
      'SW_QUOTE_SNAPSHOT'
    )
    : unavailableFact('QUOTE_TIME_UNAVAILABLE');

  const validVwapBars = bars.filter((bar) => finiteNumber(bar.vwap) !== null);
  facts.F_PRICE_VS_VWAP = validVwapBars.length >= 24 && close !== null
    ? fact((close - validVwapBars[validVwapBars.length - 1].vwap) / validVwapBars[validVwapBars.length - 1].vwap,
      'RATIO', 'SW_BAR_1M')
    : unavailableFact('VWAP_GATE_NOT_MET');

  const modelInput = {
    schema_version: 'stock-watch.market-fact-input.v1',
    security_type: 'EQUITY',
    market_region: 'CN',
    trading_phase: input.run_slot,
    capabilities: ['L0_BAR'],
    facts,
    limitations: ['NO_ORDER_BOOK', 'NO_TRADE_PRINTS', 'NO_ACTOR_IDENTITY']
  };
  const quoteTime = Number.isFinite(parsedQuoteTime)
    ? new Date(parsedQuoteTime).toISOString()
    : new Date(input.observation_time || input.now).toISOString();
  const snapshotProvider = String(input.snapshot && input.snapshot.provider || '').trim();
  const snapshotSource = String(input.snapshot && input.snapshot.source || '').trim();
  const barSources = Array.from(new Set(bars
    .map((bar) => String(bar && bar.source || '').trim())
    .filter(Boolean))).sort();
  const sourceEvidence = [snapshotProvider, snapshotSource, ...barSources].join('|').toUpperCase();
  const sourceKind = sourceEvidence.includes('LOCAL_QA_FIXTURE') || sourceEvidence.includes('PHASE2_TEST')
    ? 'LOCAL_QA_FIXTURE'
    : snapshotProvider.toUpperCase() === 'EASTMONEY'
      ? 'JAVA_ADAPTER'
      : 'DATABASE_FACTS';
  const provenance = {
    source_kind: sourceKind,
    snapshot_provider: snapshotProvider || null,
    snapshot_source: snapshotSource || null,
    bar_sources: barSources,
    quality_status: String(input.snapshot && input.snapshot.quality_status
      || latest && latest.quality_status
      || 'UNKNOWN')
  };
  const packetCore = {
    schema_version: 'stock-watch.market-fact-packet.v1',
    job_id: input.job_id,
    symbol: input.symbol,
    market: input.market,
    trade_date: input.trade_date,
    run_slot: input.run_slot,
    quote_time: quoteTime,
    provenance,
    facts,
    capabilities: ['L0_BAR'],
    model_input: modelInput
  };
  return {
    ...packetCore,
    packet_hash: `fnv1a32:${fnv1a32(stableStringify(packetCore))}`
  };
}

function addReturnFact(facts, bars, minutes, id) {
  const latest = bars[bars.length - 1];
  const target = Date.parse(latest.minute_start) - (minutes * 60 * 1000);
  const base = [...bars].reverse().find((bar) => Date.parse(bar.minute_start) <= target);
  const latestClose = finiteNumber(latest.close);
  const baseClose = base && finiteNumber(base.close);
  facts[id] = latestClose !== null && baseClose !== null && baseClose > 0
    ? fact((latestClose - baseClose) / baseClose, 'RATIO', 'SW_BAR_1M')
    : unavailableFact(`BAR_${minutes}M_UNAVAILABLE`);
}

function fact(value, unit, source) {
  return { value, unit, source, quality_status: 'VALID', limitations: [] };
}

function unavailableFact(reason) {
  return { value: null, unit: 'UNAVAILABLE', source: 'QUALITY_GATE', quality_status: 'UNAVAILABLE', limitations: [reason] };
}

function mockBehaviorView(packet) {
  const returnFact = packet.facts.F_STOCK_RETURN_30M;
  const usable = returnFact && returnFact.quality_status === 'VALID';
  return {
    schema_version: 'stock-watch.market-behavior.v1',
    analysis_status: usable ? 'VALID' : 'INSUFFICIENT',
    hypotheses: usable ? [{
      hypothesis_id: 'H1',
      type: 'NORMAL_EXECUTION',
      evidence_band: 'LOW',
      support_fact_refs: ['F_STOCK_RETURN_30M'],
      counter_fact_refs: [],
      alternatives: ['INSUFFICIENT_MICROSTRUCTURE_EVIDENCE'],
      next_confirmation_conditions: ['Observe another complete interval']
    }] : [],
    material_changes: [],
    unexplained_fact_refs: usable ? [] : ['F_STOCK_RETURN_30M'],
    limitations: ['L0_BAR_ONLY']
  };
}

function extractModelText(response) {
  if (typeof response === 'string') return response;
  const content = response && response.choices && response.choices[0]
    && response.choices[0].message && response.choices[0].message.content;
  return typeof content === 'string' ? content : '';
}

function transportBoolean(rawResponse, transport, field) {
  const embeddedPayloads = [rawResponse, transport]
    .map(value => value && value.error && value.error.message)
    .filter(value => typeof value === 'string')
    .map(message => {
      const marker = message.indexOf(' - ');
      const payloadText = marker >= 0 ? message.slice(marker + 3) : message;
      try {
        return JSON.parse(payloadText);
      } catch (_) {
        return null;
      }
    });
  const candidates = [
    rawResponse,
    transport,
    transport && transport.body,
    transport && transport.error,
    transport && transport.response,
    transport && transport.response && transport.response.body,
    ...embeddedPayloads
  ];
  for (const candidate of candidates) {
    if (candidate && typeof candidate === 'object'
      && typeof candidate[field] === 'boolean') {
      return candidate[field];
    }
  }
  return null;
}

function validateModelOutput(rawResponse, packet, transport = null) {
  const rawText = extractModelText(rawResponse);
  const errors = [];
  let output = null;
  const statusCode = finiteNumber(transport && (
    transport.statusCode
    || transport.status
    || (transport.error && (transport.error.statusCode || transport.error.status))
    || (transport.response && (transport.response.statusCode || transport.response.status))
  ));
  const transportText = JSON.stringify(transport || {});
  const providerOutcomeUnknown = transportBoolean(rawResponse, transport, 'providerOutcomeUnknown');
  const retrySafe = transportBoolean(rawResponse, transport, 'retrySafe');
  const retryCandidateTransport = (statusCode !== null
      && (statusCode === 408 || statusCode === 429 || statusCode >= 500))
    || /timeout|timed out/i.test(transportText);
  if (statusCode !== null && statusCode >= 400) {
    errors.push({ code: statusCode === 408 || statusCode === 504 ? 'MODEL_TIMEOUT' : 'MODEL_TRANSPORT_ERROR', status_code: statusCode });
  } else if (/timeout|timed out/i.test(transportText)) {
    errors.push({ code: 'MODEL_TIMEOUT' });
  }
  if (providerOutcomeUnknown === true) {
    errors.push({ code: 'MODEL_PROVIDER_OUTCOME_UNKNOWN' });
  } else if (retryCandidateTransport && retrySafe !== true) {
    errors.push({ code: 'MODEL_RETRY_SAFETY_UNPROVEN' });
  }
  if (!rawText.trim()) {
    errors.push({ code: 'MODEL_EMPTY' });
  } else {
    try {
      output = JSON.parse(rawText);
    } catch (error) {
      errors.push({ code: 'MODEL_INVALID_JSON', detail: error.message });
    }
  }
  if (output === null && rawText.trim()) {
    errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'root must be an object' });
  } else if (output !== null && (typeof output !== 'object' || Array.isArray(output))) {
    errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'root must be an object' });
  }
  if (output && output.schema_version !== 'stock-watch.market-behavior.v1') {
    errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'schema_version' });
  }
  if (output && !ALLOWED_ANALYSIS_STATUSES.has(output.analysis_status)) {
    errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'analysis_status' });
  }
  if (output && !Array.isArray(output.hypotheses)) {
    errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'hypotheses' });
  }
  if (output) {
    checkExactKeys(output, [
      'schema_version', 'analysis_status', 'hypotheses', 'material_changes',
      'unexplained_fact_refs', 'limitations'
    ], 'root', errors);
    for (const field of ['material_changes', 'unexplained_fact_refs', 'limitations']) {
      checkStringArray(output[field], field, errors);
    }
  }

  const facts = new Set(Object.keys((packet && packet.facts) || {}));
  const hypotheses = output && Array.isArray(output.hypotheses) ? output.hypotheses : [];
  for (const hypothesis of hypotheses) {
    if (!hypothesis || typeof hypothesis !== 'object' || Array.isArray(hypothesis)) {
      errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'hypothesis must be an object' });
      continue;
    }
    checkExactKeys(hypothesis, [
      'hypothesis_id', 'type', 'evidence_band', 'support_fact_refs',
      'counter_fact_refs', 'alternatives', 'next_confirmation_conditions'
    ], 'hypothesis', errors);
    if (typeof hypothesis.hypothesis_id !== 'string' || !hypothesis.hypothesis_id.trim()) {
      errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'hypothesis_id' });
    }
    if (!EVIDENCE_BANDS.has(hypothesis.evidence_band)) {
      errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: 'evidence_band' });
    }
    if (L0_FORBIDDEN_HYPOTHESES.has(hypothesis.type)) {
      errors.push({ code: 'CAPABILITY_VIOLATION', hypothesis_type: hypothesis.type });
    } else if (!ALLOWED_HYPOTHESES.has(hypothesis.type)) {
      errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: `hypothesis type ${hypothesis.type}` });
    }
    for (const field of ['support_fact_refs', 'counter_fact_refs']) {
      if (!checkStringArray(hypothesis[field], field, errors)) {
        continue;
      }
      for (const ref of hypothesis[field]) {
        if (!facts.has(ref)) errors.push({ code: 'INVALID_FACT_REF', fact_id: ref });
      }
    }
    for (const field of ['alternatives', 'next_confirmation_conditions']) {
      checkStringArray(hypothesis[field], field, errors);
    }
  }
  const unexplainedFactRefs = output && Array.isArray(output.unexplained_fact_refs)
    ? output.unexplained_fact_refs
    : [];
  for (const ref of unexplainedFactRefs) {
    if (!facts.has(ref)) errors.push({ code: 'INVALID_FACT_REF', fact_id: ref });
  }

  if (output) {
    const serialized = JSON.stringify(output);
    const forbidden = [
      /\b(buy|sell|position sizing|win rate|cvar|manipulation confirmed|recommend(?:ation)?|advis(?:e|ed|ory)|increase (?:the )?position|decrease (?:the )?position|add(?:\s+to)?[\s-]+(?:the[\s-]+)?position|reduce (?:the )?position|enter (?:the )?position|exit (?:the )?position|stop loss|take profit)\b/i,
      /(买入|卖出|建议加仓|建议减仓|加仓|减仓|建仓|清仓|调仓|止损|止盈|抄底|追涨|仓位比例|胜率|操纵已确认|主力一定)/
    ];
    for (const pattern of forbidden) {
      if (pattern.test(serialized)) errors.push({ code: 'POLICY_VIOLATION', detail: pattern.source });
    }
    const allowedNumbers = packetNumericTokens(packet);
    for (const value of narrativeStrings(output)) {
      for (const token of narrativeNumbers(value)) {
        if (!allowedNumbers.has(token)) {
          errors.push({ code: 'POLICY_VIOLATION', detail: `UNMAPPED_NUMBER:${token}` });
        }
      }
    }
  }

  const retryable = retrySafe === true && providerOutcomeUnknown !== true
    && errors.some((error) => error.code === 'MODEL_TIMEOUT'
      || (error.code === 'MODEL_TRANSPORT_ERROR'
        && (error.status_code === 429 || error.status_code >= 500)));
  return {
    status: errors.length ? 'rejected' : 'accepted',
    structure_status: structureStatus(errors),
    retryable,
    errors,
    output,
    raw_text: rawText
  };
}

function narrativeNumbers(value) {
  const tokens = [];
  const pattern = /(^|[^A-Za-z_])(-?\d+(?:\.\d+)?)(?=$|[^A-Za-z_])/g;
  let match;
  while ((match = pattern.exec(value)) !== null) tokens.push(match[2]);
  return tokens;
}

function checkExactKeys(value, allowedKeys, location, errors) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  const allowed = new Set(allowedKeys);
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: `${location}.extra.${key}` });
  }
  for (const key of allowedKeys) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: `${location}.missing.${key}` });
    }
  }
}

function checkStringArray(value, field, errors) {
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    errors.push({ code: 'MODEL_SCHEMA_INVALID', detail: field });
    return false;
  }
  return true;
}

function packetNumericTokens(packet) {
  const tokens = new Set();
  for (const factValue of Object.values((packet && packet.facts) || {})) {
    const value = finiteNumber(factValue && factValue.value);
    if (value === null) continue;
    tokens.add(String(value));
    tokens.add(value.toFixed(2));
    tokens.add(value.toFixed(4));
  }
  return tokens;
}

function narrativeStrings(output) {
  const values = [];
  const hypotheses = Array.isArray(output.hypotheses) ? output.hypotheses : [];
  for (const hypothesis of hypotheses) {
    if (!hypothesis || typeof hypothesis !== 'object' || Array.isArray(hypothesis)) continue;
    for (const key of ['alternatives', 'next_confirmation_conditions']) {
      if (Array.isArray(hypothesis[key])) values.push(...hypothesis[key].filter((value) => typeof value === 'string'));
    }
  }
  for (const key of ['material_changes', 'limitations']) {
    if (Array.isArray(output[key])) values.push(...output[key].filter((value) => typeof value === 'string'));
  }
  return values;
}

function structureStatus(errors) {
  const codes = new Set(errors.map((error) => error.code));
  if (codes.has('MODEL_PROVIDER_OUTCOME_UNKNOWN')) return 'MODEL_PROVIDER_OUTCOME_UNKNOWN';
  if (codes.has('MODEL_RETRY_SAFETY_UNPROVEN')) return 'MODEL_RETRY_SAFETY_UNPROVEN';
  if (codes.has('MODEL_TIMEOUT')) return 'MODEL_TIMEOUT';
  if (codes.has('MODEL_TRANSPORT_ERROR')) return 'MODEL_TRANSPORT_ERROR';
  if (codes.has('CAPABILITY_VIOLATION')) return 'CAPABILITY_VIOLATION';
  if (codes.has('INVALID_FACT_REF')) return 'INVALID_FACT_REF';
  if (codes.has('POLICY_VIOLATION')) return 'POLICY_VIOLATION';
  if (codes.size) return 'INVALID_OUTPUT';
  return 'STRUCTURE_VALID';
}

function buildStressReport(input) {
  const packet = input.packet;
  const facts = packet.facts || {};
  const close = finiteNumber(input.current_price);
  const dayLow = finiteNumber(input.day_low);
  const windowLow = finiteNumber(input.window_low);
  const position = input.position || {};
  const policy = input.risk_policy || {};
  const totalQuantity = Math.max(0, finiteNumber(position.total_quantity) || 0);
  const availableQuantity = Math.max(0, finiteNumber(position.available_quantity) || 0);
  const lockedQuantity = Math.max(0, totalQuantity - availableQuantity);
  const budget = finiteNumber(policy.max_stress_loss_yuan);
  const blockNewEntryWhenLocked = policy.block_new_entry_when_locked === true
    || String(policy.block_new_entry_when_locked).toLowerCase() === 'true';
  const entryBlocked = blockNewEntryWhenLocked && lockedQuantity > 0;
  const scenarioInputs = [
    ['RETURN_TO_30M_LOW', windowLow, []],
    ['RETURN_TO_DAY_LOW', dayLow, []],
    ['MARKET_WEAKENING_FIXED_SHOCK', close === null ? null : close * 0.97, ['FIXED_PRICE_SHOCK_NOT_FORECAST']],
    ['NEXT_SESSION_GAP_DOWN_MILD', close === null ? null : close * 0.97, ['NEXT_SESSION_SCENARIO']],
    ['NEXT_SESSION_GAP_DOWN_SEVERE', close === null ? null : close * 0.93, ['NEXT_SESSION_SCENARIO']],
    ['T1_LOCKED', close, ['EXECUTION_UNAVAILABLE_FOR_LOCKED_QUANTITY']],
    ['EXECUTION_SLIPPAGE_FIXED', close === null ? null : close * 0.99, ['FIXED_SLIPPAGE_ASSUMPTION']],
    ['PRICE_LIMIT_STRESS', close === null ? null : close * 0.90, ['DEFAULT_10_PERCENT_LIMIT_ASSUMPTION']]
  ];
  const scenarios = scenarioInputs.map(([code, stressedPrice, limitations]) => ({
    code,
    scenario_version: 'phase2.v1',
    assumptions: ['Deterministic stress parameter; not a probability forecast'],
    input_price: close,
    stressed_price: stressedPrice,
    estimated_loss_yuan: close !== null && stressedPrice !== null
      ? Math.max(0, (close - stressedPrice) * totalQuantity)
      : null,
    limitations
  }));
  const maxLoss = Math.max(0, ...scenarios.map((scenario) => scenario.estimated_loss_yuan || 0));
  const positionState = totalQuantity === 0 ? 'NO_POSITION'
    : availableQuantity === 0 ? 'FULLY_LOCKED_T1'
      : availableQuantity < totalQuantity ? 'PARTIALLY_LOCKED_T1'
        : 'FULLY_SELLABLE';
  let hardRisk = 'NORMAL';
  if (input.validation_status !== 'accepted') hardRisk = 'MODEL_UNCALIBRATED';
  else if (close === null) hardRisk = 'DATA_INVALID';
  else if (budget !== null && maxLoss > budget) hardRisk = 'RISK_BUDGET_BREACHED';
  else if (entryBlocked) hardRisk = 'BLOCK_NEW_ENTRY';
  else if (lockedQuantity > 0) hardRisk = 'T1_LOCKED_WARNING';
  const markdown = [
    '# Stock Watch Stress Report',
    '',
    `Packet: ${packet.packet_hash}`,
    `Validation: ${input.validation_status}`,
    `Hard risk: ${hardRisk}`,
    `Position state: ${positionState}`,
    `Total quantity: ${totalQuantity}`,
    `Available quantity: ${availableQuantity}`,
    `Locked quantity: ${lockedQuantity}`,
    `New entry blocked while locked: ${entryBlocked}`,
    `Maximum deterministic stress loss: ${maxLoss.toFixed(2)} CNY`,
    `Configured stress budget: ${budget === null ? 'unavailable' : budget.toFixed(2) + ' CNY'}`,
    '',
    'This report contains deterministic stress conditions, not probabilities or action instructions.',
    `Bar coverage: ${facts.F_BAR_COVERAGE ? facts.F_BAR_COVERAGE.value : 'unavailable'}`
  ].join('\n');
  return {
    markdown,
    hard_risk: hardRisk,
    position_state: positionState,
    entry_blocked: entryBlocked,
    max_stress_loss_yuan: maxLoss,
    scenarios
  };
}

module.exports = {
  ANALYSIS_SLOTS,
  analysisSlot,
  tradingSessionState,
  normalizeJavaQuoteEnvelope,
  calculateSnapshotDelta,
  providerSubmissionKey,
  buildFactPacket,
  mockBehaviorView,
  extractModelText,
  validateModelOutput,
  buildStressReport
};
