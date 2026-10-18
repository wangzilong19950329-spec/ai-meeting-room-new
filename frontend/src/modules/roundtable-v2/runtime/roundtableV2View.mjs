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
  research_plan: '议员列取证题',
  research_match: '资料比对',
  solo: '单人对照',
  solo_rewrite: '单人对照重写',
  source_check: '原文核验',
  other: '其他'
}

const SOURCE_CHECK_LABELS = { VERIFIED: '原文核实', CONTRADICTED: '原文更正', NOT_FOUND: '原文未找到', UNAVAILABLE: '未核验' }
const SOURCE_CHECK_REASONS = {
  SOURCE_NOT_FOUND: '没有找到原文',
  SOURCE_NOT_CITED: '给出的链接不在搜索结果里，未采信',
  SOURCE_AFTER_AS_OF: '原文晚于会议截至日，未采信',
  SOURCE_DATE_MISSING: '原文没有发布日期，未采信',
  NUMBERS_NOT_IN_QUOTE: '说法里的数字不在原文摘录中，未采信',
  SOURCE_PROOF_MISSING: '没有给出原文摘录或链接，未采信',
  CODEX_REPLY_INVALID: 'Codex 回复无法解析',
  SOURCE_CHECK_MODEL_MISSING: '没有配置 Codex 模型',
  SOURCE_CHECK_NO_RESULT: '没有拿到核对结果',
  SUPPORT_CHECK_FAILED: '摘录复核调用失败'
}

const UNSOURCED_NUMBERS = '含有资料里找不到来源的数字，重写后仍不合格'

export function buildRoundtableV2View(v2, usage) {
  if (!v2?.issueCard) return null
  const card = v2.issueCard
  const result = v2.result || null
  const questions = questionIndex(card, v2.outcomeQuestion)
  const facts = factRows(v2.facts, v2.topUp)
  const factById = new Map(facts.map(fact => [fact.id, fact]))
  const crossExams = [
    ...(v2.crossExams || []).map(exam => crossExam(exam, 1)),
    ...(v2.secondCrossExams || []).map(exam => crossExam(exam, 2))
  ]
  const research = (v2.research || []).map(researchView)
  const red = redTeam(v2.redTeam)

  return {
    finished: Boolean(result),
    progress: progress(v2),
    header: header(card),
    facts,
    factCounts: countTiers(facts),
    outcome: outcome(result?.outcome),
    research,
    interventions: (v2.interventions || []).map(interventionView),
    canIntervene: !result,
    judgement: judgementView(v2.outcomeJudgement),
    solo: soloView(v2.solo, result, card, v2.outcomeQuestion),
    canRequestSolo: Boolean(result) && !v2.solo,
    judgementPendingText: judgementPending(v2),
    conclusions: (result?.conclusions || []).map(conclusion),
    disputes: (result?.disputes || []).map(line => dispute(line, factById)),
    insufficient: (result?.insufficient || []).map(line => ({ title: line.title, reason: line.reason })),
    crossExams,
    redTeam: red,
    process: processView(v2, questions, research, crossExams, red),
    changes: (result?.changes || []).map(change => changeRow(change, questions)),
    openQuestions: result?.openQuestions || [],
    skippedMembers: (v2.answers || []).filter(answer => answer.skipped)
      .map(answer => ({ memberName: answer.memberName, reason: answer.reason || '' })),
    stats: result
      ? {
          answeredCount: result.answeredCount ?? 0,
          skippedCount: result.skippedCount ?? 0,
          // The summary counts one loop even when the agenda had nothing to cross-examine.
          loops: crossExams.length ? (result.loops ?? 1) : 0
        }
      : null,
    usage: usageView(usage)
  }
}

