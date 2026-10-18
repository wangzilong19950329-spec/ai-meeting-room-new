<template>
  <el-card shadow="never" class="v2-card">
    <template #header>
      <div class="v2-head">
        <span>🧭 圆桌研判结果</span>
        <span v-if="view.stats" class="v2-muted">
          {{ view.stats.answeredCount }} 人有效作答<template v-if="view.stats.skippedCount"> · {{ view.stats.skippedCount }} 人作答无效</template>
          · {{ view.stats.loops ? `交锋 ${view.stats.loops} 轮` : '未进行交锋' }}
        </span>
      </div>
    </template>

    <el-alert v-if="view.skippedMembers.length" type="warning" :closable="false" class="v2-skipped">
      <div v-for="member in view.skippedMembers" :key="member.memberName">{{ member.memberName }} 作答无效：{{ member.reason }}</div>
    </el-alert>

    <!-- 问题卡 -->
    <section class="v2-section">
      <div class="v2-title">问题卡</div>
      <div class="v2-issue">
        <strong>{{ view.header.topic }}</strong>
        <span class="v2-muted">
          截至 {{ view.header.asOf }}<template v-if="view.header.horizonText"> · 展望 {{ view.header.horizonText }}</template>
          <template v-if="view.header.stockText"> · {{ view.header.stockText }}</template>
        </span>
      </div>
      <ul class="v2-sub-questions">
        <li v-for="q in view.header.subQuestions" :key="q.id">
          <el-tag size="small" effect="plain">{{ q.id }}</el-tag> {{ q.title }}
          <span class="v2-muted">（{{ q.optionsText }}）</span>
        </li>
      </ul>
    </section>

    <!-- 结果区间 -->
    <section v-if="view.outcome" class="v2-section">
      <div class="v2-title">结果区间与合议概率</div>
      <div class="v2-muted v2-caption">{{ view.outcome.title }} · {{ view.outcome.memberCount }} 人等权平均，括号内为议员给出的最低–最高</div>
      <div v-for="bar in view.outcome.bars" :key="bar.id" class="v2-bar-row" :class="{ mode: bar.isMode }">
        <span class="v2-bar-label">{{ bar.label }}</span>
        <span class="v2-bar-track"><span class="v2-bar-fill" :style="{ width: bar.widthPct + '%' }"></span></span>
        <span class="v2-bar-value">{{ bar.meanText }}</span>
        <span class="v2-muted v2-bar-range">（{{ bar.rangeText }}）</span>
      </div>
      <div v-if="view.outcome.minority.length" class="v2-minority">
        <div class="v2-minority-head">少数派意见（程序按固定规则列出，不删减）</div>
        <div v-for="member in view.outcome.minority" :key="member.memberName" class="v2-minority-item">
          <strong>{{ member.memberName }}</strong> 认为最可能「{{ member.modeLabel }}」：
          <template v-for="(reason, i) in member.reasons" :key="i">
            {{ reason.text }}<FactRefs :ids="reason.factIds" :facts="factById" />
          </template>
          <div v-if="member.falsifier" class="v2-muted">证伪信号：{{ member.falsifier }}</div>
        </div>
      </div>
      <div v-if="!view.judgement && view.judgementPendingText" class="v2-muted v2-pending">到期复盘：{{ view.judgementPendingText }}</div>
    </section>

    <!-- 到期复盘 -->
    <section v-if="view.judgement" class="v2-section">
      <div class="v2-title">到期复盘</div>
      <div class="v2-judge-head">
        实际涨跌 <strong>{{ view.judgement.returnText }}</strong>，落在「{{ view.judgement.observedLabel }}」
        <el-tag size="small" :type="view.judgement.verdictText.includes('优于') ? 'success' : 'warning'">{{ view.judgement.verdictText }}</el-tag>
      </div>
      <div class="v2-muted">{{ view.judgement.priceText }}</div>
      <div class="v2-muted">RPS 衡量概率分布离实际结果有多远，0 为完全命中，越低越好；Brier 为辅助参考。</div>
      <el-table :data="[
        { name: '合议分布（等权）', rps: view.judgement.pooledRpsText, brier: view.judgement.pooledBrierText },
        { name: '均匀猜测（基准）', rps: view.judgement.uniformRpsText, brier: view.judgement.uniformBrierText },
        ...view.judgement.members.map(m => ({ name: m.memberName + (m.beatsUniform ? '' : '（不如基准）'), rps: m.rpsText, brier: m.brierText })),
        ...(view.judgement.solo ? [{ name: '单人分析（对照）' + (view.judgement.solo.beatsRoundtable ? '：优于圆桌' : '：不如圆桌'), rps: view.judgement.solo.rpsText, brier: view.judgement.solo.brierText }] : [])
      ]" size="small" class="v2-judge-table">
        <el-table-column prop="name" label="预测" />
        <el-table-column prop="rps" label="RPS" width="110" />
        <el-table-column prop="brier" label="Brier" width="110" />
      </el-table>
    </section>

    <!-- 结论清单 -->
    <section v-if="view.finished" class="v2-section">
      <div class="v2-title">结论清单 <span class="v2-count">{{ view.conclusions.length }}</span></div>
      <div v-if="!view.conclusions.length" class="v2-empty">没有达到共识门槛的结论</div>
      <div v-for="item in view.conclusions" :key="item.questionId" class="v2-item">
        <div class="v2-item-head">
          <el-tag size="small" effect="plain">{{ item.questionId }}</el-tag>
          <span class="v2-item-title">{{ item.title }}</span>
          <el-tag size="small" :type="item.evidenceThin ? 'info' : 'success'">{{ item.optionLabel }}</el-tag>
          <span class="v2-muted">{{ item.supportText }}</span>
          <el-tag v-if="item.evidenceThin" size="small" type="warning">证据不足</el-tag>
          <el-tag v-if="item.resolvedAfterDispute" size="small" type="warning" effect="plain">
            首轮有分歧，交锋或补证后形成共识<template v-if="item.typeLabel">（原为{{ item.typeLabel }}）</template>
          </el-tag>
        </div>
        <div v-if="item.evidenceThin" class="v2-thin">{{ item.evidenceThinText }}</div>
        <div v-if="item.correctedText" class="v2-thin">{{ item.correctedText }}</div>
        <div v-if="item.crux" class="v2-crux">关键分歧点：{{ item.crux }}</div>
        <MemberReasons :members="item.members" :facts="factById" />
      </div>
      <div v-if="view.insufficient.length" class="v2-insufficient">
        <div v-for="line in view.insufficient" :key="line.title">证据不足：{{ line.title }}（{{ line.reason }}）</div>
      </div>
    </section>

    <!-- 分歧清单 -->
    <section v-if="view.finished" class="v2-section">
      <div class="v2-title">分歧清单 <span class="v2-count">{{ view.disputes.length }}</span></div>
      <div v-if="!view.disputes.length" class="v2-empty">没有遗留分歧</div>
      <div v-for="item in view.disputes" :key="item.questionId" class="v2-item v2-dispute">
        <div class="v2-item-head">
          <el-tag size="small" effect="plain">{{ item.questionId }}</el-tag>
          <span class="v2-item-title">{{ item.title }}</span>
          <el-tag size="small" type="danger" effect="plain">{{ item.typeLabel }}</el-tag>
          <el-tag size="small" :type="item.statusTone">{{ item.statusLabel }}</el-tag>
        </div>
        <div class="v2-muted">{{ item.typeHint }}</div>
        <div v-if="item.crux" class="v2-crux">关键分歧点：{{ item.crux }}</div>
        <div v-if="item.evidenceQuestion" class="v2-topup">
          补证题：{{ item.evidenceQuestion }}
          <span v-if="item.topUpFacts.length"> → 补到
            <span v-for="fact in item.topUpFacts" :key="fact.id" class="v2-topup-fact">
              <el-tag size="small" :type="tierType(fact.tier)">{{ fact.id }} {{ fact.tierLabel }}</el-tag>
            </span>
          </span>
        </div>
        <div class="v2-positions">
          <div v-for="position in item.positions" :key="position.optionId" class="v2-position">
            <div class="v2-position-head">「{{ position.optionLabel }}」{{ position.members.length }} 人</div>
            <MemberReasons :members="position.members" :facts="factById" />
          </div>
        </div>
      </div>
    </section>

    <!-- 会议过程：按时间顺序，每个环节谁做了什么 -->
    <section class="v2-section">
      <div class="v2-title">会议过程</div>
      <el-timeline class="v2-timeline">
        <el-timeline-item v-for="step in view.process" :key="step.key"
          :type="step.done ? 'success' : step.running ? 'primary' : 'info'" :hollow="!step.done && !step.running">
          <div class="v2-step-title">
            {{ step.title }}
            <span v-if="step.running" class="v2-running">进行中…</span>
            <span v-else-if="!step.done" class="v2-muted">未开始</span>
          </div>
          <div v-if="step.summaryText && (step.done || step.running)" class="v2-muted">{{ step.summaryText }}</div>

          <template v-if="step.key === 'research'">
            <details v-for="member in step.members" :key="member.memberName" class="v2-process-member" :open="step.running">
              <summary><strong>{{ member.memberName }}</strong>：{{ member.summaryText }}</summary>
              <div v-for="(q, i) in member.questions" :key="i" class="v2-muted">· {{ q.question }} —— {{ q.sourceText }}</div>
            </details>
          </template>

          <template v-if="step.key === 'answers'">
            <details v-for="member in step.members" :key="member.memberName" class="v2-process-member">
              <summary>
                <strong>{{ member.memberName }}</strong>：
                <span v-if="member.skipped" class="v2-solo-error">作答无效</span>
                <template v-else>{{ member.choices.map(c => `${c.questionId} ${c.optionLabel}`).join(' · ') }}</template>
              </summary>
              <div v-if="member.skipped" class="v2-muted">{{ member.reasonText }}</div>
              <div v-for="choice in member.choices" :key="choice.questionId" class="v2-process-choice">
                <div><el-tag size="small" effect="plain">{{ choice.questionId }}</el-tag> {{ choice.title }}：<b>{{ choice.optionLabel }}</b></div>
                <div class="v2-members">
                  <div class="v2-member">
                    <template v-for="(reason, i) in choice.reasons" :key="i">{{ reason.text }}<FactRefs :ids="reason.factIds" :facts="factById" />；</template>
                    <span v-if="choice.falsifier" class="v2-muted">证伪信号：{{ choice.falsifier }}</span>
                  </div>
                </div>
              </div>
              <div v-if="member.outcomeText" class="v2-muted">结果概率：{{ member.outcomeText }}</div>
            </details>
          </template>

          <template v-if="step.key === 'debate'">
            <div v-for="(exam, i) in step.exams" :key="i" class="v2-item">
              <div class="v2-item-head">
                <el-tag size="small" effect="plain">第 {{ exam.round }} 轮</el-tag>
                <span class="v2-item-title">{{ exam.title }}</span>
                <span class="v2-muted">{{ exam.askerName }}（{{ exam.askerOption }}）质询 {{ exam.responderName }}（{{ exam.responderOption }}）</span>
              </div>
              <div v-if="exam.question" class="v2-quote"><b>{{ exam.askerName }}：</b>{{ exam.question }}</div>
              <div v-if="exam.reply" class="v2-quote reply"><b>{{ exam.responderName }}：</b>{{ exam.reply }}</div>
              <el-alert v-if="exam.failNote" :title="exam.failNote" type="info" :closable="false" show-icon />
            </div>
            <div v-if="step.redTeam" class="v2-item v2-red-team">
              <div class="v2-item-head">
                <el-tag size="small" type="danger">红队</el-tag>
                <span class="v2-item-title">攻击共识：{{ step.redTeam.targetText }}</span>
                <span class="v2-muted">由 {{ step.redTeam.memberName }} 站在反方论证</span>
              </div>
              <div v-if="step.redTeam.argument" class="v2-quote">{{ step.redTeam.argument }}</div>
              <el-alert v-if="step.redTeam.failNote" :title="step.redTeam.failNote" type="info" :closable="false" show-icon />
            </div>
          </template>

          <template v-if="step.key === 'reestimate'">
            <div v-for="member in step.members" :key="member.memberName" class="v2-change">
              <strong>{{ member.memberName }}</strong>：{{ member.changeText }}
            </div>
          </template>

          <template v-if="step.key === 'verify'">
            <details v-for="check in step.checks" :key="check.factId" class="v2-process-member">
              <summary>
                <el-tag size="small" effect="plain">{{ check.factId }}</el-tag>
                <el-tag size="small" :type="badgeType(check.statusLabel)" class="v2-badge">{{ check.statusLabel }}</el-tag>
                {{ check.question }}
              </summary>
              <div>{{ check.text }}</div>
              <div v-if="check.quote" class="v2-quote">原文<template v-if="check.publishedAt">（{{ check.publishedAt }}）</template>：“{{ check.quote }}”</div>
              <div v-if="check.sourceUrl" class="v2-muted">来源 <a :href="check.sourceUrl" target="_blank" rel="noopener noreferrer">{{ check.sourceText }}</a></div>
            </details>
          </template>
        </el-timeline-item>
      </el-timeline>

      <!-- 人为介入：排队到下一个环节开始前处理 -->
      <div v-if="view.canIntervene || view.interventions.length" class="v2-intervene">
        <div class="v2-step-title">人为介入</div>
        <div v-for="(item, i) in view.interventions" :key="i" class="v2-intervention">
          <div class="v2-item-head">
            <el-tag size="small" :type="item.applied ? 'success' : 'info'">{{ item.typeLabel }}</el-tag>
            <span>{{ item.content }}</span>
            <span class="v2-muted">{{ item.statusLabel }}</span>
          </div>
          <div v-if="item.resultText" class="v2-quote reply">{{ item.resultText }}</div>
        </div>
        <div v-if="view.canIntervene" class="v2-intervene-form">
          <el-radio-group v-model="draft.type" size="small">
            <el-radio-button value="FACT">补充资料</el-radio-button>
            <el-radio-button value="SEARCH">追加取证</el-radio-button>
            <el-radio-button value="ASK">向议员提问</el-radio-button>
          </el-radio-group>
          <el-select v-if="draft.type === 'ASK'" v-model="draft.targetMemberId" placeholder="选择议员" size="small" class="v2-intervene-target">
            <el-option v-for="member in askableMembers" :key="member.id" :label="member.memberName" :value="member.id" />
          </el-select>
          <el-input v-model="draft.content" type="textarea" :rows="2" :placeholder="placeholder" />
          <el-input v-if="draft.type === 'FACT'" v-model="draft.source" size="small" placeholder="来源（可选，如：公司公告、业绩会纪要）" />
          <div class="v2-intervene-actions">
            <el-button type="primary" size="small" :loading="interveneLoading" :disabled="!canSubmit" @click="submit">提交介入</el-button>
            <span class="v2-muted">在下一个环节开始前处理，不打断进行中的调用；你补充的资料按已核实使用，向议员提问在大家背靠背作答之后处理。</span>
          </div>
          <div v-if="interveneError" class="v2-solo-error">{{ interveneError }}</div>
        </div>
      </div>
    </section>

    <!-- 单人对照：按需生成 -->
    <section v-if="view.solo || view.canRequestSolo" class="v2-section">
      <div class="v2-title">单人对照</div>
      <template v-if="view.solo">
        <div class="v2-muted v2-caption">
          同一模型（{{ view.solo.modelType }}）、同一问题卡和会前底稿，一个人一次答完。与圆桌不同的子问题 {{ view.solo.differentCount }} 道。
        </div>
        <div v-for="row in view.solo.rows" :key="row.questionId" class="v2-solo-row">
          <div class="v2-item-head">
            <el-tag size="small" effect="plain">{{ row.questionId }}</el-tag>
            <span class="v2-item-title">{{ row.title }}</span>
            <el-tag size="small" :type="relationType(row.relation)">{{ row.relationLabel }}</el-tag>
          </div>
          <div class="v2-solo-sides">
            <span>圆桌：{{ row.roundtableText }}</span>
            <span>单人：{{ row.soloText }}</span>
          </div>
          <div v-if="row.relation !== 'same' && row.soloReasons.length" class="v2-members">
            <div class="v2-member">
              <strong>单人理由：</strong>
              <template v-for="(reason, i) in row.soloReasons" :key="i">
                {{ reason.text }}<FactRefs :ids="reason.factIds" :facts="factById" />；
              </template>
            </div>
          </div>
        </div>
        <div v-if="view.solo.outcome" class="v2-solo-row">
          结果区间：圆桌最可能「{{ view.solo.outcome.roundtableModeLabel }}」，单人最可能「{{ view.solo.outcome.soloModeLabel }}」
          <el-tag size="small" :type="view.solo.outcome.same ? 'success' : 'warning'">{{ view.solo.outcome.same ? '一致' : '不同' }}</el-tag>
        </div>
      </template>
      <template v-else>
        <div class="v2-muted v2-caption">用同一模型、同一问题卡和会前底稿让一个人一次答完，看圆桌改变了什么；到期时一起打分。约 1–2 次模型调用。</div>
        <el-button size="small" type="primary" plain :loading="soloLoading" @click="emit('request-solo')">生成单人对照</el-button>
        <div v-if="soloError" class="v2-solo-error">{{ soloError }}</div>
      </template>
    </section>

    <!-- 事实底稿与消耗 -->
    <el-collapse class="v2-collapse">
      <el-collapse-item name="facts">
        <template #title>
          事实底稿 · 已核实 {{ view.factCounts.F }} · 低置信 {{ view.factCounts.L }} · 缺口 {{ view.factCounts.G }}
        </template>
        <div v-for="fact in view.facts" :key="fact.id" class="v2-fact">
          <el-tag size="small" :type="tierType(fact.tier)" class="v2-fact-id">{{ fact.id }}</el-tag>
          <div class="v2-fact-body">
            <div>
              {{ fact.text }}
              <el-tag v-for="badge in fact.badges" :key="badge" size="small" :type="badgeType(badge)" effect="plain" class="v2-badge">{{ badge }}</el-tag>
            </div>
            <div class="v2-muted">
              <template v-if="fact.sourceUrl">来源 <a :href="fact.sourceUrl" target="_blank" rel="noopener noreferrer">{{ fact.sourceText }}</a></template>
              <template v-else-if="fact.sourceText">来源 {{ fact.sourceText }}</template>
              <template v-if="fact.note"><template v-if="fact.sourceText"> · </template>{{ fact.note }}</template>
            </div>
          </div>
        </div>
        <div v-if="view.openQuestions.length" class="v2-open">
          仍缺资料：{{ view.openQuestions.join('；') }}
        </div>
      </el-collapse-item>
      <el-collapse-item v-if="view.usage" name="usage">
        <template #title>消耗 · {{ view.usage.totalText }}</template>
        <el-table :data="view.usage.stages" size="small">
          <el-table-column prop="stageLabel" label="环节" />
          <el-table-column prop="calls" label="调用次数" width="100" />
          <el-table-column prop="tokensText" label="tokens" />
          <el-table-column prop="durationText" label="耗时" width="120" />
        </el-table>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<script setup>
