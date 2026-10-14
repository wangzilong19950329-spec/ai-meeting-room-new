import test from 'node:test'
import assert from 'node:assert/strict'
import { formatIntradayForecast } from './intradayForecast.mjs'

const start = Date.parse('2026-10-08T02:00:00Z')
const forecast = {
  version: 'M30_MVP_V1', status: 'PREDICTED', direction: 'DOWN',
  asOfAt: new Date(start).toISOString(), targetAt: new Date(start + 1800000).toISOString(),
  targetLow: 9.8, targetHigh: 9.95, timingStatus: 'TIMELY_PROXY'
}

test('fixed window counts down and expires at target, not 30 minutes after rendering', () => {
  assert.equal(formatIntradayForecast(forecast, start + 180000).timing, '剩余 27 分钟')
  assert.equal(formatIntradayForecast(forecast, start + 1800000).timing, '已到期')
  assert.equal(formatIntradayForecast(forecast, start + 1800000).active, false)
  assert.equal(formatIntradayForecast(forecast, start + 180000).window, '10:00–10:30')
})

test('legacy records are unchanged and malformed optional data is not actionable', () => {
  assert.equal(formatIntradayForecast(null), null)
  assert.equal(formatIntradayForecast({ status: 'UNKNOWN' }), null)
  for (const invalid of [true, '9.8', Infinity, NaN, -1]) {
    const result = formatIntradayForecast({ ...forecast, targetLow: invalid }, start + 1000)
    assert.equal(result.active, false)
    assert.equal(result.range, '')
  }
})

test('late or unknown recording times, abstentions and failures never become active', () => {
  for (const timingStatus of ['LATE', 'UNKNOWN', 'INVALID']) {
    assert.equal(formatIntradayForecast({ ...forecast, timingStatus }, start + 1000).active, false)
  }
  for (const status of ['ABSTAIN', 'MISSING', 'INVALID', 'INPUT_UNAVAILABLE', 'FAILED']) {
    assert.equal(formatIntradayForecast({ ...forecast, status }, start + 1000).active, false)
  }
  assert.equal(formatIntradayForecast({ ...forecast, status: 'ABSTAIN' }, start + 1000).direction, '不明确')
})

test('future or altered windows cannot display a current signal', () => {
  assert.equal(formatIntradayForecast(forecast, start - 1).active, false)
  assert.equal(formatIntradayForecast({ ...forecast, targetAt: new Date(start + 1860000).toISOString() }, start + 1000).active, false)
})
