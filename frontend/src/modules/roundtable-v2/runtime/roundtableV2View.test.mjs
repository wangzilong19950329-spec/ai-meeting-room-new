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
    ['议员自主取证', false],
    ['背靠背作答', false],
    ['交锋与红队', false],
    ['匿名再估', false],
    ['分歧分类与补证', false],
    ['原文核验', false],
    ['程序汇总', false]
  ])
  assert.equal(view.progress[3].detail, '2 人已作答')
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

test('a consensus resting mostly on evidence gaps is flagged with an explanation', () => {
  const v2 = clone(sample)
  v2.result.conclusions[0].evidenceThin = true
  const flagged = buildRoundtableV2View(v2, null).conclusions[0]
  assert.equal(flagged.evidenceThin, true)
  assert.equal(flagged.evidenceThinText, '证据不足的共识：多数理由只引用了资料缺口，一致不代表被证实')
  assert.equal(buildRoundtableV2View(sample, null).conclusions[0].evidenceThin, false)
})

const judgement = {
  status: 'JUDGED', baseDate: '2026-10-16', baseClose: 102, endDate: '2027-01-13', endClose: 112.2,
  horizonTradingDays: 60, returnPct: 10.0, observedOptionId: 'D', observedLabel: '上涨8%–23%', source: '东方财富',
  pooled: { rps: 0.1416, brier: 0.8356 }, uniform: { rps: 0.15, brier: 0.8 },
  members: [
    { memberId: 21, memberName: '甲', rps: 0.095, brier: 0.76 },
    { memberId: 22, memberName: '乙', rps: 0.295, brier: 1.16 },
    { memberId: 23, memberName: '丙', rps: 0.095, brier: 0.76 }
  ],
  judgedAt: '2027-01-13T08:00:00Z'
}

test('before expiry the outcome section says when and how it will be judged', () => {
  const view = buildRoundtableV2View(sample, null)
  assert.equal(view.judgement, null)
  assert.equal(view.judgementPendingText, '起点 2026-10-16 之后第 60 个交易日收盘后，由程序判定实际涨跌落在哪一档并给每个人打分')
  const general = clone(sample)
  general.outcomeQuestion = null
  general.result.outcome = undefined
  assert.equal(buildRoundtableV2View(general, null).judgementPendingText, '')
})

test('after expiry the review shows the actual move, the pooled score against a uniform guess and each member', () => {
  const v2 = clone(sample)
  v2.outcomeJudgement = judgement
  const review = buildRoundtableV2View(v2, null).judgement
  assert.equal(review.returnText, '+10.00%')
  assert.equal(review.observedLabel, '上涨8%–23%')
  assert.equal(review.priceText, '2026-10-16 收盘 102 → 2027-01-13 收盘 112.2（东方财富前复权，60 个交易日）')
  assert.equal(review.pooledRpsText, '0.1416')
  assert.equal(review.uniformRpsText, '0.1500')
  assert.equal(review.verdictText, '合议分布优于均匀猜测')
  assert.deepEqual(review.members.map(m => [m.memberName, m.rpsText, m.brierText, m.beatsUniform]), [
    ['甲', '0.0950', '0.7600', true],
    ['丙', '0.0950', '0.7600', true],
    ['乙', '0.2950', '1.1600', false]
  ])
})

const soloAnswer = {
  memberName: '单人分析', modelType: 'b6465a2d', skipped: false,
  answers: [
    { questionId: 'Q1', optionId: 'B', reasons: [{ text: '营收增速放缓', factIds: ['F-9'] }], falsifier: '若营收回升则失效' },
    { questionId: 'Q2', optionId: 'B', reasons: [{ text: '订单待核', factIds: ['L-1'] }], falsifier: '若订单落地则失效' },
    { questionId: 'OUTCOME', probabilities: { A: 0, B: 0.2, C: 0.3, D: 0.4, E: 0.1 }, reasons: [{ text: '估值低', factIds: ['F-5'] }] }
  ]
}

