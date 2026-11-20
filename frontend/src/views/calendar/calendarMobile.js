/**
 * 日历域手机布局的纯函数集合。
 *
 * 断点必须与 `TasksCalendarView.vue` 里的 `@media (max-width: 1023px)` 保持一致，
 * 脚本侧不要另外造一套阈值，否则会出现「CSS 认为窄屏、脚本认为宽屏」的错位。
 */
export const MOBILE_MAX_WIDTH = 1023

/** 是否走手机（两行工具栏 / 抽屉 / 底部面板）布局 */
export function isMobileWidth(width) {
  // null / undefined / 空串会被 Number() 转成 0，直接当作「测不到宽度」按桌面处理
  if (width === null || width === undefined || width === '') return false
  const value = Number(width)
  if (!Number.isFinite(value)) return false
  return value <= MOBILE_MAX_WIDTH
}

/**
 * 筛选按钮徽标数量。
 * - 过滤器生效算 1（过滤器本身是一整套条件，无法折算成清单/标签数）
 * - 自定义范围算勾选的清单数 + 标签数
 * - 「所有」为 0，此时不显示徽标
 */
export function countActiveFilters(scope) {
  if (!scope || typeof scope !== 'object') return 0
  if (scope.mode === 'filter') return scope.filterId ? 1 : 0
  if (scope.mode !== 'custom') return 0
  const lists = Array.isArray(scope.listIds) ? scope.listIds.length : 0
  const tags = Array.isArray(scope.tagIds) ? scope.tagIds.length : 0
  // custom 但两个都空等于「所有」，别让徽标挂住一个 0
  return Math.max(0, lists + tags)
}

/** 徽标文案：0 返回空串（调用方据此隐藏徽标），超过 99 收成 99+ */
export function filterBadgeText(count) {
  const value = Math.floor(Number(count) || 0)
  if (value <= 0) return ''
  return value > 99 ? '99+' : String(value)
}

/** 筛选抽屉只认这些来源，其它输入一律保持原状，避免误触把抽屉关上 */
export const FILTER_DRAWER_ACTIONS = ['open', 'close', 'backdrop', 'escape', 'done']

/**
 * 筛选抽屉的开合状态机。
 * 点遮罩、按 Esc、点「完成」、点关闭按钮都走 close 分支；「清空筛选」不关抽屉。
 */
export function reduceFilterDrawer(open, action) {
  if (action === 'open') return true
  if (action === 'close' || action === 'backdrop' || action === 'escape' || action === 'done') return false
  return Boolean(open)
}
