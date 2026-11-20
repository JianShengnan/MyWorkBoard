import { SPAN_CONFIG, SPAN_KEYS, VIEW_KEYS, clampSpan } from './calendarRange.js'

/**
 * 日历视图偏好的「按设备分存」纯函数。
 *
 * 存储结构（同一个 key，不新增 localStorage 键）：
 * {
 *   view, spans,                 // 旧版字段：只给桌面兜底/迁移用
 *   devices: {
 *     desktop: { view, spans },
 *     mobile:  { view, spans }
 *   },
 *   ...其余共享偏好（颜色 / 范围 / 时间网格 / 图层 / 安排面板）
 * }
 *
 * 选一种 key 而不是 `key:mobile`/`key:desktop` 两个键的理由：
 * 1. 颜色、筛选范围、时间网格等设置本来就该跨设备共享，拆成两个键要么重复写两份，
 *    要么再拆一个「共享键 + 两个设备键」，读一次要 merge 三个对象，容易漏写；
 * 2. 旧版本只有顶层 view/spans，用 devices 子对象可以原地迁移，桌面用户升级后视图不丢；
 * 3. 同一 key 下用一个 devices 命名空间，「别把手机的视图写到桌面」只靠写入目标分支保证，
 *    不存在两个 key 之间先后覆盖的问题。
 */
export const PREFERENCE_DEVICES = ['desktop', 'mobile']

/** view 既可能是普通视图，也可能是「多日 / 多周」这两个跨度视图 */
export const VALID_PREFERENCE_VIEWS = [...VIEW_KEYS, ...SPAN_KEYS]

function defaultSpans() {
  return {
    multiDay: SPAN_CONFIG.multiDay.defaultSpan,
    multiWeek: SPAN_CONFIG.multiWeek.defaultSpan
  }
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

/** null / undefined / 空串 / 非数字都不参与 clamp，直接回到默认跨度 */
function normalizeSpan(key, value) {
  const config = SPAN_CONFIG[key]
  if (!config) return null
  if (value === null || value === undefined || value === '') return config.defaultSpan
  if (!Number.isFinite(Number(value))) return config.defaultSpan
  return clampSpan(key, value)
}

export function normalizeSpans(raw) {
  const source = isPlainObject(raw) ? raw : {}
  return {
    multiDay: normalizeSpan('multiDay', source.multiDay),
    multiWeek: normalizeSpan('multiWeek', source.multiWeek)
  }
}

/** 手机 / 桌面判定，输入通常来自 isMobileWidth() */
export function preferenceDevice(isMobile) {
  return isMobile ? 'mobile' : 'desktop'
}

/**
 * 从完整存储对象里取出指定设备的视图偏好。
 * - 非法/缺失一律回落到 `month` + 默认跨度，不会把脏数据带进 resolveRange；
 * - 桌面在没有 devices.desktop 时读旧版顶层 view/spans（升级迁移）；
 * - 手机不继承旧版顶层值，首次进入固定 `month`（紧凑月历）。
 */
export function resolveDevicePreferences(stored, device = 'desktop') {
  const target = PREFERENCE_DEVICES.includes(device) ? device : 'desktop'
  const record = isPlainObject(stored) ? stored : {}
  const devices = isPlainObject(record.devices) ? record.devices : {}
  let section = isPlainObject(devices[target]) ? devices[target] : null
  if (!section && target === 'desktop' && (record.view !== undefined || record.spans !== undefined)) {
    section = { view: record.view, spans: record.spans }
  }
  const source = section || {}
  return {
    view: VALID_PREFERENCE_VIEWS.includes(source.view) ? source.view : 'month',
    spans: normalizeSpans(source.spans)
  }
}

/**
 * 写回指定设备的视图偏好，保留另一个设备的条目。
 * 顶层 view/spans 继续镜像桌面条目（或旧值），旧版本/旧数据仍能读到桌面选择。
 */
export function mergeDevicePreferences(stored, device, preferences = {}) {
  const record = isPlainObject(stored) ? stored : {}
  const devices = { ...(isPlainObject(record.devices) ? record.devices : {}) }
  const target = PREFERENCE_DEVICES.includes(device) ? device : 'desktop'
  const view = VALID_PREFERENCE_VIEWS.includes(preferences.view) ? preferences.view : 'month'
  devices[target] = { view, spans: normalizeSpans(preferences.spans) }
  const desktop = devices.desktop
  return {
    ...record,
    view: desktop ? desktop.view : (VALID_PREFERENCE_VIEWS.includes(record.view) ? record.view : 'month'),
    spans: desktop ? desktop.spans : (isPlainObject(record.spans) ? record.spans : defaultSpans()),
    devices
  }
}