test('a concluded meeting without a solo control offers to create one', () => {
  assert.equal(buildRoundtableV2View(sample, null).solo, null)
  assert.equal(buildRoundtableV2View(sample, null).canRequestSolo, true)
  const running = clone(sample)
  running.result = null
  assert.equal(buildRoundtableV2View(running, null).canRequestSolo, false)
})

test('the solo control is compared question by question with what the roundtable concluded', () => {
  const v2 = clone(sample)
  v2.solo = soloAnswer
  const view = buildRoundtableV2View(v2, null)
  assert.equal(view.canRequestSolo, false)
  assert.deepEqual(view.solo.rows.map(r => [r.questionId, r.roundtableText, r.soloText, r.relationLabel]), [
    ['Q1', '支撑股价（3/3 人）', '拖累股价', '不同'],
    ['Q2', '有分歧：偏强 1 人 / 偏弱 2 人', '偏弱', '圆桌有分歧']
  ])
  assert.deepEqual(view.solo.rows[0].soloReasons, [{ text: '营收增速放缓', factIds: ['F-9'] }])
  assert.equal(view.solo.differentCount, 1)
  assert.deepEqual(view.solo.outcome, { soloModeLabel: '上涨8%–23%', roundtableModeLabel: '在±8%以内', same: false })
})

test('the expiry review scores the solo control next to the roundtable', () => {
  const v2 = clone(sample)
  v2.outcomeJudgement = { ...judgement, solo: { rps: 0.075, brier: 0.5 } }
  const review = buildRoundtableV2View(v2, null).judgement
  assert.deepEqual(review.solo, { rpsText: '0.0750', brierText: '0.5000', beatsUniform: true, beatsRoundtable: true })
  assert.equal(buildRoundtableV2View({ ...clone(sample), outcomeJudgement: judgement }, null).judgement.solo, null)
})

test('usage names the solo control stage', () => {
  const stages = [{ stage: 'solo', calls: 1, meteredCalls: 0, unmeteredCalls: 1, promptChars: 900, completionChars: 600, durationMs: 80000 }]
  const view = buildRoundtableV2View(sample, { total: { calls: 1, meteredCalls: 0, unmeteredCalls: 1, promptChars: 900, completionChars: 600, durationMs: 80000 }, stages })
  assert.equal(view.usage.stages[0].stageLabel, '单人对照')
})

test('facts a member found are marked with who asked for them', () => {
  const v2 = clone(sample)
  v2.facts.push({ id: 'F-23', tier: 'F', question: '最新季度出货同比增速是多少？', statement: '最新季度出货同比增长20%', source: 'https://example.org/q', askedBy: '甲' })
  const fact = buildRoundtableV2View(v2, null).facts.find(f => f.id === 'F-23')
  assert.deepEqual(fact.badges, ['甲查询'])
})

test('each member research is summarised with what was reused and what was searched', () => {
  const v2 = clone(sample)
  v2.research = [
    { memberName: '甲', rounds: [{ round: 1, questions: [
      { question: '最新季度出货同比增速是多少？', searched: ['F-23'] },
      { question: '市盈率处于什么分位？', reused: ['F-5'] }
    ] }, { round: 2, questions: [{ question: '海外关税细则是什么？', searched: ['G-2'] }] }] },
    { memberName: '乙', rounds: [] }
  ]
  assert.deepEqual(buildRoundtableV2View(v2, null).research, [
    {
      memberName: '甲',
      summaryText: '问了 3 题：复用已有资料 1 题，新查 2 题',
      questions: [
        { question: '最新季度出货同比增速是多少？', sourceText: '新查 → F-23' },
        { question: '市盈率处于什么分位？', sourceText: '复用 F-5' },
        { question: '海外关税细则是什么？', sourceText: '新查 → G-2（未查到）' }
      ]
    },
    { memberName: '乙', summaryText: '没有追加取证', questions: [] }
  ])
  assert.deepEqual(buildRoundtableV2View(sample, null).research, [])
})