import { computed, defineComponent, h, reactive, watch } from 'vue'
import { ElTooltip } from 'element-plus'

const props = defineProps({
  view: { type: Object, required: true },
  soloLoading: { type: Boolean, default: false },
  soloError: { type: String, default: '' },
  members: { type: Array, default: () => [] },
  interveneLoading: { type: Boolean, default: false },
  interveneError: { type: String, default: '' },
  // Bumped by the parent after a successful submission, so the draft is cleared only then.
  interveneVersion: { type: Number, default: 0 }
})
const emit = defineEmits(['request-solo', 'intervene'])

const draft = reactive({ type: 'FACT', content: '', source: '', targetMemberId: null })
const askableMembers = computed(() => props.members.filter(member => member.role !== 1))
const canSubmit = computed(() => draft.content.trim() && (draft.type !== 'ASK' || draft.targetMemberId))
const placeholder = computed(() => ({
  FACT: '写一条你确认可靠的资料，例如：公司三季度营收同比增长 37%',
  SEARCH: '写一个需要查证的问题，系统会走审核搜索，例如：最新季度出货同比增速是多少？',
  ASK: '写你想问这位议员的问题或质疑，他必须正面回应'
})[draft.type])

function submit() {
  const payload = { type: draft.type, content: draft.content.trim() }
  if (draft.type === 'FACT' && draft.source.trim()) payload.source = draft.source.trim()
  if (draft.type === 'ASK') payload.targetMemberId = draft.targetMemberId
  emit('intervene', payload)
}

