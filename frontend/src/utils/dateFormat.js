/**
 * 带缓存的 Intl.DateTimeFormat。
 *
 * `new Intl.DateTimeFormat()` 单次构造在 V8 里要几十到上百微秒（要解析 locale、
 * 组装格式化规则），而任务列表一行里光日期标签就要用到两三处。700 多行时
 * 一次重渲染就是两千来次构造 —— 这是「点一下完成要等一秒」里很实在的一块。
 *
 * 格式化器本身是不可变的，按 options 建一次就够了。
 */
const cache = new Map()

export function dateFormatter(options = {}, locale = 'zh-CN') {
  const key = `${locale}|${JSON.stringify(options)}`
  let formatter = cache.get(key)
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(locale, options)
    cache.set(key, formatter)
  }
  return formatter
}

/** 月/日，同年省略年份；跨年才带年 */
export function dayLabel(date, { withYear = true } = {}) {
  const sameYear = date.getFullYear() === new Date().getFullYear()
  const options = sameYear || !withYear
    ? { month: 'numeric', day: 'numeric' }
    : { year: 'numeric', month: 'numeric', day: 'numeric' }
  return dateFormatter(options).format(date)
}

/** 时:分，24 小时制 */
export function timeLabel(date) {
  return dateFormatter({ hour: '2-digit', minute: '2-digit' }).format(date)
}