test('usage names the research stages', () => {
  const line = (stage) => ({ stage, calls: 1, meteredCalls: 1, unmeteredCalls: 0, promptTokens: 1, completionTokens: 1, durationMs: 1000 })
  const view = buildRoundtableV2View(sample, { total: line(null), stages: [line('research_plan'), line('research_match')] })
  assert.deepEqual(view.usage.stages.map(s => s.stageLabel), ['议员列取证题', '资料比对'])
})

test('the meeting process lists every stage in order with what each member did', () => {
  const view = buildRoundtableV2View(sample, null)
  assert.deepEqual(view.process.map(step => [step.key, step.title, step.done]), [
    ['card', '问题卡', true],
    ['facts', '共享底稿', true],
    ['research', '议员自主取证', true],
    ['answers', '背靠背作答', true],
    ['debate', '交锋与红队', true],
    ['reestimate', '匿名再估', true],
    ['summary', '程序汇总', true]
  ])
  assert.equal(view.process[1].summaryText, '共享底稿 22 条：已核实 20 · 低置信 1 · 缺口 1')
  assert.equal(view.process[2].summaryText, '没有议员追加取证')
  const answers = view.process[3]
  assert.deepEqual(answers.members.map(m => m.memberName), ['甲', '乙', '丙'])
  assert.deepEqual(answers.members[0].choices.map(c => [c.questionId, c.title, c.optionLabel]), [
    ['Q1', '业绩基本面', '支撑股价'],
    ['Q2', '订单与需求', '偏强']
  ])
  assert.deepEqual(answers.members[0].choices[0].reasons, [{ text: '市盈率22.5倍，处于近一年较低分位', factIds: ['F-4'] }])
  assert.equal(answers.members[1].outcomeText, '跌幅超过23% 10% / 下跌8%–23% 50% / 在±8%以内 30% / 上涨8%–23% 10% / 涨幅超过23% 0%')
  assert.deepEqual(view.process[5].members.map(m => [m.memberName, m.changeText]), [
    ['甲', '维持首轮选择'],
    ['乙', '维持首轮选择'],
    ['丙', '订单与需求：偏强 → 偏弱（质询后认为订单增速放缓的证据更扎实）']
  ])
})

test('a running meeting marks the stage in progress and shows research question by question', () => {
  const v2 = {
    issueCard: sample.issueCard, outcomeQuestion: sample.outcomeQuestion, facts: sample.facts,
    answers: [], crossExams: [], finalAnswers: [], secondCrossExams: [], secondFinalAnswers: [], result: null,
    research: [{ memberName: '甲', inProgress: true, rounds: [{ round: 1, questions: [{ question: '最新季度出货同比增速是多少？', searched: ['F-23'] }] }] }]
  }
  const view = buildRoundtableV2View(v2, null)
  const research = view.process.find(step => step.key === 'research')
  assert.equal(research.done, false)
  assert.equal(research.running, true)
  assert.deepEqual(research.members.map(m => [m.memberName, m.summaryText]), [['甲', '进行中：第 1 轮，已问 1 题']])
  assert.equal(view.process.find(step => step.key === 'answers').running, false)
  assert.equal(view.process.find(step => step.key === 'summary').done, false)
})

test('user interventions are listed with their status and what they produced', () => {
  const v2 = clone(sample)
  v2.result = null
  v2.interventions = [
    { type: 'FACT', content: '三季度营收同比增长37%', source: '业绩说明会', status: 'APPLIED', factIds: ['F-23'], stage: '议员作答之前' },
    { type: 'SEARCH', content: '最新季度出货同比增速是多少？', status: 'APPLIED', factIds: ['G-3'], stage: '议员作答之后' },
    { type: 'ASK', content: '你为什么认为需求偏强？', targetName: '乙', status: 'APPLIED', reply: '乙：依据订单数据，维持原判。' },
    { type: 'ASK', content: '订单数据从哪来？', targetName: '丙', status: 'QUEUED' },
    { type: 'FACT', content: '补充一条', status: 'EXPIRED', note: '会议已收尾，未及处理' }
  ]
  const view = buildRoundtableV2View(v2, null)
  assert.equal(view.canIntervene, true)
  assert.deepEqual(view.interventions.map(i => [i.typeLabel, i.statusLabel, i.resultText]), [
    ['补充资料', '已处理（议员作答之前）', '已加入资料 F-23'],
    ['追加取证', '已处理（议员作答之后）', '未查到（G-3）'],
    ['向「乙」提问', '已处理', '乙：依据订单数据，维持原判。'],
    ['向「丙」提问', '排队中：等议员都作答后处理', ''],
    ['补充资料', '未处理', '会议已收尾，未及处理']
  ])
  assert.equal(buildRoundtableV2View(sample, null).canIntervene, false)
})

