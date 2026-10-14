<template>
  <div v-if="display" :class="['m30-forecast-line', display.tone]">
    <strong>30分钟预测</strong>
    <span>{{ display.direction }}</span>
    <span v-if="display.window">{{ display.window }}</span>
    <span v-if="display.range">{{ display.range }}</span>
    <small>{{ display.timing }}</small>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { formatIntradayForecast } from '../runtime/intradayForecast.mjs'

const props = defineProps({ forecast: { type: Object, default: null } })
const now = ref(Date.now())
const display = computed(() => formatIntradayForecast(props.forecast, now.value))
let timer
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 10000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.m30-forecast-line {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 14px;
  padding: 10px 0;
  border-top: 1px solid #e5e7eb;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.m30-forecast-line strong { font-size: 13px; color: #303742; }
.m30-forecast-line small { font-size: 12px; }
.m30-forecast-line.down { color: #a6383e; }
.m30-forecast-line.up { color: #25735a; }
.m30-forecast-line.muted { color: #727984; }
</style>
