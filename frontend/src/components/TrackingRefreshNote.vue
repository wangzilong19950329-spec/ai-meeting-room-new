<template>
  <div class="refresh-note">
    <template v-if="parsed">
      <div v-if="parsed.query" class="rn-query">
        <span class="rn-query-label">{{ parsed.queryKind }}</span>
        <span class="rn-query-text">{{ parsed.query }}</span>
      </div>
      <div v-if="chips.length" class="rn-chips">
        <span v-for="c in chips" :key="c.label" class="rn-chip" :class="c.cls">
          <i class="rn-chip-label">{{ c.label }}</i>{{ c.value }}
        </span>
      </div>
      <div v-if="parsed.freshness" class="rn-field">
        <span class="rn-field-label">时效</span>
        <span class="rn-field-text">{{ parsed.freshness }}</span>
      </div>
      <div v-if="parsed.summary" class="rn-field rn-summary">
        <span class="rn-field-label">摘要</span>
        <span class="rn-field-text">{{ parsed.summary }}</span>
      </div>
    </template>
    <div v-else class="rn-raw">{{ text }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  text: { type: String, default: '' }
})

const LABELS = [
  { key: 'query', patterns: ['自动刷新查询：', '手动刷新查询：'] },
  { key: 'severity', patterns: ['变化等级：'] },
  { key: 'provider', patterns: ['搜索源：'] },
  { key: 'cross', patterns: ['交叉验证：'] },
  { key: 'freshness', patterns: ['时效判断：'] },
  { key: 'summary', patterns: ['摘要：'] }
]

const parsed = computed(() => {
  const text = props.text || ''
  if (!text.trim()) return null
  const found = []
  for (const { key, patterns } of LABELS) {
    for (const p of patterns) {
      const idx = text.indexOf(p)
      if (idx >= 0) {
        found.push({ key, idx, len: p.length, label: p })
        break
      }
    }
  }
  // 至少要命中两个字段才认为是结构化结果，否则原样展示。
  if (found.length < 2) return null
  found.sort((a, b) => a.idx - b.idx)
  const out = {}
  for (let i = 0; i < found.length; i++) {
    const cur = found[i]
    const end = i + 1 < found.length ? found[i + 1].idx : text.length
    let val = text.slice(cur.idx + cur.len, end).trim()
    if (cur.key !== 'summary') val = val.replace(/。+$/, '')
    out[cur.key] = val
    if (cur.key === 'query') out.queryKind = cur.label.replace('：', '')
  }
  // 无摘要、仅返回“本次未检索到有效结果”兜底句时，从上一字段里拆出来单独显示。
  if (!out.summary) {
    const noResult = '本次未检索到有效结果'
    for (const key of ['freshness', 'cross', 'provider', 'severity']) {
      if (out[key] && out[key].includes(noResult)) {
        out[key] = out[key].replace(/。?本次未检索到有效结果。?$/, '').replace(/。+$/, '')
        if (!out[key]) delete out[key]
        out.summary = '本次未检索到有效结果。'
        break
      }
    }
  }
  return out
})

const chips = computed(() => {
  const p = parsed.value
  if (!p) return []
  const list = []
  if (p.severity) {
    list.push({ label: '变化', value: p.severity, cls: severityCls(p.severity) })
  }
  if (p.provider) {
    list.push({ label: '搜索源', value: p.provider, cls: 'rn-chip-neutral' })
  }
  if (p.cross) {
    list.push({ label: '交叉验证', value: p.cross, cls: crossCls(p.cross) })
  }
  return list
})

function severityCls(value) {
  const v = String(value || '').toUpperCase()
  if (v === 'MATERIAL') return 'rn-chip-danger'
  if (v === 'MINOR') return 'rn-chip-warning'
  return 'rn-chip-neutral'
}

function crossCls(value) {
  const v = String(value || '').toLowerCase()
  if (v.includes('strong')) return 'rn-chip-success'
  if (v.includes('weak') || v.includes('medium')) return 'rn-chip-warning'
  return 'rn-chip-neutral'
}
</script>

<style scoped>
.refresh-note { display: flex; flex-direction: column; gap: 10px; }
.rn-query { display: flex; gap: 8px; align-items: baseline; font-size: 13px; line-height: 1.6; }
.rn-query-label {
  flex-shrink: 0; font-size: 11px; font-weight: 700; color: #94a3b8;
  padding: 1px 6px; border-radius: 6px; background: #f1f5f9;
}
.rn-query-text { color: #475569; word-break: break-word; }
.rn-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.rn-chip {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 9px; border-radius: 999px; font-size: 12px; font-weight: 600;
  border: 1px solid transparent;
}
.rn-chip-label { font-style: normal; font-weight: 500; opacity: .7; }
.rn-chip-danger { background: #fef2f2; color: #b91c1c; border-color: #fecaca; }
.rn-chip-warning { background: #fff7ed; color: #c2410c; border-color: #fed7aa; }
.rn-chip-success { background: #f0fdf4; color: #15803d; border-color: #bbf7d0; }
.rn-chip-neutral { background: #f1f5f9; color: #475569; border-color: #e2e8f0; }
.rn-field { display: flex; gap: 10px; align-items: baseline; line-height: 1.75; }
.rn-field-label {
  flex-shrink: 0; width: 36px; font-size: 12px; font-weight: 700; color: #64748b;
}
.rn-field-text { color: #334155; font-size: 13px; word-break: break-word; }
.rn-summary .rn-field-text { color: #1e293b; }
.rn-raw { color: #334155; line-height: 1.75; word-break: break-word; }
</style>