test('facts from the user are marked as provided by the user or asked by the user', () => {
  const v2 = clone(sample)
  v2.facts.push({ id: 'F-23', tier: 'F', question: '用户补充', statement: '三季度营收同比增长37%', source: '业绩说明会', userProvided: true, askedBy: '你' })
  v2.facts.push({ id: 'F-24', tier: 'F', question: '最新季度出货同比增速是多少？', statement: '出货同比增长20%', source: 'https://example.org/q', askedBy: '你' })
  const view = buildRoundtableV2View(v2, null)
  assert.deepEqual(view.facts.find(f => f.id === 'F-23').badges, ['用户提供'])
  assert.deepEqual(view.facts.find(f => f.id === 'F-24').badges, ['你提出'])
})

test('a gap left after repeated search outages says so', () => {
  const v2 = clone(sample)
  v2.facts.push({ id: 'G-9', tier: 'G', question: '半年报存货是多少？', reason: 'RETRIES_EXHAUSTED_N8N_REVIEW_ERROR', askedBy: '甲' })
  assert.equal(buildRoundtableV2View(v2, null).facts.find(f => f.id === 'G-9').note, '审核搜索多次重试仍失败')
})

const checkedFacts = () => [
  {
    id: 'L-7', tier: 'F', question: '最新订单规模是多少？', statement: '最新订单规模60亿元',
    originalStatement: '待核验的订单规模80亿', source: 'https://www.cninfo.com.cn/a.pdf',
    sourceCheck: { factId: 'L-7', status: 'CONTRADICTED', statement: '最新订单规模60亿元', quote: '订单规模为60亿元', sourceUrl: 'https://www.cninfo.com.cn/a.pdf', publishedAt: '2026-09-30' }
  },
  {
    id: 'G-7', tier: 'F', question: '海外关税影响多大？', statement: '海外关税对营收影响约5%', source: 'https://example.org/tariff',
    sourceCheck: { factId: 'G-7', status: 'VERIFIED', statement: '海外关税对营收影响约5%', quote: '关税对营收影响约5%', sourceUrl: 'https://example.org/tariff', publishedAt: '2026-10-09' }
  },
  {
    id: 'L-8', tier: 'L', question: '库存周期在哪个阶段？', statement: '库存处于去化尾声', confidenceScore: 0.25,
    sourceCheck: { factId: 'L-8', status: 'UNAVAILABLE', reason: 'CODEX_HTTP_500' }
  },
  {
    id: 'G-8', tier: 'G', question: '半年报存货是多少？', reason: 'N8N_REVIEW_UNVERIFIED',
    sourceCheck: { factId: 'G-8', status: 'NOT_FOUND', reason: 'SOURCE_AFTER_AS_OF' }
  }
]

test('facts checked against the original source say what the check found', () => {
  const v2 = clone(sample)
  v2.facts.push(...checkedFacts())
  const view = buildRoundtableV2View(v2, null)
  const corrected = view.facts.find(f => f.id === 'L-7')
  assert.equal(corrected.tierLabel, '已核实')
  assert.deepEqual(corrected.badges, ['原文更正'])
  assert.equal(corrected.note, '原文（2026-09-30）：“订单规模为60亿元”；此前的低置信说法“待核验的订单规模80亿”与原文不符')
  assert.equal(corrected.sourceUrl, 'https://www.cninfo.com.cn/a.pdf')
  const filled = view.facts.find(f => f.id === 'G-7')
  assert.deepEqual(filled.badges, ['原文核实'])
  assert.equal(filled.note, '原文（2026-10-09）：“关税对营收影响约5%”')
  const unchecked = view.facts.find(f => f.id === 'L-8')
  assert.deepEqual(unchecked.badges, ['低置信', '未核验'])
  assert.equal(unchecked.note, '置信度 0.25')
  const notFound = view.facts.find(f => f.id === 'G-8')
  assert.deepEqual(notFound.badges, ['原文未找到'])
  assert.equal(notFound.note, '审核搜索未能核实')
})

