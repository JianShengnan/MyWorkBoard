/**
 * 首页「倒数日」系列卡片（发薪日 / 节假日 / 纪念日）与戒烟卡片的取数口径。
 *
 * 发薪日与节假日都不是新数据源：任务域的倒数日早就有对应的系统内置项
 * （`builtinKey` 为 `payday` / `holiday`，目标日由服务端按放假表滚动），
 * 首页只挑出来展示，避免再维护一份「几号发薪」的配置。
 *
 * 这里保持纯函数，便于对边界（跨年、调休、过期条目不显示）做单测。
 */

import { daysUntil } from '../countdowns/countdownUtils.js'
import { dateKeyOf } from './homeMetrics.js'

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

function parseDay(key) {
  const [year, month, day] = String(key || '').split('-').map(Number)
  if (!year || !month || !day) return null
  const date = new Date(year, month - 1, day)
  return Number.isNaN(date.getTime()) ? null : date
}

export function weekdayLabel(key) {
  const date = parseDay(key)
  return date ? WEEKDAYS[date.getDay()] : ''
}

function aliveCountdowns(countdowns) {
  return (countdowns || []).filter(item => item && !item.deleted && !item.archived)
}

/** 取系统内置倒数日（发薪日 / 节假日）；用户删掉内置项时返回 null，卡片显示引导 */
export function findBuiltinCountdown(countdowns, key) {
  return aliveCountdowns(countdowns).find(item => item.builtinKey === key) || null
}

/**
 * 最近的纪念日：只取用户自建的倒数日（内置的周末/月末由专门卡片负责）。
 * 排序口径：先按「还有几天」升序排未来的，不足时再补最近的过去，避免只剩陈年条目。
 */
export function nearestCountdowns(countdowns, { today = new Date(), limit = 3 } = {}) {
  const todayKey = dateKeyOf(today)
  const rows = aliveCountdowns(countdowns)
    .filter(item => !item.builtinKey)
    .map(item => {
      const days = daysUntil(item, todayKey)
      return {
        id: String(item.publicId || item.id || ''),
        title: item.title || '未命名倒数日',
        days,
        target: String(item.targetDate || '').slice(0, 10),
        weekday: weekdayLabel(String(item.targetDate || '')),
        color: item.color || item.iconColor || 'var(--accent)',
        icon: item.iconValue || '📌'
      }
    })
  const upcoming = rows.filter(row => row.days >= 0).sort((left, right) => left.days - right.days)
  const past = rows.filter(row => row.days < 0).sort((left, right) => right.days - left.days)
  return [...upcoming, ...past].slice(0, limit)
}

/** 发薪日：目标日由内置倒数日给出，这里只补充展示用的天数与星期 */
export function paydaySummary(countdown, { today = new Date() } = {}) {
  if (!countdown) return null
  const todayKey = dateKeyOf(today)
  const target = String(countdown.targetDate || '').slice(0, 10)
  return {
    id: String(countdown.publicId || countdown.id || ''),
    title: countdown.title || '距离发薪日',
    target,
    days: daysUntil(countdown, todayKey),
    weekday: weekdayLabel(target)
  }
}

/**
 * 下一个节假日：主条目来自内置倒数日（标题里带节日名）；
 * `upcoming` 从放假表里再往后取几个「放假起始日」，作为「接着是…」的补充。
 */
export function holidaySummary(countdown, holidays = {}, { today = new Date(), upcoming = 2 } = {}) {
  const todayKey = dateKeyOf(today)
  const main = countdown
    ? {
      title: countdown.title || '距离下一个节假日',
      target: String(countdown.targetDate || '').slice(0, 10),
      days: daysUntil(countdown, todayKey),
      weekday: weekdayLabel(String(countdown.targetDate || ''))
    }
    : null
  // 主条目已经占用了「下一个节日」，补充列表从它之后按名字去重（同一节日连休多天只列一次）
  const mainName = main ? main.title.replace(/^距离/, '') : ''
  const rows = []
  const seen = new Set(mainName ? [mainName] : [])
  for (const [date, info] of Object.entries(holidays || {})) {
    if (!info || info.off !== true) continue
    if (date <= todayKey) continue
    const name = String(info.name || '').trim()
    if (!name || seen.has(name)) continue
    seen.add(name)
    rows.push({ date, name, days: Math.round((parseDay(date) - parseDay(todayKey)) / 86400000), weekday: weekdayLabel(date) })
  }
  rows.sort((left, right) => left.date.localeCompare(right.date))
  return { main, upcoming: rows.slice(0, upcoming) }
}

/**
 * 戒烟卡片最近 N 天：给迷你柱状条用。没有记录的日子与「0 支」区分开，
 * 前者是没打卡、后者是明确没抽，展示上不能混为一谈。
 */
export function quitWeek(days = {}, { today = new Date(), span = 7 } = {}) {
  const rows = []
  for (let offset = span - 1; offset >= 0; offset -= 1) {
    const date = new Date(today)
    date.setDate(date.getDate() - offset)
    const key = dateKeyOf(date)
    const record = days?.[key]
    rows.push({
      date: key,
      label: String(date.getDate()),
      weekday: WEEKDAYS[date.getDay()].slice(1),
      count: record ? Number(record.smokedCount || 0) : null,
      logged: Boolean(record)
    })
  }
  return rows
}

/** 补录日期范围：与戒烟 store 拉取的 90 天窗口一致，且不允许未来日期 */
export function recordableRange({ today = new Date(), span = 90 } = {}) {
  const max = dateKeyOf(today)
  const start = new Date(today)
  start.setDate(start.getDate() - (span - 1))
  return { min: dateKeyOf(start), max }
}
