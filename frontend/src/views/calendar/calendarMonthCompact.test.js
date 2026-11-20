import test from 'node:test'
import assert from 'node:assert/strict'
import {
  MOBILE_COMPACT_ROWS, MOBILE_MONTH_CHROME_HEIGHT, MOBILE_ROW_MIN_HEIGHT, compactEventRows, mobileCellHeight
} from './calendarMonthCompact.js'

test('手机格高常量：2 行事件、最低 60、顶部占位 28', () => {
  assert.equal(MOBILE_COMPACT_ROWS, 2)
  assert.equal(MOBILE_ROW_MIN_HEIGHT, 60)
  assert.equal(MOBILE_MONTH_CHROME_HEIGHT, 28)
})

test('mobileCellHeight：按可用高度等分并扣掉星期栏', () => {
  assert.equal(mobileCellHeight(692, 6), 111)
  assert.equal(mobileCellHeight(508, 6), 80)
  assert.equal(mobileCellHeight(628, 5), 120)
})

test('mobileCellHeight：矮屏回落到最低值，非法输入也回落到最低值', () => {
  assert.equal(mobileCellHeight(200, 6), MOBILE_ROW_MIN_HEIGHT)
  assert.equal(mobileCellHeight(0, 6), MOBILE_ROW_MIN_HEIGHT)
  assert.equal(mobileCellHeight(-100, 6), MOBILE_ROW_MIN_HEIGHT)
  assert.equal(mobileCellHeight(undefined, 6), MOBILE_ROW_MIN_HEIGHT)
  assert.equal(mobileCellHeight(null, 6), MOBILE_ROW_MIN_HEIGHT)
  assert.equal(mobileCellHeight(Number.NaN, 6), MOBILE_ROW_MIN_HEIGHT)
})

test('mobileCellHeight：行数缺失按 1 行算，不会出现除零', () => {
  assert.equal(mobileCellHeight(288, 0), 260)
  assert.equal(mobileCellHeight(288, undefined), 260)
})

test('mobileCellHeight：允许调用方覆盖占位与最低值', () => {
  assert.equal(mobileCellHeight(300, 5, { headHeight: 0, minHeight: 40 }), 60)
  assert.equal(mobileCellHeight(100, 5, { headHeight: 0, minHeight: 40 }), 40)
})

test('compactEventRows：不超过上限时全部展示、不出 +N', () => {
  assert.deepEqual(compactEventRows(0), { visible: 0, hidden: 0 })
  assert.deepEqual(compactEventRows(1), { visible: 1, hidden: 0 })
  assert.deepEqual(compactEventRows(2), { visible: 2, hidden: 0 })
  assert.deepEqual(compactEventRows(2, 4), { visible: 2, hidden: 0 })
})

test('compactEventRows：超出上限时留一行给 +N', () => {
  assert.deepEqual(compactEventRows(3), { visible: 1, hidden: 2 })
  assert.deepEqual(compactEventRows(7), { visible: 1, hidden: 6 })
  assert.deepEqual(compactEventRows(7, 4), { visible: 3, hidden: 4 })
  assert.deepEqual(compactEventRows(5, 3), { visible: 2, hidden: 3 })
})

test('compactEventRows：非法条数与非法上限安全回退', () => {
  assert.deepEqual(compactEventRows(undefined), { visible: 0, hidden: 0 })
  assert.deepEqual(compactEventRows(-3), { visible: 0, hidden: 0 })
  assert.deepEqual(compactEventRows('abc'), { visible: 0, hidden: 0 })
  assert.deepEqual(compactEventRows(3, 0), { visible: 0, hidden: 3 })
  assert.deepEqual(compactEventRows(3, undefined), { visible: 1, hidden: 2 })
})