test('the source check is a stage of the process that lists each fact and what the source said', () => {
  const v2 = clone(sample)
  v2.facts.push(...checkedFacts())
  v2.sourceCheck = { targets: ['L-7', 'G-7', 'L-8', 'G-8'], done: true, checks: checkedFacts().map(f => f.sourceCheck) }
  const view = buildRoundtableV2View(v2, null)
  assert.deepEqual(view.process.map(step => step.key),
    ['card', 'facts', 'research', 'answers', 'debate', 'reestimate', 'verify', 'summary'])
  const step = view.process.find(s => s.key === 'verify')
  assert.equal(step.title, '原文核验')
  assert.equal(step.done, true)
  assert.equal(step.summaryText, '核对 4 条低置信资料和缺口：原文核实 1 · 原文更正 1 · 原文未找到 1 · 未核验 1')
  assert.deepEqual(step.checks.map(c => [c.factId, c.question, c.statusLabel, c.text]), [
    ['L-7', '最新订单规模是多少？', '原文更正', '最新订单规模60亿元'],
    ['G-7', '海外关税影响多大？', '原文核实', '海外关税对营收影响约5%'],
    ['L-8', '库存周期在哪个阶段？', '未核验', 'Codex 调用失败'],
    ['G-8', '半年报存货是多少？', '原文未找到', '原文晚于会议截至日，未采信']
  ])
  assert.equal(step.checks[0].quote, '订单规模为60亿元')
  assert.equal(step.checks[0].sourceUrl, 'https://www.cninfo.com.cn/a.pdf')
  assert.equal(step.checks[0].sourceText, 'www.cninfo.com.cn')
})

test('a running source check shows how far it got', () => {
  const v2 = clone(sample)
  v2.result = null
  v2.sourceCheck = { targets: ['L-1', 'G-1', 'G-2'], done: false, checks: [{ factId: 'L-1', status: 'VERIFIED', statement: '待核验的订单规模80亿', quote: '订单规模80亿', sourceUrl: 'https://example.org/o', publishedAt: '2026-09-01' }] }
  const step = buildRoundtableV2View(v2, null).process.find(s => s.key === 'verify')
  assert.equal(step.done, false)
  assert.equal(step.running, true)
  assert.equal(step.summaryText, '已核对 1 / 3 条')
})

test('a source check with nothing to check says so, and a meeting from before the check has no such stage', () => {
  const v2 = clone(sample)
  v2.sourceCheck = { targets: [], done: true, checks: [] }
  assert.equal(buildRoundtableV2View(v2, null).process.find(s => s.key === 'verify').summaryText,
    '成员没有引用低置信资料或缺口，无需核对')
  assert.equal(buildRoundtableV2View(sample, null).process.find(s => s.key === 'verify'), undefined)
})

test('a conclusion that rested on a corrected fact says it was re-estimated after the correction', () => {
  const v2 = clone(sample)
  v2.result.conclusions[0].correctedFactIds = ['L-1']
  const flagged = buildRoundtableV2View(v2, null).conclusions[0]
  assert.equal(flagged.correctedText, '所引 L-1 经原文更正，已据此重新评估')
  assert.equal(buildRoundtableV2View(sample, null).conclusions[0].correctedText, '')
})

test('usage names the source check stage', () => {
  const line = (stage) => ({ stage, calls: 1, meteredCalls: 1, unmeteredCalls: 0, promptTokens: 1, completionTokens: 1, durationMs: 1000 })
  const view = buildRoundtableV2View(sample, { total: line(null), stages: [line('source_check')] })
  assert.deepEqual(view.usage.stages.map(s => s.stageLabel), ['原文核验'])
})

