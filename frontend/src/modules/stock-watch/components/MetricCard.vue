<template>
  <article class="metric-card">
    <span>{{ metric.label }}</span>
    <strong v-if="metric.status === 'VALID'" :class="metric.tone">{{ formatMetric(metric) }}</strong>
    <strong v-else-if="metric.status === 'STALE'">数据已过期</strong>
    <strong v-else-if="metric.status === 'INVALID'">数据无效</strong>
    <strong v-else>未计算</strong>
    <small>{{ metric.reason || metric.source || '' }}</small>
  </article>
</template>

<script setup>
defineProps({
  metric: {
    type: Object,
    required: true
  }
})

function formatMetric(metric) {
  const unit = metric.unit || ''
  return `${metric.value ?? ''}${unit}`
}
</script>

<style scoped>
.metric-card {
  padding: 14px;
  border: 1px solid #e2e8f0;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

span {
  font-size: 12px;
  color: #64748b;
}

strong {
  font-size: 16px;
  color: #0f172a;
}

strong.red {
  color: #dc2626;
}

strong.green {
  color: #16a34a;
}

strong.blue {
  color: #2563eb;
}

strong.amber {
  color: #d97706;
}

strong.purple {
  color: #7c3aed;
}

small {
  min-height: 16px;
  font-size: 12px;
  color: #64748b;
}
</style>