function header(card) {
  const days = card.horizonTradingDays
  const horizonText = card.horizon
    ? card.horizon + (days && !card.horizon.includes('交易日') ? `（${days} 个交易日）` : '')
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
  if (fact.sourceCheck) badges.push(SOURCE_CHECK_LABELS[fact.sourceCheck.status] || fact.sourceCheck.status)
  if (fromTopUp) badges.push('补证')
  if (fact.userProvided) badges.push('用户提供')
  else if (fact.askedBy) badges.push(fact.askedBy === '你' ? '你提出' : `${fact.askedBy}查询`)
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
  const check = fact.sourceCheck
  if (check && (check.status === 'VERIFIED' || check.status === 'CONTRADICTED')) {
    const quoted = `原文（${check.publishedAt}）：“${check.quote}”`
    return fact.originalStatement ? `${quoted}；此前的低置信说法“${fact.originalStatement}”与原文不符` : quoted
  }
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
  if (code.startsWith('RETRIES_EXHAUSTED')) return '审核搜索多次重试仍失败'
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

/** A user intervention: what was asked for, whether it has been applied yet, and what came of it. */
function interventionView(item) {
  const typeLabel = item.type === 'FACT' ? '补充资料' : item.type === 'SEARCH' ? '追加取证' : `向「${item.targetName}」提问`
  const ids = item.factIds || []
  let statusLabel = '未处理'
  let resultText = item.note || ''
  if (item.status === 'APPLIED') {
    statusLabel = item.stage ? `已处理（${item.stage}）` : '已处理'
    if (item.type === 'FACT') resultText = `已加入资料 ${ids.join('、')}`
    else if (item.type === 'SEARCH') resultText = ids.length && ids.every(id => id.startsWith('G-')) ? `未查到（${ids.join('、')}）` : `查到 ${ids.join('、')}`
    else resultText = item.reply || item.note || ''
  } else if (item.status === 'QUEUED') {
    statusLabel = item.type === 'ASK' ? '排队中：等议员都作答后处理' : '排队中：下一个环节开始前处理'
    resultText = ''
  }
  return { typeLabel, content: item.content, statusLabel, resultText, applied: item.status === 'APPLIED' }
}

/** One member's own evidence questions: what an existing fact already answered and what was searched. */
function researchView(record) {
  const questions = (record.rounds || []).flatMap(round => (round.questions || []).map(line => ({
    question: line.question,
    sourceText: line.reused
      ? `复用 ${line.reused.join('、')}`
      : `新查 → ${(line.searched || []).join('、')}${(line.searched || []).every(id => id.startsWith('G-')) ? '（未查到）' : ''}`
  })))
  const reused = questions.filter(q => q.sourceText.startsWith('复用')).length
  let summaryText = questions.length
    ? `问了 ${questions.length} 题：复用已有资料 ${reused} 题，新查 ${questions.length - reused} 题`
    : '没有追加取证'
  if (record.inProgress) summaryText = `进行中：第 ${(record.rounds || []).length} 轮，已问 ${questions.length} 题`
  return { memberName: record.memberName, summaryText, questions }
}

/**
 * The meeting in the order it happened: what each stage produced and what each member did in it. A stage
 * is running when it has started and is not done while the meeting has no summary yet.
 */
function processView(v2, questions, research, crossExams, red) {
  const card = v2.issueCard
  const answers = v2.answers || []
  const finals = v2.finalAnswers || []
  const result = v2.result
  const answered = Boolean(v2.agenda) || finals.length > 0 || Boolean(result)
  const shared = (v2.facts || []).filter(fact => !fact.askedBy)
  const counts = countTiers(shared.map(fact => ({ tier: fact.tier })))
  const firstByMember = new Map(answers.map(answer => [answer.memberName, answer]))
  const steps = [
    {
      key: 'card', title: '问题卡', done: true, active: true,
      summaryText: `${(card.subQuestions || []).filter(q => q.kind !== 'PROBABILITY').length} 道子问题，`
        + `${(card.evidenceQuestions || []).length} 道共享取证题`
    },
    {
      key: 'facts', title: '共享底稿', done: shared.length > 0, active: false,
      summaryText: `共享底稿 ${shared.length} 条：已核实 ${counts.F} · 低置信 ${counts.L} · 缺口 ${counts.G}`
    },
    {
      key: 'research', title: '议员自主取证', done: answered, active: research.length > 0,
      summaryText: research.length ? `${research.length} 位议员取证` : '没有议员追加取证',
      members: research
    },
    {
      key: 'answers', title: '背靠背作答', done: answered, active: answers.length > 0,
      members: answers.map(answer => answerCard(answer, questions, v2.outcomeQuestion))
    },
    {
      key: 'debate', title: '交锋与红队', done: finals.length > 0 || Boolean(result), active: crossExams.length > 0 || Boolean(red),
      exams: crossExams, redTeam: red
    },
    {
      key: 'reestimate', title: '匿名再估', done: Boolean(v2.classification) || Boolean(result), active: finals.length > 0,
      members: finals.map(final => ({ memberName: final.memberName, changeText: changeText(final, firstByMember.get(final.memberName), questions) }))
    },
    ...(v2.sourceCheck || !result ? [verifyStep(v2)] : []),
    { key: 'summary', title: '程序汇总', done: Boolean(result), active: false }
  ]
  return steps.map(({ active, ...step }) => ({ ...step, running: !result && !step.done && active }))
}

// Members' cited low-confidence facts and gaps, each checked against its original source after the first round.
function verifyStep(v2) {
  const record = v2.sourceCheck
  const factById = new Map((v2.facts || []).map(fact => [fact.id, fact]))
  const checks = (record?.checks || []).map(check => sourceCheckRow(check, factById))
  const targets = record?.targets || []
  let summaryText = ''
  if (record?.done) {
    const count = (status) => checks.filter(check => check.status === status).length
    summaryText = targets.length
      ? `核对 ${targets.length} 条低置信资料和缺口：` + ['VERIFIED', 'CONTRADICTED', 'NOT_FOUND', 'UNAVAILABLE']
        .map(status => `${SOURCE_CHECK_LABELS[status]} ${count(status)}`).join(' · ')
      : '成员没有引用低置信资料或缺口，无需核对'
  } else if (record) {
    summaryText = `已核对 ${checks.length} / ${targets.length} 条`
  }
  return { key: 'verify', title: '原文核验', done: Boolean(record?.done) || Boolean(v2.result), active: Boolean(record), summaryText, checks }
}

function sourceCheckRow(check, factById) {
  const definitive = check.status === 'VERIFIED' || check.status === 'CONTRADICTED'
  const url = /^https?:\/\//i.test(check.sourceUrl || '') ? check.sourceUrl : null
  const reason = String(check.reason || '')
  return {
    factId: check.factId,
    status: check.status,
    question: factById.get(check.factId)?.question || '',
    statusLabel: SOURCE_CHECK_LABELS[check.status] || check.status,
    text: definitive ? check.statement || ''
      : reason === 'QUOTE_DOES_NOT_SUPPORT' ? `原文摘录读不出这个说法（缺少：${check.missing || '未说明'}），未采信`
        : SOURCE_CHECK_REASONS[reason] || (reason.startsWith('CODEX_') ? 'Codex 调用失败' : reason),
    quote: definitive ? check.quote || '' : '',
    publishedAt: check.publishedAt || '',
    sourceUrl: url,
    sourceText: url ? hostOf(url) : ''
  }
}

function answerCard(answer, questions, outcomeQuestion) {
  if (answer.skipped) return { memberName: answer.memberName, skipped: true, reasonText: answer.reason || '', choices: [], outcomeText: '' }
  const items = answer.answers || []
  const choices = items.filter(item => item.optionId).map(item => {
    const question = questions.get(item.questionId)
    return {
      questionId: item.questionId,
      title: question?.title || item.questionId,
      optionLabel: question?.labels.get(item.optionId) || item.optionId,
      reasons: reasons(item.reasons),
      falsifier: item.falsifier || ''
    }
  })
  const outcome = items.find(item => item.probabilities)
  const outcomeText = outcome && outcomeQuestion
    ? (outcomeQuestion.options || []).map(o => `${o.label} ${Math.round(Number(outcome.probabilities[o.id] || 0) * 100)}%`).join(' / ')
    : ''
  return { memberName: answer.memberName, skipped: false, reasonText: '', choices, outcomeText }
}

function changeText(final, first, questions) {
  if (final.skipped) return '未参加再估'
  if (final.fallback) return '再估无效，沿用首轮'
  const before = new Map((first?.answers || []).map(item => [item.questionId, item.optionId]))
  const changes = (final.answers || []).filter(item => item.optionId && before.get(item.questionId) && before.get(item.questionId) !== item.optionId)
    .map(item => {
      const question = questions.get(item.questionId)
      const label = (id) => question?.labels.get(id) || id
      return `${question?.title || item.questionId}：${label(before.get(item.questionId))} → ${label(item.optionId)}`
        + (item.changeReason ? `（${item.changeReason}）` : '')
    })
  return changes.length ? changes.join('；') : '维持首轮选择'
}

function judgementPending(v2) {
  const question = v2.outcomeQuestion
  if (v2.outcomeJudgement?.status === 'JUDGED' || !question?.baseDate || !question.horizonTradingDays) return ''
  return `起点 ${question.baseDate} 之后第 ${question.horizonTradingDays} 个交易日收盘后，由程序判定实际涨跌落在哪一档并给每个人打分`
}

function judgementView(verdict) {
  if (verdict?.status !== 'JUDGED') return null
  const uniform = Number(verdict.uniform?.rps)
  const pooled = Number(verdict.pooled?.rps)
  // A random walk with the question's own σ is the harder baseline; meetings judged before it existed use equal odds.
  const walk = verdict.randomWalk
  const baseline = walk ? Number(walk.rps) : uniform
  const baselineName = walk ? '随机游走基准' : '均匀猜测'
  const comparison = pooled < baseline ? '优于' : pooled > baseline ? '不如' : '持平于'
  return {
    returnText: `${verdict.returnPct > 0 ? '+' : ''}${Number(verdict.returnPct).toFixed(2)}%`,
    observedLabel: verdict.observedLabel,
    priceText: `${verdict.baseDate} 收盘 ${verdict.baseClose} → ${verdict.endDate} 收盘 ${verdict.endClose}`
      + `（${verdict.source}前复权，${verdict.horizonTradingDays} 个交易日）`,
    pooledRpsText: score(pooled),
    pooledBrierText: score(verdict.pooled?.brier),
    uniformRpsText: score(uniform),
    uniformBrierText: score(verdict.uniform?.brier),
    randomWalkRpsText: walk ? score(walk.rps) : '',
    randomWalkBrierText: walk ? score(walk.brier) : '',
    randomWalkText: walk?.probabilities
      ? '随机游走概率：' + walk.probabilities.map(p => `${(Number(p) * 100).toFixed(1)}%`).join(' / ')
      : '',
    verdictText: comparison === '持平于' ? `合议分布与${baselineName}持平` : `合议分布${comparison}${baselineName}`,
    solo: verdict.solo ? {
      rpsText: score(verdict.solo.rps),
      brierText: score(verdict.solo.brier),
      beatsUniform: Number(verdict.solo.rps) < uniform,
      beatsRoundtable: Number(verdict.solo.rps) < pooled
    } : null,
    members: [...(verdict.members || [])]
      .sort((a, b) => Number(a.rps) - Number(b.rps))
      .map(member => ({
        memberName: member.memberName,
        rpsText: score(member.rps),
        brierText: score(member.brier),
        beatsUniform: Number(member.rps) < uniform,
        beatsBaseline: Number(member.rps) < baseline
      }))
  }
}

const RELATION_LABELS = { same: '一致', different: '不同', disputed: '圆桌有分歧', open: '圆桌未下结论' }

/** The single-analyst control next to what the roundtable concluded, question by question. */
function soloView(solo, result, card, outcomeQuestion) {
  if (!solo || solo.skipped) return null
  const choices = new Map((solo.answers || []).map(answer => [answer.questionId, answer]))
  const rows = (card.subQuestions || []).filter(q => q.kind !== 'PROBABILITY').map(question => {
    const labels = new Map((question.options || []).map(o => [o.id, o.label]))
    const choice = choices.get(question.id)
    const conclusion = (result?.conclusions || []).find(line => line.questionId === question.id)
    const dispute = (result?.disputes || []).find(line => line.questionId === question.id)
    let roundtableText = '证据不足，未下结论'
    let relation = 'open'
    if (conclusion) {
      roundtableText = `${conclusion.optionLabel}（${conclusion.support}/${conclusion.total} 人）`
      relation = choice?.optionId === conclusion.optionId ? 'same' : 'different'
    } else if (dispute) {
      roundtableText = '有分歧：' + (dispute.positions || [])
        .map(position => `${position.optionLabel} ${(position.members || []).length} 人`).join(' / ')
      relation = 'disputed'
    }
    return {
      questionId: question.id,
      title: question.title,
      roundtableText,
      soloText: labels.get(choice?.optionId) || choice?.optionId || '未作答',
      relation,
      relationLabel: RELATION_LABELS[relation],
      soloReasons: reasons(choice?.reasons)
    }
  })
  return {
    modelType: solo.modelType,
    rows,
    differentCount: rows.filter(row => row.relation === 'different').length,
    outcome: soloOutcome(choices.get(outcomeQuestion?.id), outcomeQuestion, result?.outcome)
  }
}

function soloOutcome(choice, outcomeQuestion, pooled) {
  if (!choice?.probabilities || !outcomeQuestion || !pooled) return null
  const labels = new Map((outcomeQuestion.options || []).map(o => [o.id, o.label]))
  let mode = null
  for (const option of outcomeQuestion.options || []) {
    if (mode === null || Number(choice.probabilities[option.id]) > Number(choice.probabilities[mode])) mode = option.id
  }
  return {
    soloModeLabel: labels.get(mode),
    roundtableModeLabel: labels.get(pooled.aggregateMode),
    same: mode === pooled.aggregateMode
  }
}

function score(value) {
  return Number(value).toFixed(4)
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
    evidenceThin: Boolean(line.evidenceThin),
    evidenceThinText: line.evidenceThin ? '证据不足的共识：多数理由只引用了资料缺口，一致不代表被证实' : '',
    correctedText: line.correctedFactIds?.length ? `所引 ${line.correctedFactIds.join('、')} 经原文更正，已据此重新评估` : '',
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
    ['议员自主取证', Boolean(v2.agenda) || finalAnswers.length > 0 || result, `${(v2.research || []).length} 人已取证`],
    ['背靠背作答', Boolean(v2.agenda) || finalAnswers.length > 0 || result, `${answers.length} 人已作答`],
    ['交锋与红队', finalAnswers.length > 0 || result, crossExams ? `${crossExams} 场质询` : ''],
    ['匿名再估', Boolean(v2.classification) || result, finalAnswers.length ? `${finalAnswers.length} 人已再估` : ''],
    ['分歧分类与补证', result, ''],
    ...(v2.sourceCheck || !result
      ? [['原文核验', Boolean(v2.sourceCheck?.done) || result,
          v2.sourceCheck ? `${(v2.sourceCheck.checks || []).length}/${(v2.sourceCheck.targets || []).length} 条` : '']]
      : []),
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
