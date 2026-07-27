<template>
  <div class="intraday-bar-chart">
    <svg viewBox="0 0 760 280" role="img" aria-label="真实分钟 OHLC 行情">
      <line x1="48" y1="44" x2="718" y2="44" class="grid-line" />
      <line x1="48" y1="96" x2="718" y2="96" class="grid-line" />
      <line x1="48" y1="148" x2="718" y2="148" class="grid-line" />
      <line x1="48" y1="200" x2="718" y2="200" class="grid-line" />
      <line x1="48" y1="226" x2="718" y2="226" class="axis-line" />

      <g v-for="bar in drawableBars" :key="bar.key" :class="['bar-candle', bar.close >= bar.open ? 'up' : 'down']">
        <line :x1="bar.x" :x2="bar.x" :y1="bar.highY" :y2="bar.lowY" />
        <rect :x="bar.bodyX" :y="bar.bodyY" :width="bar.bodyWidth" :height="bar.bodyHeight" rx="1" />
      </g>

      <text x="48" y="260" class="axis-text">{{ firstTimeLabel }}</text>
      <text x="348" y="260" class="axis-text">{{ middleTimeLabel }}</text>
      <text x="678" y="260" class="axis-text">{{ lastTimeLabel }}</text>
    </svg>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  bars: {
    type: Array,
    default: () => []
  }
})

const validBars = computed(() => props.bars.filter((bar) => (
  bar?.qualityStatus === 'VALID'
  && Number.isFinite(Number(bar.open))
  && Number.isFinite(Number(bar.high))
  && Number.isFinite(Number(bar.low))
  && Number.isFinite(Number(bar.close))
)))

const priceRange = computed(() => {
  const values = validBars.value.flatMap((bar) => [Number(bar.high), Number(bar.low)])
  const min = Math.min(...values)
  const max = Math.max(...values)
  const padding = Math.max((max - min) * 0.08, 0.01)
  return { min: min - padding, max: max + padding }
})

const drawableBars = computed(() => {
  const bars = validBars.value
  const width = bars.length <= 1 ? 8 : Math.max(3, Math.min(12, 620 / bars.length * 0.52))
  return bars.map((bar, index) => {
    const open = Number(bar.open)
    const high = Number(bar.high)
    const low = Number(bar.low)
    const close = Number(bar.close)
    const x = pointX(index, bars.length)
    const openY = pointY(open)
    const closeY = pointY(close)
    return {
      key: `${bar.startTime || index}-${index}`,
      open,
      close,
      x,
      highY: pointY(high),
      lowY: pointY(low),
      bodyX: x - width / 2,
      bodyY: Math.min(openY, closeY),
      bodyWidth: width,
      bodyHeight: Math.max(2, Math.abs(openY - closeY))
    }
  })
})

const firstTimeLabel = computed(() => timeLabel(validBars.value[0]))
const middleTimeLabel = computed(() => timeLabel(validBars.value[Math.floor(validBars.value.length / 2)]))
const lastTimeLabel = computed(() => timeLabel(validBars.value[validBars.value.length - 1]))

function pointX(index, count) {
  if (count <= 1) return 382
  return 56 + (index / (count - 1)) * 640
}

function pointY(value) {
  const { min, max } = priceRange.value
  if (!Number.isFinite(min) || !Number.isFinite(max) || max === min) return 140
  return 226 - ((Number(value) - min) / (max - min)) * 182
}

function timeLabel(bar) {
  const raw = String(bar?.startTime || '')
  const match = raw.match(/(\d{2}:\d{2})/)
  return match ? match[1] : '--'
}
</script>

<style scoped>
.intraday-bar-chart {
  width: 100%;
}

svg {
  width: 100%;
  display: block;
}

.grid-line {
  stroke: #e2e8f0;
  stroke-width: 1;
}

.axis-line {
  stroke: #94a3b8;
  stroke-width: 1;
}

.axis-text {
  fill: #64748b;
  font-size: 12px;
}

.bar-candle line {
  stroke-width: 1.5;
}

.bar-candle rect {
  stroke-width: 1;
}

.bar-candle.up line,
.bar-candle.up rect {
  stroke: #dc2626;
  fill: #fee2e2;
}

.bar-candle.down line,
.bar-candle.down rect {
  stroke: #16a34a;
  fill: #dcfce7;
}
</style>
