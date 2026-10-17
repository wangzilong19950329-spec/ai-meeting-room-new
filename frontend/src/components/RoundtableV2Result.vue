<template>
  <el-card shadow="never" class="v2-card">
    <template #header>
      <div class="v2-head">
        <span>🧭 圆桌研判结果</span>
        <span v-if="view.stats" class="v2-muted">
          {{ view.stats.answeredCount }} 人有效作答<template v-if="view.stats.skippedCount"> · {{ view.stats.skippedCount }} 人作答无效</template>
          · 交锋 {{ view.stats.loops }} 轮
        </span>
      </div>
    </template>

    <!-- 进度：会议进行中才显示 -->
    <div v-if="!view.finished" class="v2-progress">
      <span v-for="step in view.progress" :key="step.label" class="v2-step" :class="{ done: step.done }">
        {{ step.done ? '✓' : '○' }} {{ step.label }}<small v-if="step.detail"> · {{ step.detail }}</small>
      </span>
    </div>

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
    </section>

    <!-- 结论清单 -->
    <section v-if="view.finished" class="v2-section">
      <div class="v2-title">结论清单 <span class="v2-count">{{ view.conclusions.length }}</span></div>
      <div v-if="!view.conclusions.length" class="v2-empty">没有达到共识门槛的结论</div>
      <div v-for="item in view.conclusions" :key="item.questionId" class="v2-item">
        <div class="v2-item-head">
          <el-tag size="small" effect="plain">{{ item.questionId }}</el-tag>
          <span class="v2-item-title">{{ item.title }}</span>
          <el-tag size="small" type="success">{{ item.optionLabel }}</el-tag>
          <span class="v2-muted">{{ item.supportText }}</span>
          <el-tag v-if="item.resolvedAfterDispute" size="small" type="warning" effect="plain">
            首轮有分歧，交锋或补证后形成共识<template v-if="item.typeLabel">（原为{{ item.typeLabel }}）</template>
          </el-tag>
        </div>
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

    <!-- 交锋与红队 -->
    <section v-if="view.crossExams.length || view.redTeam" class="v2-section">
      <div class="v2-title">交锋记录</div>
      <div v-for="(exam, i) in view.crossExams" :key="i" class="v2-item">
        <div class="v2-item-head">
          <el-tag size="small" effect="plain">第 {{ exam.round }} 轮</el-tag>
          <span class="v2-item-title">{{ exam.title }}</span>
          <span class="v2-muted">{{ exam.askerName }}（{{ exam.askerOption }}）质询 {{ exam.responderName }}（{{ exam.responderOption }}）</span>
        </div>
        <div v-if="exam.question" class="v2-quote"><b>{{ exam.askerName }}：</b>{{ exam.question }}</div>
        <div v-if="exam.reply" class="v2-quote reply"><b>{{ exam.responderName }}：</b>{{ exam.reply }}</div>
        <el-alert v-if="exam.failNote" :title="exam.failNote" type="info" :closable="false" show-icon />
      </div>
      <div v-if="view.redTeam" class="v2-item v2-red-team">
        <div class="v2-item-head">
          <el-tag size="small" type="danger">红队</el-tag>
          <span class="v2-item-title">攻击共识：{{ view.redTeam.targetText }}</span>
          <span class="v2-muted">由 {{ view.redTeam.memberName }} 站在反方论证</span>
        </div>
        <div v-if="view.redTeam.argument" class="v2-quote">{{ view.redTeam.argument }}</div>
        <el-alert v-if="view.redTeam.failNote" :title="view.redTeam.failNote" type="info" :closable="false" show-icon />
      </div>
    </section>

    <!-- 立场变化 -->
    <section v-if="view.finished" class="v2-section">
      <div class="v2-title">匿名再估后的立场变化</div>
      <div v-if="!view.changes.length" class="v2-empty">再估后没有人改变立场</div>
      <div v-for="(change, i) in view.changes" :key="i" class="v2-change">
        <strong>{{ change.memberName }}</strong> 在「{{ change.questionTitle }}」上
        从「{{ change.fromLabel }}」改为「{{ change.toLabel }}」<span v-if="change.reason">：{{ change.reason }}</span>
      </div>
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
import { computed, defineComponent, h } from 'vue'
import { ElTooltip } from 'element-plus'

const props = defineProps({
  view: { type: Object, required: true }
})

const factById = computed(() => new Map(props.view.facts.map(fact => [fact.id, fact])))

function tierType(tier) {
  return ({ F: 'success', L: 'warning', G: 'info' })[tier] || 'info'
}

function badgeType(badge) {
  return ({ 低置信: 'warning', 东财单源: 'info', 补证: 'primary', 参照类: 'info' })[badge] || 'info'
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
.v2-minority { margin-top: 10px; padding: 8px 12px; background: #fdf6ec; border-radius: 8px; font-size: 13px; }
.v2-minority-head { font-weight: 600; color: #b88230; margin-bottom: 4px; }
.v2-item { padding: 8px 0; display: flex; flex-direction: column; gap: 6px; }
.v2-item + .v2-item { border-top: 1px dashed #eef1f6; }
.v2-item-head { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.v2-item-title { font-weight: 600; }
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
