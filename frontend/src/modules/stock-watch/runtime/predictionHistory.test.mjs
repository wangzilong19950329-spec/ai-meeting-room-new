import assert from 'node:assert/strict'
import test from 'node:test'
import { loadAllPredictionPages, selectVisiblePredictionGroups } from './predictionHistory.mjs'

test('loads every date and finishes a date split across the 200-record boundary', async () => {
  const dates = ['2026-08-14', '2026-08-13', '2026-08-10', '2026-08-07', '2026-07-31', '2026-07-30', '2026-07-29', '2026-07-28']
  const counts = [35, 38, 16, 31, 22, 24, 20, 32]
  for (let day = 27; day >= 11; day--) {
    dates.push(`2026-07-${day}`)
    counts.push(day === 27 ? 6 : 5)
  }
  const source = []
  let id = 304
  dates.forEach((date, index) => {
    for (let i = 0; i < counts[index]; i++) source.push({ id: id--, forecastDate: date })
  })
  assert.equal(source.length, 304)

  const requests = []
  const history = await loadAllPredictionPages(async (params) => {
    requests.push(params)
    const remaining = source.filter((row) => !params.beforeDate
      || row.forecastDate < params.beforeDate
      || (row.forecastDate === params.beforeDate && row.id < params.beforeId))
    return { summary: { total: 200 }, records: remaining.slice(0, params.limit) }
  }, 200)

  assert.equal(history.records.length, 304)
  assert.equal(history.records.filter((row) => row.forecastDate === '2026-07-28').length, 32)
  assert.equal(new Set(history.records.map((row) => row.forecastDate)).size, 25)
  assert.equal(requests.length, 2)
  assert.deepEqual(requests[1], { limit: 200, beforeDate: source[199].forecastDate, beforeId: source[199].id })
})

test('daily view lists all dates while individual view keeps its initial compact limit', () => {
  const groups = Array.from({ length: 25 }, (_, index) => ({ date: `date-${index}` }))
  assert.equal(selectVisiblePredictionGroups(groups, true, false, 6).length, 25)
  assert.equal(selectVisiblePredictionGroups(groups, false, false, 6).length, 6)
  assert.equal(selectVisiblePredictionGroups(groups, false, true, 6).length, 25)
})

test('rejects a server that ignores the history cursor', async () => {
  const page = Array.from({ length: 200 }, (_, index) => ({ id: 200 - index, forecastDate: '2026-07-28' }))
  await assert.rejects(
    loadAllPredictionPages(async () => ({ records: page }), 200),
    /重复/
  )
})
