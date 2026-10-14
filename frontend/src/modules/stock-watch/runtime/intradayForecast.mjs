const statuses = new Set(['PREDICTED', 'ABSTAIN', 'MISSING', 'INVALID', 'INVALID_REQUEST', 'INPUT_UNAVAILABLE', 'FAILED'])
const labels = {
  ABSTAIN: '不明确', MISSING: '未返回', INVALID: '字段无效', INVALID_REQUEST: '请求无效',
  INPUT_UNAVAILABLE: '数据不可用', FAILED: '分析未完成'
}
const timeFormat = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false })

export function formatIntradayForecast(raw, now = Date.now()) {
  if (!raw || raw.version !== 'M30_MVP_V1' || !statuses.has(raw.status)) return null
  const start = Date.parse(raw.asOfAt)
  const target = Date.parse(raw.targetAt)
  const validWindow = Number.isFinite(start) && Number.isFinite(target) && target - start === 1800000
  const numeric = (value) => typeof value === 'number' && Number.isFinite(value) && value > 0
  const predicted = raw.status === 'PREDICTED' && ['UP', 'DOWN'].includes(raw.direction)
    && numeric(raw.targetLow) && numeric(raw.targetHigh) && raw.targetLow <= raw.targetHigh
  const direction = predicted ? (raw.direction === 'UP' ? '看涨' : '看跌')
    : raw.status === 'PREDICTED' ? '字段无效' : labels[raw.status]
  let timing = '时效未知'
  let active = false
  if (validWindow && now >= target) timing = '已到期'
  else if (validWindow && now < start) timing = '尚未开始'
  else if (raw.timingStatus === 'LATE') timing = '入库迟到'
  else if (raw.timingStatus === 'INVALID') timing = '时间无效'
  else if (validWindow && predicted && raw.timingStatus === 'TIMELY_PROXY') {
    timing = `剩余 ${Math.ceil((target - now) / 60000)} 分钟`
    active = true
  } else if (validWindow && raw.timingStatus === 'TIMELY_PROXY') timing = '无有效预测'
  return {
    direction, timing, active,
    tone: active ? (raw.direction === 'DOWN' ? 'down' : 'up') : 'muted',
    window: validWindow ? `${timeFormat.format(start)}–${timeFormat.format(target)}` : '',
    range: predicted ? `到期区间 ${raw.targetLow.toFixed(2)}–${raw.targetHigh.toFixed(2)}` : ''
  }
}
