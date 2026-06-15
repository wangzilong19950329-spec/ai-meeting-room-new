<template>
  <div class="wf-canvas-wrap">
    <div class="wf-canvas-toolbar">
      <div class="wf-legend">
        <span class="wf-legend-item"><i class="dot status-active"></i>执行中</span>
        <span class="wf-legend-item"><i class="dot status-completed"></i>已完成</span>
        <span class="wf-legend-item"><i class="dot status-blocked"></i>阻塞</span>
        <span class="wf-legend-item"><i class="dot status-rejected"></i>打回</span>
        <span class="wf-legend-item"><i class="dot status-pending"></i>待执行</span>
      </div>
      <div class="wf-zoom">
        <el-button size="small" circle plain @click="zoomOut">-</el-button>
        <span class="wf-zoom-val">{{ Math.round(zoom * 100) }}%</span>
        <el-button size="small" circle plain @click="zoomIn">+</el-button>
        <el-button size="small" plain @click="fitToWidth">适配</el-button>
      </div>
    </div>
    <div ref="viewportRef" class="wf-canvas-viewport">
      <div
        class="wf-canvas-board"
        :style="{ width: `${geo.width * zoom}px`, height: `${geo.height * zoom}px` }"
      >
        <div
          class="wf-canvas-scale"
          :style="{ width: `${geo.width}px`, height: `${geo.height}px`, transform: `scale(${zoom})` }"
        >
          <div class="wf-lanes">
            <div
              v-for="lane in geo.lanes"
              :key="lane.uid"
              class="wf-lane"
              :class="`lane-${lane.tone || 'generic'}`"
              :style="{ left: `${lane.x}px`, top: `${lane.y}px`, width: `${lane.width}px`, height: `${lane.height}px` }"
            >
              <div class="wf-lane-label">{{ lane.label }}</div>
            </div>
          </div>

          <svg class="wf-lines" :viewBox="`0 0 ${geo.width} ${geo.height}`" preserveAspectRatio="none">
            <defs>
              <marker id="wf-arrow-pass" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#409eff" />
              </marker>
              <marker id="wf-arrow-reject" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#f56c6c" />
              </marker>
              <marker id="wf-arrow-reopen" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#e6a23c" />
              </marker>
            </defs>
            <g v-for="line in flowLines" :key="line.id">
              <path
                :d="line.path"
                :class="['wf-line', `wf-line-${line.kind}`, { 'wf-line-rejected': line.rejected }]"
                :marker-end="`url(#wf-arrow-${line.kind})`"
              />
              <text :x="line.labelX" :y="line.labelY" class="wf-line-label">{{ line.label }}</text>
            </g>
          </svg>

          <div
            v-for="stage in geo.stages"
            :key="stage.stageKey"
            class="wf-node"
            :class="[stage.toneClass, stage.statusClass]"
            :style="{ left: `${stage.x}px`, top: `${stage.y}px`, width: `${stage.width}px`, minHeight: `${stage.height}px` }"
            @click="$emit('node-click', stage.node)"
          >
            <div class="wf-node-head">
              <div class="wf-node-titles">
                <div class="wf-node-title">{{ stage.stageName }}</div>
                <div class="wf-node-key">{{ stage.stageKey }}</div>
              </div>
              <span class="wf-node-status">{{ stage.statusLabel }}</span>
            </div>
            <div class="wf-node-body">
              <div class="wf-node-meta">{{ actorTypeLabel(stage.node.actorType) }}</div>
              <div v-if="stage.node.actorBindingMode === 'BY_SLOT' && stage.node.actorSlotKey" class="wf-node-meta wf-node-skill">
                槽位 · {{ stage.node.actorSlotKey }}
              </div>
              <div v-if="stage.node.skillKey" class="wf-node-meta wf-node-skill">Skill · {{ stage.node.skillKey }}</div>
              <div v-if="stage.node.searchStrategyLabel" class="wf-node-meta">{{ stage.node.searchStrategyLabel }}</div>
            </div>
            <div class="wf-node-foot">
              <span class="wf-node-attempts">{{ stage.attempts }} 次执行</span>
              <span v-if="stage.node.searchResultCount != null" class="wf-node-search">结果 {{ stage.node.searchResultCount }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'

const props = defineProps({
  layoutConfig: { type: String, default: '' },
  nodes: { type: Array, default: () => [] },
  currentStageKey: { type: String, default: '' }
})
defineEmits(['node-click'])

const viewportRef = ref()
const zoom = ref(1)
const MIN_ZOOM = 0.4
const MAX_ZOOM = 1.6

function parseLayout(raw) {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

function stageToneClass(type) {
  if (['DEBATE', 'ANALYSIS'].includes(type)) return 'tone-analysis'
  if (['REVIEW', 'APPROVAL'].includes(type)) return 'tone-review'
  if (type === 'TRACKING') return 'tone-tracking'
  return 'tone-generic'
}

function actorTypeLabel(type) {
  return ({
    ALL_MEMBERS: '全体议员',
    ANALYST: '分析员',
    REVIEWER: '复核员',
    APPROVER: '审批人',
    HUMAN: '人工',
    SYSTEM: '系统'
  })[type] || type || '-'
}

function effectiveStatus(node) {
  const s = node.latestStatus
  if (s === 'COMPLETED') return 'completed'
  if (s === 'BLOCKED') return 'blocked'
  if (s === 'REJECTED') return 'rejected'
  if (node.activeNode || node.stageKey === props.currentStageKey) return 'active'
  return 'pending'
}

const STATUS_LABEL = {
  active: '执行中',
  completed: '已完成',
  blocked: '阻塞',
  rejected: '打回',
  pending: '待执行'
}

const geo = computed(() => {
  const layout = parseLayout(props.layoutConfig) || {}
  const laneList = Array.isArray(layout.lanes) ? layout.lanes : []
  const stageLayouts = new Map((layout.stageLayouts || []).map(item => [item.stageKey, item]))

  const lanes = laneList.map((lane, idx) => ({
    uid: lane.uid || lane.key || `lane-${idx}`,
    label: lane.label || `分组 ${idx + 1}`,
    tone: lane.tone || 'generic',
    x: Number.isFinite(lane.x) ? lane.x : 40 + idx * 320,
    y: Number.isFinite(lane.y) ? lane.y : 20,
    width: Number.isFinite(lane.width) ? lane.width : 320,
    height: Number.isFinite(lane.height) ? lane.height : 640
  }))

  const stages = props.nodes.map((node, idx) => {
    const sl = stageLayouts.get(node.stageKey) || {}
    const status = effectiveStatus(node)
    return {
      stageKey: node.stageKey,
      stageName: node.stageName || `节点 ${idx + 1}`,
      node,
      x: Number.isFinite(sl.x) ? sl.x : 80 + (idx % 3) * 300,
      y: Number.isFinite(sl.y) ? sl.y : 100 + Math.floor(idx / 3) * 220,
      width: Number.isFinite(sl.width) ? sl.width : 260,
      height: Number.isFinite(sl.height) ? sl.height : 160,
      toneClass: stageToneClass(node.stageType),
      statusClass: `status-${status}`,
      statusLabel: node.latestStatusName || STATUS_LABEL[status],
      attempts: node.attempts?.length || node.latestAttemptNo || 0
    }
  })

  let width = 600
  let height = 400
  for (const lane of lanes) {
    width = Math.max(width, lane.x + lane.width + 40)
    height = Math.max(height, lane.y + lane.height + 40)
  }
  for (const stage of stages) {
    width = Math.max(width, stage.x + stage.width + 40)
    height = Math.max(height, stage.y + stage.height + 40)
  }
  return { lanes, stages, width, height }
})

const flowLines = computed(() => {
  const lines = []
  const byKey = new Map(geo.value.stages.map(s => [s.stageKey, s]))
  for (const stage of geo.value.stages) {
    const transitions = [
      { field: 'passTarget', kind: 'pass', label: '通过' },
      { field: 'rejectTarget', kind: 'reject', label: '打回' },
      { field: 'reopenTarget', kind: 'reopen', label: '重开' }
    ]
    for (const t of transitions) {
      const targetKey = stage.node[t.field]
      if (!targetKey) continue
      const target = byKey.get(targetKey)
      if (!target) continue
      const startX = stage.x + stage.width
      const startY = stage.y + stage.height / 2
      const endX = target.x
      const endY = target.y + target.height / 2
      const midX = (startX + endX) / 2
      lines.push({
        id: `${stage.stageKey}-${t.field}-${targetKey}`,
        kind: t.kind,
        label: t.label,
        rejected: t.kind === 'reject' && stage.statusClass === 'status-rejected',
        path: `M ${startX} ${startY} C ${midX} ${startY}, ${midX} ${endY}, ${endX} ${endY}`,
        labelX: midX,
        labelY: (startY + endY) / 2 - 8
      })
    }
  }
  return lines
})

function zoomIn() {
  zoom.value = Math.min(MAX_ZOOM, Math.round((zoom.value + 0.1) * 10) / 10)
}
function zoomOut() {
  zoom.value = Math.max(MIN_ZOOM, Math.round((zoom.value - 0.1) * 10) / 10)
}
function fitToWidth() {
  const vp = viewportRef.value
  if (!vp || !geo.value.width) return
  const avail = vp.clientWidth - 24
  const next = Math.min(1, avail / geo.value.width)
  zoom.value = Math.max(MIN_ZOOM, Math.round(next * 100) / 100)
}

onMounted(() => fitToWidth())
</script>

<style scoped>
.wf-canvas-wrap { border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; background: #fff; }
.wf-canvas-toolbar {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 8px 12px; border-bottom: 1px solid #eef2f7; background: #f8fafc; flex-wrap: wrap;
}
.wf-legend { display: flex; gap: 14px; flex-wrap: wrap; }
.wf-legend-item { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: #475569; }
.wf-legend-item .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.dot.status-active { background: #409eff; }
.dot.status-completed { background: #22c55e; }
.dot.status-blocked { background: #f59e0b; }
.dot.status-rejected { background: #f56c6c; }
.dot.status-pending { background: #cbd5e1; }
.wf-zoom { display: flex; align-items: center; gap: 8px; }
.wf-zoom-val { font-size: 12px; color: #64748b; min-width: 38px; text-align: center; }

.wf-canvas-viewport { position: relative; max-height: 640px; overflow: auto; }
.wf-canvas-board {
  position: relative;
  background-image:
    linear-gradient(rgba(148, 163, 184, .1) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, .1) 1px, transparent 1px);
  background-size: 28px 28px;
}
.wf-canvas-scale { position: absolute; top: 0; left: 0; transform-origin: top left; }

.wf-lanes { position: absolute; inset: 0; pointer-events: none; }
.wf-lane { position: absolute; border-radius: 24px; border: 1px dashed rgba(148, 163, 184, .28); }
.wf-lane.lane-analysis { background: linear-gradient(180deg, rgba(59, 130, 246, .07), rgba(59, 130, 246, .02)); }
.wf-lane.lane-review { background: linear-gradient(180deg, rgba(168, 85, 247, .07), rgba(168, 85, 247, .02)); }
.wf-lane.lane-tracking { background: linear-gradient(180deg, rgba(245, 158, 11, .08), rgba(245, 158, 11, .02)); }
.wf-lane.lane-generic { background: linear-gradient(180deg, rgba(100, 116, 139, .09), rgba(100, 116, 139, .02)); }
.wf-lane-label {
  position: absolute; left: 16px; top: 14px; font-size: 12px; font-weight: 700; color: #475569;
  padding: 4px 10px; border-radius: 999px; background: rgba(255, 255, 255, .72); border: 1px solid rgba(255, 255, 255, .75);
}

.wf-lines { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.wf-line { fill: none; stroke-width: 2.5; }
.wf-line-pass { stroke: #409eff; }
.wf-line-reject { stroke: #f56c6c; stroke-dasharray: 6 4; }
.wf-line-reopen { stroke: #e6a23c; stroke-dasharray: 3 3; }
.wf-line-rejected { stroke-width: 3.5; filter: drop-shadow(0 0 3px rgba(245, 108, 108, .5)); }
.wf-line-label { font-size: 12px; fill: #334155; font-weight: 700; }

.wf-node {
  position: absolute; width: 260px; min-height: 160px; border-radius: 14px;
  border: 1px solid #dbe3ee; background: rgba(255, 255, 255, .98);
  box-shadow: 0 12px 24px rgba(15, 23, 42, .08); cursor: pointer;
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease;
}
.wf-node:hover { transform: translateY(-1px); box-shadow: 0 16px 28px rgba(15, 23, 42, .12); }
.wf-node.tone-analysis { border-top: 4px solid #3b82f6; }
.wf-node.tone-review { border-top: 4px solid #a855f7; }
.wf-node.tone-tracking { border-top: 4px solid #f59e0b; }
.wf-node.tone-generic { border-top: 4px solid #64748b; }

.wf-node.status-active { border-color: #409eff; box-shadow: 0 0 0 3px rgba(64, 158, 255, .18), 0 18px 30px rgba(15, 23, 42, .12); animation: wf-pulse 1.6s ease-in-out infinite; z-index: 130; }
.wf-node.status-completed { border-color: #86efac; }
.wf-node.status-blocked { border-color: #fcd34d; box-shadow: 0 0 0 3px rgba(245, 158, 11, .14); }
.wf-node.status-rejected { border-color: #fca5a5; box-shadow: 0 0 0 3px rgba(245, 108, 108, .14); }
.wf-node.status-pending { opacity: .72; }

@keyframes wf-pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(64, 158, 255, .18), 0 18px 30px rgba(15, 23, 42, .12); }
  50% { box-shadow: 0 0 0 7px rgba(64, 158, 255, .08), 0 18px 30px rgba(15, 23, 42, .12); }
}

.wf-node-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 8px;
  padding: 12px 12px 8px; border-radius: 14px 14px 0 0;
  background: linear-gradient(180deg, rgba(248, 250, 252, .92), rgba(248, 250, 252, .55));
}
.wf-node-titles { min-width: 0; }
.wf-node-title { font-size: 14px; font-weight: 700; color: #1f2937; line-height: 1.4; }
.wf-node-key { margin-top: 4px; font-size: 11px; color: #94a3b8; }
.wf-node-status {
  flex-shrink: 0; font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 999px;
  background: #eef2f7; color: #475569;
}
.status-active .wf-node-status { background: rgba(64, 158, 255, .14); color: #1d4ed8; }
.status-completed .wf-node-status { background: rgba(34, 197, 94, .14); color: #15803d; }
.status-blocked .wf-node-status { background: rgba(245, 158, 11, .16); color: #b45309; }
.status-rejected .wf-node-status { background: rgba(245, 108, 108, .16); color: #b91c1c; }

.wf-node-body { padding: 8px 12px; display: flex; flex-direction: column; gap: 6px; }
.wf-node-meta { font-size: 12px; color: #475569; }
.wf-node-skill { color: #2563eb; font-weight: 600; }
.wf-node-foot {
  padding: 8px 12px 12px; border-top: 1px dashed #e2e8f0; display: flex; gap: 12px;
  font-size: 12px; color: #64748b;
}
.wf-node-attempts { font-weight: 600; color: #475569; }
</style>
