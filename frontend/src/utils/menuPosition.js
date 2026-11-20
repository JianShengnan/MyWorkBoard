/**
 * 把浮层左上角收敛到视口内：锚点可能在屏幕边缘（长按、右键、按钮贴边），
 * 统一按菜单实际宽高算一次，避免菜单跑出可视区。
 */
export function clampMenuPoint(x, y, width, height, viewportWidth, viewportHeight, pad = 8) {
  const maxX = Math.max(pad, viewportWidth - width - pad)
  const maxY = Math.max(pad, viewportHeight - height - pad)
  return {
    x: Math.max(pad, Math.min(Number(x) || 0, maxX)),
    y: Math.max(pad, Math.min(Number(y) || 0, maxY))
  }
}
