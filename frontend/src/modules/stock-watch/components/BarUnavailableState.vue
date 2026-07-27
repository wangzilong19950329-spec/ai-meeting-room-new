<template>
  <div class="bar-unavailable-state">
    <strong>{{ title }}</strong>
    <span>{{ message }}</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  state: {
    type: String,
    default: 'UNAVAILABLE'
  },
  message: {
    type: String,
    default: '尚未获取真实分钟行情'
  }
})

const title = computed(() => {
  if (props.state === 'LOADING') return '分钟行情加载中'
  if (props.state === 'ERROR') return '分钟行情加载失败'
  if (props.state === 'INVALID') return '分钟行情质量无效'
  if (props.state === 'EMPTY') return '分钟行情为空'
  if (props.state === 'DEGRADED') return '分钟行情质量降级'
  return '分钟行情不可用'
})
</script>

<style scoped>
.bar-unavailable-state {
  min-height: 280px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 8px;
  border: 1px dashed #cbd5e1;
  background: #f8fafc;
  color: #475569;
}

strong {
  color: #0f172a;
  font-size: 16px;
}

span {
  font-size: 13px;
}
</style>
