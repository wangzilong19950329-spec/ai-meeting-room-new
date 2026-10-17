// Turns GET /meetings/{id}/roundtable-v2 (+ /usage) into display rows. Pure: no Vue, no requests.

const TIER_LABELS = { F: '已核实', L: '低置信', G: '缺口' }

const DISPUTE_TYPES = {
  FACT: ['事实分歧', '双方掌握的事实不同：可以靠补证裁决'],
  INTERPRETATION: ['解读分歧', '事实相同，权重或因果判断不同：保留双方推理链，由你判断'],
  ASSUMPTION: ['假设分歧', '对未来的假设不同：到期验证'],
  RISK_PREFERENCE: ['风险偏好分歧', '判断相同，行动建议不同：取决于你的风险偏好'],
  UNCLASSIFIED: ['未分类', '主持人未能给出合格的分类']
}

const DISPUTE_STATUS = {
  UNRESOLVED: ['仍有分歧', 'warning'],
  UNDECIDED: ['证据不足未裁决', 'info'],
  UNCLASSIFIED: ['未分类', 'info']
}

const STAGE_LABELS = {
  issue_card: '问题卡',
  divergence: '背靠背作答',
  divergence_rewrite: '作答重写',
  cross_exam: '交锋质询',
  cross_exam_rewrite: '质询重写',
  red_team: '红队',
  red_team_rewrite: '红队重写',
  reestimate: '匿名再估',
  reestimate_rewrite: '再估重写',
  classify: '分歧分类',
  classify_rewrite: '分类重写',
  evidence_plan: '取证规划',
  evidence_search: '取证搜索',
  speech: '发言',
  speech_rewrite: '发言重写',
  vote: '投票',
  cluster: '聚类',
  round_summary: '轮次小结',
  summary: '会议总结',
  summary_rewrite: '总结重写',
  decision_card: '决策卡片',
  decision_card_rewrite: '决策卡片重写',
  other: '其他'
}

const UNSOURCED_NUMBERS = '含有资料里找不到来源的数字，重写后仍不合格'

export function buildRoundtableV2View(v2, usage) {
  if (!v2?.issueCard) return null
  const card = v2.issueCard
  const result = v2.result || null
  const questions = questionIndex(card, v2.outcomeQuestion)
  const facts = factRows(v2.facts, v2.topUp)
  const factById = new Map(facts.map(fact => [fact.id, fact]))

  return {
    finished: Boolean(result),
    progress: progress(v2),
    header: header(card),
    facts,
    factCounts: countTiers(facts),
    outcome: outcome(result?.outcome),
    conclusions: (result?.conclusions || []).map(conclusion),
    disputes: (result?.disputes || []).map(line => dispute(line, factById)),
    insufficient: (result?.insufficient || []).map(line => ({ title: line.title, reason: line.reason })),
    crossExams: [
      ...(v2.crossExams || []).map(exam => crossExam(exam, 1)),
      ...(v2.secondCrossExams || []).map(exam => crossExam(exam, 2))
    ],
    redTeam: redTeam(v2.redTeam),
    changes: (result?.changes || []).map(change => changeRow(change, questions)),
    openQuestions: result?.openQuestions || [],
    stats: result
      ? { answeredCount: result.answeredCount ?? 0, skippedCount: result.skippedCount ?? 0, loops: result.loops ?? 1 }
      : null,
    usage: usageView(usage)
  }
}

function header(card) {
  const days = card.horizonTradingDays
  const horizonText = card.horizon
    ? card.horizon + (days ? `（${days} 个交易日）` : '')
    : (days ? `${days} 个交易日` : '')
  const stock = card.stock
  return {
    topic: card.topic,
    asOf: card.asOf,
    horizonText,
    stockText: stock?.symbol ? `${stock.symbol}${stock.market ? '.' + stock.market.toUpperCase() : ''}` : '',
    subQuestions: (card.subQuestions || []).filter(q => q.kind !== 'PROBABILITY').map(q => ({
      id: q.id,
      title: q.title,
      optionsText: (q.options || []).map(o => `${o.id} ${o.label}`).join(' / ')
    }))
  }
}

function questionIndex(card, outcomeQuestion) {
  const index = new Map()
  for (const q of [...(card.subQuestions || []), ...(outcomeQuestion ? [outcomeQuestion] : [])]) {
    index.set(q.id, { title: q.title, labels: new Map((q.options || []).map(o => [o.id, o.label])) })
  }
  return index
}