watch(() => props.interveneVersion, () => {
  draft.content = ''
  draft.source = ''
})

function relationType(relation) {
  return ({ same: 'success', different: 'danger', disputed: 'warning', open: 'info' })[relation] || 'info'
}

const factById = computed(() => new Map(props.view.facts.map(fact => [fact.id, fact])))

function tierType(tier) {
  return ({ F: 'success', L: 'warning', G: 'info' })[tier] || 'info'
}

function badgeType(badge) {
  return ({ 低置信: 'warning', 东财单源: 'info', 补证: 'primary', 参照类: 'info', 原文核实: 'success', 原文更正: 'danger' })[badge] || 'info'
}

// Fact ids in a reason; hovering shows the fact itself so every claim can be checked against the base.
const FactRefs = defineComponent({
  props: { ids: { type: Array, default: () => [] }, facts: { type: Map, required: true } },
  setup(refProps) {
    return () => refProps.ids.map(id => {
      const fact = refProps.facts.get(id)
      return h(ElTooltip, { content: fact ? fact.text : '底稿中没有这条事实', placement: 'top' }, {
        default: () => h('span', { class: ['v2-ref', fact ? 'tier-' + fact.tier : 'missing'] }, id)
      })
    })
  }
})

const MemberReasons = defineComponent({
  props: { members: { type: Array, default: () => [] }, facts: { type: Map, required: true } },
  setup(reasonProps) {
    return () => h('div', { class: 'v2-members' }, reasonProps.members.map(member =>
      h('div', { class: 'v2-member' }, [
        h('strong', `${member.memberName}：`),
        ...member.reasons.flatMap(reason => [
          h('span', reason.text),
          h(FactRefs, { ids: reason.factIds, facts: reasonProps.facts }),
          h('span', '；')
        ]),
        member.falsifier ? h('span', { class: 'v2-muted' }, `证伪信号：${member.falsifier}`) : null
      ])
    ))
  }
})
</script>

