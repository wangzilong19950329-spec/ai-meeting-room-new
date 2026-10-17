import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { buildRoundtableV2View } from './roundtableV2View.mjs'

const sample = JSON.parse(readFileSync(
  new URL('../../../../scripts/fixtures/roundtable-v2-sample.json', import.meta.url), 'utf8'))

const usage = {
  total: {
    stage: null, calls: 20, meteredCalls: 16, unmeteredCalls: 4, promptTokens: 12000, completionTokens: 3400,
    cacheHitTokens: 5000, promptChars: 200, completionChars: 8000, durationMs: 754000
  },
  stages: [
    {
      stage: 'divergence', calls: 3, meteredCalls: 3, unmeteredCalls: 0, promptTokens: 6000, completionTokens: 1500,
      cacheHitTokens: 0, promptChars: 0, completionChars: 0, durationMs: 30000
    },
    {
      stage: 'evidence_search', calls: 4, meteredCalls: 0, unmeteredCalls: 4, promptTokens: 0, completionTokens: 0,
      cacheHitTokens: 0, promptChars: 200, completionChars: 8000, durationMs: 600000
    }
  ]
}

const clone = (value) => JSON.parse(JSON.stringify(value))

test('returns null until the moderator has written the issue card', () => {
  assert.equal(buildRoundtableV2View(null, null), null)
  assert.equal(buildRoundtableV2View({ issueCard: null, answers: [] }, usage), null)
})

test('issue card shows topic, stock, horizon and sub-questions', () => {
  const view = buildRoundtableV2View(sample, usage)
  assert.equal(view.header.topic, '宁德时代未来三个月走势')
  assert.equal(view.header.asOf, '2026-10-17')
  assert.equal(view.header.stockText, '300750.SZ')
  assert.equal(view.header.horizonText, '三个月（60 个交易日）')
  assert.deepEqual(view.header.subQuestions.map(q => [q.id, q.title, q.optionsText]), [
    ['Q1', '业绩基本面', 'A 支撑股价 / B 拖累股价'],
    ['Q2', '订单与需求', 'A 偏强 / B 偏弱']
  ])
})

test('fact base rows carry tier labels and visible confidence badges', () => {
  const view = buildRoundtableV2View(sample, usage)
  assert.equal(view.facts.length, 22)
  assert.deepEqual(view.factCounts, { F: 20, L: 1, G: 1 })

  const close = view.facts.find(f => f.id === 'F-1')
  assert.equal(close.tierLabel, '已核实')
  assert.equal(close.text, '收盘价（2026-10-16，前复权日K）：180.50元')
  assert.deepEqual(close.badges, ['东财单源'])
  assert.equal(close.sourceText, '东方财富')
  assert.equal(close.sourceUrl, null)

  assert.deepEqual(view.facts.find(f => f.id === 'F-19').badges, ['东财单源', '参照类'])

  const searched = view.facts.find(f => f.id === 'F-20')
  assert.deepEqual(searched.badges, [])
  assert.equal(searched.sourceUrl, 'https://example.org/source')

  const low = view.facts.find(f => f.id === 'L-1')
  assert.equal(low.tierLabel, '低置信')
  assert.deepEqual(low.badges, ['低置信'])
  assert.equal(low.note, '置信度 0.25 · 缺少独立来源')

  const gap = view.facts.find(f => f.id === 'G-1')
  assert.equal(gap.tierLabel, '缺口')
  assert.equal(gap.text, '海外关税影响多大？')
  assert.equal(gap.note, '审核搜索未能核实')
})

test('outcome distribution shows pooled means, ranges, the mode and the program-picked minority', () => {
  const view = buildRoundtableV2View(sample, usage)
  const outcome = view.outcome
  assert.equal(outcome.title, '未来60个交易日收益率（以2026-10-16收盘价为起点，前复权）')
  assert.equal(outcome.memberCount, 3)
  assert.deepEqual(outcome.bars.map(b => [b.id, b.label, b.meanText, b.rangeText, b.isMode]), [
    ['A', '跌幅超过23%', '3.3%', '0%–10%', false],
    ['B', '下跌8%–23%', '23.3%', '10%–50%', false],
    ['C', '在±8%以内', '43.3%', '30%–50%', true],
    ['D', '上涨8%–23%', '23.3%', '10%–30%', false],
    ['E', '涨幅超过23%', '6.7%', '0%–10%', false]
  ])
  assert.equal(outcome.bars[2].widthPct, 43.3)
  assert.deepEqual(outcome.minority, [{
    memberName: '乙',
    modeLabel: '下跌8%–23%',
    reasons: [{ text: '估值不低', factIds: ['F-1'] }],
    falsifier: '若需求明显转弱则判断失效'
  }])
})

