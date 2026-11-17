import test from 'node:test'
import assert from 'node:assert/strict'
import {
  findBuiltinCountdown, holidaySummary, nearestCountdowns, paydaySummary, quitWeek, recordableRange, weekdayLabel
} from './homeDashboard.js'

const today = new Date(2026, 10, 17)

test('builtin payday and holiday are picked from the task-domain countdowns', () => {
  const rows = [
    { publicId: 'a', title: '距离发薪日', builtinKey: 'payday', targetDate: '2026-11-20' },
    { publicId: 'b', title: '距离元旦', builtinKey: 'holiday', targetDate: '2027-01-01' },
    { publicId: 'c', title: '我的生日', targetDate: '2026-12-01' }
  ]
  assert.equal(findBuiltinCountdown(rows, 'payday').publicId, 'a')
  assert.equal(findBuiltinCountdown(rows, 'holiday').publicId, 'b')
  assert.equal(findBuiltinCountdown(rows, 'weekend'), null)
  // 归档/删除的内置项不能再出现在首页
  assert.equal(findBuiltinCountdown([{ builtinKey: 'payday', archived: true }], 'payday'), null)
  assert.equal(findBuiltinCountdown([{ builtinKey: 'payday', deleted: true }], 'payday'), null)
})

test('nearest countdowns sort upcoming first, then the closest past ones, and skip builtins', () => {
  const rows = [
    { publicId: 'payday', title: '距离发薪日', builtinKey: 'payday', targetDate: '2026-11-20' },
    { publicId: 'far', title: '春节', targetDate: '2027-02-17' },
    { publicId: 'near', title: '纪念日', targetDate: '2026-11-19' },
    { publicId: 'pass', title: '已过去', targetDate: '2026-11-10' },
    { publicId: 'gone', title: '归档', targetDate: '2026-11-18', archived: true }
  ]
  const list = nearestCountdowns(rows, { today, limit: 3 })
  assert.deepEqual(list.map(item => item.id), ['near', 'far', 'pass'])
  assert.equal(list[0].days, 2)
  assert.equal(list[2].days, -7)
})

test('payday summary derives days and weekday from the builtin target', () => {
  const summary = paydaySummary({ publicId: 'p', title: '距离发薪日', targetDate: '2026-12-07' }, { today })
  assert.equal(summary.days, 20)
  assert.equal(summary.target, '2026-12-07')
  assert.equal(summary.weekday, '周一')
  assert.equal(paydaySummary(null), null)
})

test('holiday summary keeps the builtin title and lists following off days without duplicates', () => {
  const holidays = {
    '2026-11-17': { name: '今天调休', off: true },
    '2027-01-01': { name: '元旦', off: true },
    '2027-01-02': { name: '元旦', off: true },
    '2027-01-03': { name: '元旦', off: true },
    '2027-02-06': { name: '春节', off: true },
    '2027-02-07': { name: '补班', off: false },
    '2026-10-01': { name: '国庆节', off: true }
  }
  const summary = holidaySummary(
    { title: '距离元旦', targetDate: '2027-01-01' }, holidays, { today })
  assert.equal(summary.main.days, 45)
  assert.equal(summary.main.weekday, '周五')
  // 主条目是元旦，补充列表里同名节日整段跳过；今天调休与补班都不算「接下来」
  assert.deepEqual(summary.upcoming.map(item => item.name), ['春节'])
  assert.equal(summary.upcoming[0].date, '2027-02-06')
  assert.ok(summary.upcoming.every(item => item.date > '2026-11-17'))
  assert.ok(!summary.upcoming.some(item => item.name === '补班'))
})

test('holiday summary works without a builtin countdown', () => {
  const summary = holidaySummary(null, { '2027-01-01': { name: '元旦', off: true } }, { today })
  assert.equal(summary.main, null)
  assert.deepEqual(summary.upcoming.map(item => item.name), ['元旦'])
})

test('quit week separates unlogged days from explicit zero-cigarette days', () => {
  const rows = quitWeek({ '2026-11-15': { smokedCount: 0 }, '2026-11-17': { smokedCount: 3 } }, { today, span: 3 })
  assert.deepEqual(rows.map(row => row.date), ['2026-11-15', '2026-11-16', '2026-11-17'])
  assert.equal(rows[0].count, 0)
  assert.equal(rows[1].count, null)
  assert.equal(rows[1].logged, false)
  assert.equal(rows[2].count, 3)
})

test('recordable range never allows future days', () => {
  const range = recordableRange({ today, span: 7 })
  assert.equal(range.max, '2026-11-17')
  assert.equal(range.min, '2026-11-11')
  assert.equal(weekdayLabel('2026-11-17'), '周二')
  assert.equal(weekdayLabel('bad'), '')
})