<style scoped>
.v2-card { border-radius: 12px; }
.v2-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.v2-muted { color: #8492a6; font-size: 12px; }
.v2-skipped { margin-bottom: 12px; }
.v2-progress { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-bottom: 14px; font-size: 13px; color: #8492a6; }
.v2-step.done { color: #3a8f3a; }
.v2-section { padding: 12px 0; border-top: 1px solid #eef1f6; }
.v2-section:first-of-type { border-top: none; padding-top: 0; }
.v2-title { font-weight: 700; color: #1f2d3d; margin-bottom: 8px; }
.v2-count { display: inline-block; min-width: 18px; padding: 0 6px; margin-left: 4px; border-radius: 9px; background: #f0f2f5; color: #606266; font-size: 12px; text-align: center; }
.v2-caption { margin-bottom: 8px; }
.v2-issue { display: flex; flex-wrap: wrap; align-items: baseline; gap: 10px; margin-bottom: 6px; }
.v2-sub-questions { margin: 0; padding-left: 0; list-style: none; display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
.v2-bar-row { display: grid; grid-template-columns: 120px minmax(80px, 1fr) 56px 96px; align-items: center; gap: 8px; font-size: 13px; padding: 2px 0; }
.v2-bar-track { height: 10px; background: #f0f2f5; border-radius: 5px; overflow: hidden; }
.v2-bar-fill { display: block; height: 100%; background: #a0cfff; border-radius: 5px; }
.v2-bar-row.mode .v2-bar-fill { background: #409eff; }
.v2-bar-row.mode .v2-bar-label, .v2-bar-row.mode .v2-bar-value { font-weight: 700; }
.v2-bar-value { text-align: right; }
.v2-pending { margin-top: 8px; }
.v2-intervene { margin-top: 8px; padding-top: 10px; border-top: 1px dashed #eef1f6; display: flex; flex-direction: column; gap: 8px; }
.v2-intervention { font-size: 13px; display: flex; flex-direction: column; gap: 4px; }
.v2-intervene-form { display: flex; flex-direction: column; gap: 8px; }
.v2-intervene-target { width: 200px; }
.v2-intervene-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.v2-timeline { padding-left: 2px; margin-top: 4px; }
.v2-step-title { font-weight: 600; color: #1f2d3d; display: flex; align-items: center; gap: 8px; }
.v2-running { font-size: 12px; font-weight: 400; color: #409eff; }
.v2-process-member { font-size: 13px; margin-top: 6px; }
.v2-process-member summary { cursor: pointer; line-height: 1.7; }
.v2-process-choice { margin: 4px 0 4px 12px; }
.v2-solo-row { padding: 6px 0; font-size: 13px; display: flex; flex-direction: column; align-items: flex-start; gap: 4px; }
.v2-solo-row + .v2-solo-row { border-top: 1px dashed #eef1f6; }
.v2-solo-sides { display: flex; flex-wrap: wrap; gap: 4px 24px; }
.v2-solo-error { margin-top: 6px; font-size: 13px; color: #c45656; }
.v2-judge-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 4px; }
.v2-judge-table { margin-top: 8px; }
.v2-minority { margin-top: 10px; padding: 8px 12px; background: #fdf6ec; border-radius: 8px; font-size: 13px; }
.v2-minority-head { font-weight: 600; color: #b88230; margin-bottom: 4px; }
.v2-item { padding: 8px 0; display: flex; flex-direction: column; gap: 6px; }
.v2-item + .v2-item { border-top: 1px dashed #eef1f6; }
.v2-item-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.v2-item-title { font-weight: 600; }
.v2-thin { font-size: 13px; color: #b88230; background: #fdf6ec; border-radius: 6px; padding: 4px 10px; }
.v2-crux { font-size: 13px; background: #f4f4f5; border-radius: 6px; padding: 4px 10px; }
.v2-topup { font-size: 13px; }
.v2-topup-fact { margin-left: 4px; }
.v2-positions { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr)); gap: 10px; }
.v2-position { border: 1px solid #eef1f6; border-radius: 8px; padding: 8px 10px; }
.v2-position-head { font-weight: 600; margin-bottom: 4px; }
.v2-members { display: flex; flex-direction: column; gap: 4px; font-size: 13px; line-height: 1.6; }
.v2-quote { font-size: 13px; line-height: 1.6; padding: 6px 10px; border-left: 3px solid #a0cfff; background: #f7faff; border-radius: 0 6px 6px 0; white-space: pre-wrap; }
.v2-quote.reply { border-left-color: #c0c4cc; background: #fafafa; }
.v2-red-team .v2-quote { border-left-color: #f89898; background: #fef6f6; }
.v2-change { font-size: 13px; line-height: 1.8; }
.v2-empty, .v2-insufficient, .v2-open { font-size: 13px; color: #8492a6; }
.v2-collapse { margin-top: 8px; }
.v2-fact { display: flex; gap: 8px; padding: 6px 0; font-size: 13px; line-height: 1.5; }
.v2-fact + .v2-fact { border-top: 1px dashed #eef1f6; }
.v2-fact-id { flex-shrink: 0; min-width: 44px; justify-content: center; }
.v2-fact-body { min-width: 0; overflow-wrap: anywhere; }
.v2-badge { margin-left: 4px; }
:deep(.v2-ref) { display: inline-block; margin: 0 2px; padding: 0 4px; border-radius: 4px; font-size: 11px; line-height: 16px; cursor: help; background: #f0f9eb; color: #529b2e; }
:deep(.v2-ref.tier-L) { background: #fdf6ec; color: #b88230; }
:deep(.v2-ref.tier-G), :deep(.v2-ref.missing) { background: #f4f4f5; color: #909399; }
@media (max-width: 640px) {
  .v2-bar-row { grid-template-columns: 96px minmax(60px, 1fr) 48px; }
  .v2-bar-range { display: none; }
}
</style>