test('conclusions and disputes come straight from the program summary', () => {
  const view = buildRoundtableV2View(sample, usage)
  assert.equal(view.conclusions.length, 1)
  const conclusion = view.conclusions[0]
  assert.equal(conclusion.title, '业绩基本面')
  assert.equal(conclusion.optionLabel, '支撑股价')
  assert.equal(conclusion.supportText, '3/3 人')
  assert.equal(conclusion.resolvedAfterDispute, false)
  assert.deepEqual(conclusion.members.map(m => m.memberName), ['甲', '乙', '丙'])
  assert.deepEqual(conclusion.members[1].reasons, [{ text: '营收同比8.5%，增速一般', factIds: ['F-9'] }])

  assert.equal(view.disputes.length, 1)
  const dispute = view.disputes[0]
  assert.equal(dispute.title, '订单与需求')
  assert.equal(dispute.typeLabel, '解读分歧')
  assert.equal(dispute.typeHint, '事实相同，权重或因果判断不同：保留双方推理链，由你判断')
  assert.equal(dispute.statusLabel, '仍有分歧')
  assert.equal(dispute.crux, '若需求回暖，则偏强；否则偏弱')
  assert.deepEqual(dispute.positions.map(p => [p.optionLabel, p.members.map(m => m.memberName)]), [
    ['偏强', ['甲']],
    ['偏弱', ['乙', '丙']]
  ])
  assert.deepEqual(dispute.topUpFacts, [])
  assert.deepEqual(view.openQuestions, ['海外关税影响多大？'])
  assert.deepEqual(view.stats, { answeredCount: 3, skippedCount: 0, loops: 1 })
})

test('cross-examination, red team and position changes are shown with who said what', () => {
  const view = buildRoundtableV2View(sample, usage)
  assert.deepEqual(view.crossExams.map(c => [c.round, c.title, c.askerName, c.askerOption, c.responderName, c.responderOption]), [
    [1, '订单与需求', '甲', '偏强', '乙', '偏弱']
  ])
  assert.equal(view.crossExams[0].question, '甲：请说明你的判断依据哪条事实。')
  assert.equal(view.crossExams[0].reply, '乙：请说明你的判断依据哪条事实。')
  assert.equal(view.crossExams[0].failNote, null)

  assert.equal(view.redTeam.memberName, '丙')
  assert.equal(view.redTeam.targetText, '业绩基本面：支撑股价')
  assert.equal(view.redTeam.argument, '若下一季度需求转弱，这条结论最可能失效。')
  assert.equal(view.redTeam.failNote, null)

  assert.deepEqual(view.changes, [{
    memberName: '丙',
    questionTitle: '订单与需求',
    fromLabel: '偏强',
    toLabel: '偏弱',
    reason: '质询后认为订单增速放缓的证据更扎实'
  }])
})

test('usage shows total tokens, unmetered calls and time per stage', () => {
  const view = buildRoundtableV2View(sample, usage)
  assert.equal(view.usage.totalText, '共 20 次调用 · 输入 12,000 / 输出 3,400 tokens（缓存命中 5,000）· 未计量 4 次（约 8,200 字）· 耗时 12 分 34 秒')
  assert.deepEqual(view.usage.stages.map(s => [s.stageLabel, s.calls, s.tokensText, s.durationText]), [
    ['背靠背作答', 3, '7,500 tokens', '30 秒'],
    ['取证搜索', 4, '未计量（约 8,200 字）', '10 分 0 秒']
  ])
  assert.equal(buildRoundtableV2View(sample, null).usage, null)
})

