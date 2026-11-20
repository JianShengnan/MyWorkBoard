import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { measureShellHeight, resolveShellMetrics, SHELL_DEFAULTS } from "./appShellMetrics.js";

const app = await readFile(new URL("../App.vue", import.meta.url), "utf8");
const floatingAgent = await readFile(new URL("../components/FloatingAgentAssistant.vue", import.meta.url), "utf8");
const theme = await readFile(new URL("../styles/theme.css", import.meta.url), "utf8");

test("外壳高度取整，缺省与零高度分开处理", () => {
  assert.equal(measureShellHeight({ height: 63.6 }), 64);
  assert.equal(measureShellHeight({ height: 75.4 }), 75);
  assert.equal(measureShellHeight({ height: 0 }), 0);
  assert.equal(measureShellHeight({ height: -12 }), 0);
  assert.equal(measureShellHeight({ height: Number.NaN }), 0);
  assert.equal(measureShellHeight(null, 64), 64);
  assert.equal(measureShellHeight(undefined, 56), 56);
  assert.equal(measureShellHeight(null), 0);
});

test("顶栏缺失用 64 兜底，底栏缺失归零", () => {
  assert.deepEqual(resolveShellMetrics(), { topbarHeight: 64, bottomNavHeight: 0 });
  assert.deepEqual(SHELL_DEFAULTS, { topbar: 64, bottomNav: 0 });
  assert.deepEqual(resolveShellMetrics({ topbarRect: { height: 88 }, bottomNavRect: { height: 64 + 34 } }), { topbarHeight: 88, bottomNavHeight: 98 });
});

test("外壳元素存在但被 display:none 隐藏时高度归零", () => {
  assert.deepEqual(resolveShellMetrics({ topbarRect: { height: 0 }, bottomNavRect: { height: 0 } }), { topbarHeight: 0, bottomNavHeight: 0 });
});

test("App.vue 实测外壳高度并写入 CSS 变量，卸载时清理", () => {
  assert.match(app, /const topbarRef = ref\(null\)/);
  assert.match(app, /const bottomNavRef = ref\(null\)/);
  assert.match(app, /new ResizeObserver\(applyShellMetrics\)/);
  assert.ok(app.includes("root.style.setProperty('--app-topbar-h', `${metrics.topbarHeight}px`)"));
  assert.ok(app.includes("root.style.setProperty('--app-bottombar-h', `${metrics.bottomNavHeight}px`)"));
  assert.ok(app.includes("removeProperty('--app-topbar-h')"));
  assert.ok(app.includes("removeProperty('--app-bottombar-h')"));
  assert.match(app, /<header ref="topbarRef" class="workspace-topbar"/);
  assert.match(app, /<BottomNav [^>]*ref="bottomNavRef"/);
});

test("AI 悬浮入口底部避让基于底栏变量并保留兜底值", () => {
  assert.ok(floatingAgent.includes("@media (max-width:767px) { .floating-agent"));
  // 无底栏的路由（如 /calendar）变量是 0，靠 max() 兜住 home indicator 的安全区
  assert.ok(floatingAgent.includes("bottom:calc(max(var(--app-bottombar-h, 56px), env(safe-area-inset-bottom)) + 32px)"));
  assert.ok(!floatingAgent.includes("bottom:calc(96px"));
  assert.match(theme, /--app-topbar-h: 64px/);
  assert.match(theme, /--app-bottombar-h: 56px/);
});
