// 应用外壳高度：App.vue 用 ResizeObserver 测出顶栏/底栏的真实外框高度（含 safe-area），
// 写到 documentElement 的 --app-topbar-h / --app-bottombar-h。
// 这里只做「矩形 → 像素整数」的换算，方便在 Node 里断言，也避免把测量逻辑散在组件里。

export const SHELL_DEFAULTS = Object.freeze({ topbar: 64, bottomNav: 0 });

/**
 * 单个外壳元素的高度。
 * - rect 为 null/undefined 表示元素不存在：用 fallback（顶栏 64，底栏 0）。
 * - 元素存在但高度为 0（display:none、脚本还没画出来）就返回 0，
 *   不能拿 fallback 顶上，否则隐藏的顶栏会凭空占掉 64px。
 */
export function measureShellHeight(rect, fallback = 0) {
  if (!rect) return Math.max(0, Math.round(Number(fallback) || 0));
  const height = Number(rect.height);
  if (!Number.isFinite(height) || height <= 0) return 0;
  return Math.round(height);
}

/** 顶栏 + 底栏的像素高度，供 --app-topbar-h / --app-bottombar-h 使用 */
export function resolveShellMetrics({ topbarRect = null, bottomNavRect = null, topbarFallback = SHELL_DEFAULTS.topbar, bottomNavFallback = SHELL_DEFAULTS.bottomNav } = {}) {
  return {
    topbarHeight: measureShellHeight(topbarRect, topbarFallback),
    bottomNavHeight: measureShellHeight(bottomNavRect, bottomNavFallback),
  };
}
