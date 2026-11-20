/**
 * 月视图「手机紧凑模式」的纯函数。
 *
 * 断点与 `calendarMobile.js` 的 1023 保持一致，由调用方判定后把结果传进来；
 * 这里只负责算数值，不读 window，方便 node --test 直接覆盖。
 */

/** 手机格内最多展示 2 行：1 条事件 + 1 行「+N」，或 2 条事件 */
export const MOBILE_COMPACT_ROWS = 2

/**
 * 手机格子最低高度（px）。
 * 需要同时容纳：日期行（约 20）+ 1 条跨日泳道（20）+ 2 条事件（2 × 16 + 间距），
 * 并且保证日期格的点击命中区明显大于 36px。
 */
export const MOBILE_ROW_MIN_HEIGHT = 60

/** 月视图顶部星期栏加表头占位，从可用高度里扣掉 */
export const MOBILE_MONTH_CHROME_HEIGHT = 28

/**
 * 手机月视图单元格高度：按日历容器可用高度等分，最低 MOBILE_ROW_MIN_HEIGHT。
 * 非法高度（0 / NaN / null）退回最低值，避免把格子算成 0 高。
 */
export function mobileCellHeight(availableHeight, rows, { headHeight = MOBILE_MONTH_CHROME_HEIGHT, minHeight = MOBILE_ROW_MIN_HEIGHT } = {}) {
  const count = Math.max(1, Math.floor(Number(rows) || 0))
  const usable = Number(availableHeight)
  const floorHeight = Number.isFinite(Number(minHeight)) ? Number(minHeight) : MOBILE_ROW_MIN_HEIGHT
  if (!Number.isFinite(usable) || usable <= 0) return floorHeight
  const chrome = Number.isFinite(Number(headHeight)) ? Number(headHeight) : MOBILE_MONTH_CHROME_HEIGHT
  return Math.max(floorHeight, Math.round((usable - chrome) / count))
}

/**
 * 单个日期格的事件裁剪规则。
 * - total <= cap：全部展示，不出「+N」
 * - total > cap：留一行给「+N」，其余展示 cap - 1 条（cap 至少 1）
 * 桌面沿用同一套规则（cap 来自格子实际能放下的行数），保证两边行为一致。
 */
export function compactEventRows(total, cap = MOBILE_COMPACT_ROWS) {
  const count = Math.max(0, Math.floor(Number(total) || 0))
  const limit = Math.max(1, Math.floor(Number(cap) || 1))
  if (count <= limit) return { visible: count, hidden: 0 }
  const visible = Math.max(0, limit - 1)
  return { visible, hidden: count - visible }
}