function factRows(baseFacts, topUp) {
  const rows = (baseFacts || []).map(fact => factRow(fact, false))
  for (const fact of topUp?.added || []) rows.push(factRow(fact, true))
  return rows
}

function factRow(fact, fromTopUp) {
  const badges = []
  if (fact.tier === 'F' && fact.singleSource) badges.push('东财单源')
  if (fact.referenceClass) badges.push('参照类')
  if (fact.tier === 'L') badges.push('低置信')
  if (fromTopUp) badges.push('补证')
  const source = fact.source || ''
  const isUrl = /^https?:\/\//i.test(source)
  return {
    id: fact.id,
    tier: fact.tier,
    tierLabel: TIER_LABELS[fact.tier] || fact.tier,
    question: fact.question,
    text: fact.statement || fact.question,
    badges,
    sourceText: isUrl ? hostOf(source) : source,
    sourceUrl: isUrl ? source : null,
    note: factNote(fact)
  }
}

function factNote(fact) {
  if (fact.tier === 'L') {
    const parts = []
    if (fact.confidenceScore != null) parts.push(`置信度 ${fact.confidenceScore}`)
    if (fact.limitation) parts.push(fact.limitation)
    return parts.join(' · ')
  }
  if (fact.tier === 'G') return gapReasonLabel(fact.reason)
  return ''
}

function gapReasonLabel(reason) {
  const code = String(reason || '')
  if (!code) return '未取到资料'
  if (code === 'N8N_REVIEW_UNVERIFIED' || code === 'N8N_UNVERIFIED') return '审核搜索未能核实'
  if (code === 'N8N_REVIEW_PARTIAL' || code === 'N8N_PARTIAL') return '审核搜索仅部分核实'
  if (/^N8N_(REVIEW_ERROR|ERROR|HTTP_|OUTCOME_UNKNOWN)/.test(code)) return '审核搜索出错'
  if (/^N8N_.*(PROOF|AUDIT|IDENTITY)/.test(code)) return '审核结果校验未通过'
  if (code.startsWith('REVIEWED_SEARCH_')) return '审核搜索未启用或配置不全'
  if (code.startsWith('HISTORY_TOO_SHORT')) return '历史数据不足'
  if (code.startsWith('EASTMONEY_')) return '东方财富取数失败'
  return code
}

function hostOf(url) {
  try {
    return new URL(url).hostname
  } catch {
    return url
  }
}

function countTiers(facts) {
  const counts = { F: 0, L: 0, G: 0 }
  for (const fact of facts) if (fact.tier in counts) counts[fact.tier]++
  return counts
}

function outcome(summary) {
  if (!summary) return null
  const labels = new Map((summary.options || []).map(o => [o.id, o.label]))
  return {
    title: summary.title,
    memberCount: summary.total ?? 0,
    bars: (summary.options || []).map(option => ({
      id: option.id,
      label: option.label,
      meanText: `${percent(option.mean).toFixed(1)}%`,
      widthPct: Number(percent(option.mean).toFixed(1)),
      rangeText: `${trimmedPercent(option.min)}%–${trimmedPercent(option.max)}%`,
      isMode: option.id === summary.aggregateMode
    })),
    minority: (summary.minority || []).map(member => ({
      memberName: member.memberName,
      modeLabel: labels.get(member.mode) || member.mode,
      reasons: reasons(member.reasons),
      falsifier: member.falsifier
    }))
  }
}

function percent(probability) {
  return (Number(probability) || 0) * 100
}

function trimmedPercent(probability) {
  return String(Math.round(percent(probability) * 10) / 10)
}

function reasons(list) {
  return (list || []).map(reason => ({ text: reason.text, factIds: reason.factIds || [] }))
}

function memberRows(members) {
  return (members || []).map(member => ({
    memberName: member.memberName,
    reasons: reasons(member.reasons),
    falsifier: member.falsifier
  }))
}

function conclusion(line) {
  const resolved = Boolean(line.resolvedAfterDispute)
  return {
    questionId: line.questionId,
    title: line.title,
    optionLabel: line.optionLabel,
    supportText: `${line.support}/${line.total} 人`,
    resolvedAfterDispute: resolved,
    typeLabel: resolved && line.type ? (DISPUTE_TYPES[line.type] || [line.type])[0] : '',
    crux: line.crux || '',
    members: memberRows(line.members)
  }
}

