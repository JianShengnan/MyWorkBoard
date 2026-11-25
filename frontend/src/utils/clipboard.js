/**
 * 复制文本到剪贴板。
 *
 * `navigator.clipboard` 只在**安全上下文**（HTTPS / localhost）存在，
 * 线上是纯 HTTP（http://jsn1024.cn），该对象为 undefined，直接调用会抛
 * `TypeError: Cannot read properties of undefined (reading 'writeText')`。
 *
 * 兜底分两级：
 * 1. `document.execCommand('copy')` + 临时 textarea（桌面端首选）；
 * 2. `contentEditable` + Range（iOS Safari 对 readonly textarea 的 select 不可靠）。
 *
 * 关键细节：临时元素必须放在**视口内**且不是完全透明。早期实现用了
 * `left:-9999px; opacity:0`，Chrome 会把这种不可见元素的选区裁掉，
 * `execCommand` 仍然返回 true，但剪贴板是空的——表现就是「提示已复制，粘贴却没有」。
 *
 * 失败时**抛错**（而不是返回 false），调用方原有的 try/catch 语义不变。
 */
export async function copyText(text) {
  const value = String(text ?? '')

  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // 权限被拒 / 非聚焦文档 → 交给下面的兜底
    }
  }

  if (textareaCopy(value)) return true
  if (editableCopy(value)) return true
  throw new Error('复制失败')
}

function textareaCopy(value) {
  if (typeof document === 'undefined' || !document.body) return false

  const area = document.createElement('textarea')
  area.value = value
  area.setAttribute('readonly', '')
  // 视口内 1×1、opacity 0.01：完全透明或跑到视口外都会被浏览器判定为不可见
  area.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;margin:0;padding:0;' +
    'border:0;outline:none;box-shadow:none;background:transparent;opacity:0.01;z-index:2147483647'
  document.body.appendChild(area)

  const selection = typeof document.getSelection === 'function' ? document.getSelection() : null
  const savedRange = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null

  let ok = false
  try {
    if (typeof area.focus === 'function') area.focus({ preventScroll: true })
    area.select()
    area.setSelectionRange(0, value.length)
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }

  area.remove()
  restoreSelection(selection, savedRange)
  return ok
}

function editableCopy(value) {
  if (typeof document === 'undefined' || !document.body) return false
  if (typeof document.createRange !== 'function') return false

  const box = document.createElement('div')
  box.setAttribute('contenteditable', 'true')
  box.textContent = value
  box.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;margin:0;padding:0;' +
    'border:0;outline:none;overflow:hidden;opacity:0.01;z-index:2147483647'
  document.body.appendChild(box)

  const selection = typeof document.getSelection === 'function' ? document.getSelection() : null
  const savedRange = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null

  let ok = false
  try {
    const range = document.createRange()
    range.selectNodeContents(box)
    if (selection) {
      selection.removeAllRanges()
      selection.addRange(range)
    }
    if (typeof box.focus === 'function') box.focus({ preventScroll: true })
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }

  box.remove()
  restoreSelection(selection, savedRange)
  return ok
}

/** 还原用户原来的选区，避免复制动作把光标位置弄丢 */
function restoreSelection(selection, savedRange) {
  if (!selection || !savedRange) return
  try {
    selection.removeAllRanges()
    selection.addRange(savedRange)
  } catch {
    // 原选区所在节点可能已被卸载，忽略即可
  }
}