test('a check whose quote did not support the statement, or whose second reading failed, says so', () => {
  const v2 = clone(sample)
  v2.sourceCheck = {
    targets: ['G-1', 'G-2'], done: true,
    checks: [
      { factId: 'G-1', status: 'NOT_FOUND', reason: 'QUOTE_DOES_NOT_SUPPORT', missing: '7月末的价格' },
      { factId: 'G-2', status: 'UNAVAILABLE', reason: 'SUPPORT_CHECK_FAILED' }
    ]
  }
  const step = buildRoundtableV2View(v2, null).process.find(s => s.key === 'verify')
  assert.deepEqual(step.checks.map(c => c.text), ['原文摘录读不出这个说法（缺少：7月末的价格），未采信', '摘录复核调用失败'])
})

test('with a random-walk baseline the review compares everyone against it and still lists the uniform guess', () => {
  const v2 = clone(sample)
  v2.outcomeJudgement = { ...judgement, randomWalk: { rps: 0.147, brier: 0.8082, probabilities: [0.0673, 0.2341, 0.3972, 0.2341, 0.0673] } }
  const review = buildRoundtableV2View(v2, null).judgement
  assert.equal(review.randomWalkRpsText, '0.1470')
  assert.equal(review.randomWalkBrierText, '0.8082')
  assert.equal(review.randomWalkText, '随机游走概率：6.7% / 23.4% / 39.7% / 23.4% / 6.7%')
  assert.equal(review.verdictText, '合议分布优于随机游走基准')
  assert.deepEqual(review.members.map(m => [m.memberName, m.beatsBaseline]), [['甲', true], ['丙', true], ['乙', false]])
  const older = clone(sample)
  older.outcomeJudgement = judgement
  assert.equal(buildRoundtableV2View(older, null).judgement.randomWalkRpsText, '')
  assert.equal(buildRoundtableV2View(older, null).judgement.members[2].beatsBaseline, false)
})

test('a finished meeting offers a review of chosen questions with an estimate and lists the reviews already made', () => {
  const v2 = clone(sample)
  v2.reviews = [{ childMeetingId: 120, questionIds: ['Q2'], facts: [{ content: '三季度新增订单同比下降', source: '公司公告' }], createdAt: '2026-10-20T08:00:00Z' }]
  const view = buildRoundtableV2View(v2, null)
  assert.equal(view.canReview, true)
  assert.deepEqual(view.reviewOptions.map(o => [o.id, o.title]), [['Q1', '业绩基本面'], ['Q2', '订单与需求']])
  assert.equal(view.reviewEstimateText(3), '预计约 14–25 次模型调用：每位议员先盲答、再对照上一版，之后照常交锋和汇总；不另做取证')
  assert.deepEqual(view.reviews.map(r => [r.meetingId, r.text]), [[120, '复议 订单与需求；新情况：三季度新增订单同比下降（公司公告）']])
  const running = clone(sample)
  running.result = null
  assert.equal(buildRoundtableV2View(running, null).canReview, false)
  const judged = clone(sample)
  judged.outcomeJudgement = judgement
  assert.equal(buildRoundtableV2View(judged, null).canReview, false)
})