test('fact disputes show top-up facts, second-round exams and failures', () => {
  const v2 = clone(sample)
  v2.topUp = {
    added: [{ id: 'G-2', tier: 'G', question: '最新季度订单规模是多少？', reason: 'N8N_REVIEW_ERROR' }],
    byQuestion: { Q2: ['G-2'] },
    overBudget: []
  }
  Object.assign(v2.result.disputes[0], {
    type: 'FACT', status: 'UNDECIDED', evidenceQuestion: '最新季度订单规模是多少？', topUpFactIds: ['G-2']
  })
  Object.assign(v2.result.conclusions[0], { resolvedAfterDispute: true, type: 'FACT' })
  v2.secondCrossExams = [{ ...clone(sample.crossExams[0]), failed: true, reason: '质询内容含有资料里找不到来源的数字，重写后仍不合格' }]
  delete v2.secondCrossExams[0].question
  delete v2.secondCrossExams[0].reply
  v2.redTeam = { ...clone(sample.redTeam), failed: true }
  delete v2.redTeam.argument

  const view = buildRoundtableV2View(v2, usage)
  assert.equal(view.facts.length, 23)
  assert.deepEqual(view.facts.at(-1).badges, ['补证'])
  assert.equal(view.facts.at(-1).note, '审核搜索出错')

  const dispute = view.disputes[0]
  assert.equal(dispute.typeLabel, '事实分歧')
  assert.equal(dispute.statusLabel, '证据不足未裁决')
  assert.equal(dispute.evidenceQuestion, '最新季度订单规模是多少？')
  assert.deepEqual(dispute.topUpFacts.map(f => [f.id, f.tierLabel]), [['G-2', '缺口']])

  assert.equal(view.conclusions[0].resolvedAfterDispute, true)

  assert.deepEqual(view.crossExams.map(c => [c.round, c.failNote]), [
    [1, null],
    [2, '质询内容含有资料里找不到来源的数字，重写后仍不合格']
  ])
  assert.equal(view.redTeam.argument, null)
  assert.equal(view.redTeam.failNote, '红队论证含有资料里找不到来源的数字，重写后仍不合格')
})

test('a meeting still running shows which stages are done and no summary yet', () => {
  const v2 = {
    issueCard: sample.issueCard,
    outcomeQuestion: sample.outcomeQuestion,
    facts: sample.facts,
    answers: sample.answers.slice(0, 2),
    crossExams: [], finalAnswers: [], secondCrossExams: [], secondFinalAnswers: [],
    agenda: null, redTeam: null, classification: null, topUp: null, result: null
  }
  const view = buildRoundtableV2View(v2, null)
  assert.equal(view.finished, false)
  assert.deepEqual(view.progress.map(p => [p.label, p.done]), [
    ['问题卡', true],
    ['事实底稿', true],
    ['背靠背作答', false],
    ['交锋与红队', false],
    ['匿名再估', false],
    ['分歧分类与补证', false],
    ['程序汇总', false]
  ])
  assert.equal(view.progress[2].detail, '2 人已作答')
  assert.deepEqual(view.conclusions, [])
  assert.deepEqual(view.disputes, [])
  assert.equal(view.outcome, null)
  assert.equal(view.redTeam, null)

  assert.equal(buildRoundtableV2View(sample, null).finished, true)
})

test('horizon text does not repeat trading days the moderator already wrote', () => {
  const v2 = clone(sample)
  v2.issueCard.horizon = '2026-10-17至2027-01-17（约60个交易日）'
  assert.equal(buildRoundtableV2View(v2, null).header.horizonText, '2026-10-17至2027-01-17（约60个交易日）')
})

test('a meeting without any cross-examination reports zero loops', () => {
  const v2 = clone(sample)
  v2.crossExams = []
  v2.secondCrossExams = []
  assert.equal(buildRoundtableV2View(v2, null).stats.loops, 0)
  assert.equal(buildRoundtableV2View(sample, null).stats.loops, 1)
})

test('members without a valid answer are listed with the reason', () => {
  const v2 = clone(sample)
  v2.answers[1] = { memberId: 22, memberName: '乙', skipped: true, reason: '「乙」的模型调用失败（meeting_selected_model_unavailable）' }
  assert.deepEqual(buildRoundtableV2View(v2, null).skippedMembers, [
    { memberName: '乙', reason: '「乙」的模型调用失败（meeting_selected_model_unavailable）' }
  ])
  assert.deepEqual(buildRoundtableV2View(sample, null).skippedMembers, [])
})