function dispute(line, factById) {
  const [typeLabel, typeHint] = DISPUTE_TYPES[line.type] || DISPUTE_TYPES.UNCLASSIFIED
  const [statusLabel, statusTone] = DISPUTE_STATUS[line.status] || DISPUTE_STATUS.UNCLASSIFIED
  return {
    questionId: line.questionId,
    title: line.title,
    typeLabel,
    typeHint,
    statusLabel,
    statusTone,
    crux: line.crux || '',
    evidenceQuestion: line.evidenceQuestion || '',
    topUpFacts: (line.topUpFactIds || []).map(id => factById.get(id)).filter(Boolean),
    positions: (line.positions || []).map(position => ({
      optionId: position.optionId,
      optionLabel: position.optionLabel,
      members: memberRows(position.members)
    }))
  }
}

function crossExam(exam, round) {
  let failNote = null
  if (exam.failed) failNote = exam.reason || '质询未能生成'
  else if (exam.replyFailed) failNote = `回应${UNSOURCED_NUMBERS}`
  return {
    round,
    questionId: exam.questionId,
    title: exam.title,
    askerName: exam.a?.memberName,
    askerOption: exam.a?.optionLabel,
    responderName: exam.b?.memberName,
    responderOption: exam.b?.optionLabel,
    question: exam.question ?? null,
    reply: exam.reply ?? null,
    failNote
  }
}

function redTeam(record) {
  if (!record) return null
  return {
    memberName: record.memberName,
    targetText: `${record.title}：${record.optionLabel}`,
    argument: record.argument ?? null,
    failNote: record.failed ? `红队论证${UNSOURCED_NUMBERS}` : null
  }
}

function changeRow(change, questions) {
  const question = questions.get(change.questionId)
  const label = (id) => question?.labels.get(id) || id
  return {
    memberName: change.memberName,
    questionTitle: question?.title || change.questionId,
    fromLabel: label(change.from),
    toLabel: label(change.to),
    reason: change.reason || ''
  }
}

function progress(v2) {
  const answers = v2.answers || []
  const finalAnswers = v2.finalAnswers || []
  const crossExams = (v2.crossExams || []).length + (v2.secondCrossExams || []).length
  const result = Boolean(v2.result)
  const steps = [
    ['问题卡', true, ''],
    ['事实底稿', Array.isArray(v2.facts), Array.isArray(v2.facts) ? `${v2.facts.length} 条` : ''],
    ['背靠背作答', Boolean(v2.agenda) || finalAnswers.length > 0 || result, `${answers.length} 人已作答`],
    ['交锋与红队', finalAnswers.length > 0 || result, crossExams ? `${crossExams} 场质询` : ''],
    ['匿名再估', Boolean(v2.classification) || result, finalAnswers.length ? `${finalAnswers.length} 人已再估` : ''],
    ['分歧分类与补证', result, ''],
    ['程序汇总', result, '']
  ]
  return steps.map(([label, done, detail]) => ({ label, done, detail }))
}

function usageView(usage) {
  if (!usage?.total) return null
  const total = usage.total
  const parts = [`共 ${total.calls} 次调用`]
  if (total.meteredCalls > 0) {
    let tokens = `输入 ${num(total.promptTokens)} / 输出 ${num(total.completionTokens)} tokens`
    if (total.cacheHitTokens > 0) tokens += `（缓存命中 ${num(total.cacheHitTokens)}）`
    parts.push(tokens)
  }
  if (total.unmeteredCalls > 0) parts.push(`未计量 ${total.unmeteredCalls} 次（约 ${num(chars(total))} 字）`)
  parts.push(`耗时 ${duration(total.durationMs)}`)
  return {
    // A full-width bracket already carries its own spacing.
    totalText: parts.join(' · ').replaceAll('） · ', '）· '),
    stages: (usage.stages || []).map(line => ({
      stage: line.stage,
      stageLabel: STAGE_LABELS[line.stage] || line.stage,
      calls: line.calls,
      tokensText: stageTokens(line),
      durationText: duration(line.durationMs)
    }))
  }
}

function stageTokens(line) {
  const metered = line.meteredCalls > 0 ? `${num(line.promptTokens + line.completionTokens)} tokens` : ''
  if (line.unmeteredCalls <= 0) return metered || '—'
  const unmetered = `未计量（约 ${num(chars(line))} 字）`
  return metered ? `${metered} + ${unmetered}` : unmetered
}

function chars(line) {
  return (line.promptChars || 0) + (line.completionChars || 0)
}

function num(value) {
  return Number(value || 0).toLocaleString('en-US')
}

function duration(ms) {
  const seconds = Math.round((ms || 0) / 1000)
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  return `${Math.floor(seconds / 3600)} 小时 ${Math.floor((seconds % 3600) / 60)} 分`
}