test('a review shows where it came from, what was new and how each question moved from the parent', () => {
  const v2 = clone(sample)
  v2.review = {
    parentMeetingId: 98, parentTitle: '宁德时代未来三个月走势', parentAsOf: '2026-10-17', questionIds: ['Q1', 'Q2'],
    facts: [{ content: '三季度新增订单同比下降', source: '公司公告' }],
    parentResult: {
      conclusions: [{ questionId: 'Q1', title: '业绩基本面', optionLabel: '拖累股价', support: 2, total: 3 }],
      disputes: [{ questionId: 'Q2', title: '订单与需求', positions: [{ optionLabel: '偏强', members: [{}, {}] }, { optionLabel: '偏弱', members: [{}] }] }]
    }
  }
  v2.informedAnswers = [
    { memberName: '甲', skipped: false, answers: [{ questionId: 'Q2', optionId: 'B', changeReason: '新订单转弱' }] },
    { memberName: '乙', skipped: false, fallback: true, answers: [] }
  ]
  v2.answers = [
    { memberName: '甲', skipped: false, answers: [{ questionId: 'Q2', optionId: 'A' }] },
    { memberName: '乙', skipped: false, answers: [{ questionId: 'Q2', optionId: 'B' }] }
  ]
  const view = buildRoundtableV2View(v2, null)
  const review = view.review
  assert.equal(review.headerText, '这是「宁德时代未来三个月走势」（截至 2026-10-17）的复议')
  assert.equal(review.parentMeetingId, 98)
  assert.deepEqual(review.newFacts, ['三季度新增订单同比下降（公司公告）'])
  assert.deepEqual(review.rows.map(r => [r.questionId, r.beforeText, r.afterText]), [
    ['Q1', '拖累股价（2/3 人）', '支撑股价（3/3 人）'],
    ['Q2', '有分歧：偏强 2 人 / 偏弱 1 人', '有分歧：偏强 1 人 / 偏弱 2 人']
  ])
  const informed = view.process.find(step => step.key === 'informed')
  assert.equal(informed.title, '对照上一版')
  assert.deepEqual(informed.members.map(m => [m.memberName, m.changeText]), [
    ['甲', '订单与需求：偏强 → 偏弱（新订单转弱）'],
    ['乙', '对照无效，沿用盲答']
  ])
  assert.equal(view.process.find(step => step.key === 'research').summaryText, '复议不另做取证：沿用上一版的资料和新情况')
  assert.equal(buildRoundtableV2View(sample, null).review, null)
})

test('a review header gives the trading days left to the original expiry, not the original horizon', () => {
  const v2 = clone(sample)
  v2.issueCard = { ...v2.issueCard, reviewOf: { meetingId: 96, baseDate: '2026-10-16', horizonTradingDays: 60 } }
  v2.outcomeQuestion = { ...v2.outcomeQuestion, baseDate: '2026-10-30', horizonTradingDays: 50 }
  assert.equal(buildRoundtableV2View(v2, null).header.horizonText, '剩余 50 个交易日（到期日与上一版相同）')
})

test('a settled answer that changed the blind one without a reason says the blind answer was kept', () => {
  const v2 = clone(sample)
  v2.review = { parentMeetingId: 98, parentTitle: 't', parentAsOf: '2026-10-17', questionIds: ['Q2'], facts: [], parentResult: {} }
  v2.answers = [{ memberName: '甲', skipped: false, answers: [{ questionId: 'Q2', optionId: 'B' }] }]
  v2.informedAnswers = [{ memberName: '甲', skipped: false, revertedToBlind: ['Q2'], answers: [{ questionId: 'Q2', optionId: 'B' }] }]
  const informed = buildRoundtableV2View(v2, null).process.find(step => step.key === 'informed')
  assert.deepEqual(informed.members.map(m => m.changeText), ['与盲答一致（订单与需求：改了盲答却说不出理由，保留盲答）'])
})

test('suggestions to review are listed until a review is started after them', () => {
  const v2 = clone(sample)
  v2.reviewHints = [
    { kind: 'PRICE', text: '截至 2026-11-02 收盘 120，自 2026-10-16 收盘 102 起 +17.65%，约 2.8 个标准差', at: '2026-11-02T08:00:00Z' },
    { kind: 'TRACKING', text: '追踪项「海外关税」有关键变化：美国加征关税正式落地', at: '2026-11-05T08:00:00Z' }
  ]
  v2.reviews = [{ childMeetingId: 120, questionIds: ['Q2'], facts: [], createdAt: '2026-11-03T08:00:00Z' }]
  const view = buildRoundtableV2View(v2, null)
  assert.deepEqual(view.reviewHints.map(h => [h.label, h.text]), [['追踪项变化', '追踪项「海外关税」有关键变化：美国加征关税正式落地']])
  const judged = clone(v2)
  judged.outcomeJudgement = judgement
  assert.deepEqual(buildRoundtableV2View(judged, null).reviewHints, [])
})
