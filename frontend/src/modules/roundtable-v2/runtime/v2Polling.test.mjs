import assert from 'node:assert/strict'
import test from 'node:test'
import { shouldLoadFull } from './v2Polling.mjs'

test('the full v2 data is fetched only when its version changed, on first load or when forced', () => {
  assert.equal(shouldLoadFull(null, '12-340-9876-0', false), true)
  assert.equal(shouldLoadFull('12-340-9876-0', '12-340-9876-0', false), false)
  assert.equal(shouldLoadFull('12-340-9876-0', '13-341-9900-0', false), true)
  assert.equal(shouldLoadFull('12-340-9876-0', '12-340-9876-0', true), true)
  assert.equal(shouldLoadFull('12-340-9876-0', null, false), true, 'an unknown version is treated as changed')
})
