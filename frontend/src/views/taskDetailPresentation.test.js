import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { TASK_DETAIL_HISTORY_MARK, taskDetailHistoryAction, taskDetailPresentation } from "./taskDetailPresentation.js";

const base = { mobile: true, workspaceView: "LIST", hasTask: false, editorOpen: false, batchCount: 0, listActivityOpen: false, hasHabit: false };
const view = await readFile(new URL("./TaskWorkspaceView.vue", import.meta.url), "utf8");

test("手机列表选中任务和新建表单都显示完整详情且可被辅助技术访问", () => {
  for (const patch of [{ hasTask: true }, { editorOpen: true }]) {
    assert.deepEqual(taskDetailPresentation({ ...base, ...patch }), { mobileTaskDetail: true, hidden: false, dialog: true });
  }
  assert.deepEqual(taskDetailPresentation(base), { mobileTaskDetail: false, hidden: true, dialog: false });
});

test("习惯、批量操作和清单动态保持各自详情样式且不会被aria-hidden隐藏", () => {
  for (const patch of [{ hasHabit: true }, { batchCount: 2 }, { listActivityOpen: true }]) {
    const state = taskDetailPresentation({ ...base, hasTask: true, ...patch });
    assert.deepEqual(state, { mobileTaskDetail: false, hidden: false, dialog: true });
  }
});

test("桌面列表仍使用常驻详情，看板和时间线保留原浮层语义", () => {
  assert.deepEqual(taskDetailPresentation({ ...base, mobile: false, hasTask: true }), { mobileTaskDetail: false, hidden: false, dialog: false });
  for (const mobile of [true, false]) {
    for (const workspaceView of ["KANBAN", "TIMELINE"]) {
      assert.deepEqual(taskDetailPresentation({ ...base, mobile, workspaceView, hasTask: true }), { mobileTaskDetail: false, hidden: false, dialog: true });
    }
  }
});

test("手机查看在行内编辑和focus之前返回，桌面仍保留原有编辑行为", () => {
  const openRow = view.match(/function openTaskRow\(task, event\) \{[\s\S]*?\n\}/)?.[0];
  assert.match(openRow, /openDetail\(task\);\s+if \(isMobileSidebar\.value\) \{\s+inlineRowTitleTaskId\.value = "";\s+return;\s+\}\s+inlineRowTitleTaskId\.value = task\.publicId/);
  assert.match(openRow, /document\.querySelector\("\.clone-task-content input"\)\?\.focus\(\)/);
});

test("手机完整详情包含关闭入口、安全区、独立滚动和可达的底部工具", () => {
  assert.match(view, /v-if="isMobileSidebar"[^\n]*aria-label="关闭任务详情，返回列表" @click="closeDetail"/);
  assert.match(view, /:aria-hidden="detailPresentation\.hidden \? 'true' : undefined"/);
  assert.match(view, /'is-mobile-task-detail': detailPresentation\.mobileTaskDetail/);
  assert.match(view, /\.clone-detail-pane\.is-mobile-task-detail \{[^}]*position: fixed;[^}]*display: flex !important;[^}]*height: 100dvh;[^}]*safe-area-inset-top/s);
  assert.match(view, /\.clone-detail-pane\.is-mobile-task-detail \.clone-detail-form \{[^}]*overflow: auto;[^}]*overscroll-behavior: contain;/);
  assert.match(view, /\.clone-detail-pane\.is-mobile-task-detail \.clone-detail-footer \{[^}]*safe-area-inset-bottom/);
  assert.match(view, /\.clone-task-shell > \.clone-detail-pane\.is-mobile-task-detail \{ animation: none; \}/);
  assert.match(view, /:global\(body:has\(\.clone-detail-pane\.is-mobile-task-detail\) \.ui-dialog-backdrop\) \{ z-index: 340; \}/);
  assert.match(view, /:global\(body:has\(\.clone-detail-pane\.is-mobile-task-detail\) \.ui-dialog\) \{ z-index: 341; \}/);
  assert.match(view, /\(\(isMobileSidebar\.value \|\| activeWorkspaceView\.value !== "LIST"\) && selectedTask\.value\)\) closeDetail\(\)/);
});

test("手机详情与批量模式互斥且关闭后回到隐藏状态", () => {
  const selected = { ...base, hasTask: true };
  assert.equal(taskDetailPresentation(selected).mobileTaskDetail, true);
  assert.equal(taskDetailPresentation({ ...selected, batchCount: 1 }).mobileTaskDetail, false);
  assert.equal(taskDetailPresentation({ ...selected, batchCount: 0 }).mobileTaskDetail, true);
  assert.deepEqual(taskDetailPresentation({ ...selected, hasTask: false }), { mobileTaskDetail: false, hidden: true, dialog: false });
});

test("手机关闭清理详情菜单，缓存工作区切路由清理批量与习惯残留", () => {
  const closeDetail = view.match(/function closeDetail\(\) \{[\s\S]*?\n\}/)?.[0];
  assert.match(closeDetail, /if \(isMobileSidebar\.value\) \{[\s\S]*inlineRowTitleTaskId\.value = "";[\s\S]*detailListPickerOpen\.value = false;[\s\S]*detailTagPickerOpen\.value = false;[\s\S]*detailBlockMenuOpen\.value = false;[\s\S]*detailLinkPickerOpen\.value = false;[\s\S]*closeDetailMore\(\)/);
  assert.match(view, /\(\) => route\.fullPath,[\s\S]*closeDetail\(\);\s+if \(isMobileSidebar\.value\) \{\s+clearBatchSelection\(\);\s+closeHabitPane\(\);/);
});

test("所有习惯Teleport子弹层层级统一且高于手机习惯详情", async () => {
  const detailLayer = Number(view.match(/\.clone-detail-pane\.is-habit \{[^}]*z-index: (\d+)/s)?.[1]);
  assert.equal(detailLayer, 302);
  for (const component of ["HabitFormDialog", "HabitLogDialog", "HabitCheckDialog", "HabitSkipDialog", "HabitCheckSettingDialog"]) {
    const source = await readFile(new URL(`../components/habits/${component}.vue`, import.meta.url), "utf8");
    const layer = Number(source.match(/\.[a-z]+-mask \{[^}]*z-index: (\d+)/)?.[1]);
    assert.equal(layer, 360, component);
    assert.ok(layer > detailLayer, `${component} 必须高于习惯详情`);
    assert.match(source, /<Teleport to="body">/);
    assert.match(source, /role="dialog" aria-modal="true"/);
  }
});

test("手机打开整屏详情压一条历史条目，桌面永远不压", () => {
  const open = { mobile: true, detailOpen: true, entryActive: false, source: "open" };
  assert.deepEqual(taskDetailHistoryAction(open), { pushEntry: true, popEntry: false, closeDetail: false });
  assert.deepEqual(taskDetailHistoryAction({ ...open, detailOpen: false }), { pushEntry: false, popEntry: false, closeDetail: false });
  assert.deepEqual(taskDetailHistoryAction({ ...open, entryActive: true }), { pushEntry: false, popEntry: false, closeDetail: false });
  assert.deepEqual(taskDetailHistoryAction({ ...open, mobile: false }), { pushEntry: false, popEntry: false, closeDetail: false });
});

test("返回事件在详情打开时只关详情，详情已关则不动页面", () => {
  assert.deepEqual(taskDetailHistoryAction({ mobile: true, detailOpen: true, entryActive: true, source: "popstate" }), { pushEntry: false, popEntry: false, closeDetail: true });
  assert.deepEqual(taskDetailHistoryAction({ mobile: true, detailOpen: false, entryActive: true, source: "popstate" }), { pushEntry: false, popEntry: false, closeDetail: false });
  assert.deepEqual(taskDetailHistoryAction({ mobile: false, detailOpen: true, entryActive: true, source: "popstate" }), { pushEntry: false, popEntry: false, closeDetail: false });
  // 没压过条目（entryActive=false）时完全没有我们的事
  assert.deepEqual(taskDetailHistoryAction({ mobile: true, detailOpen: true, entryActive: false, source: "popstate" }), { pushEntry: false, popEntry: false, closeDetail: false });
});

test("主动关闭只在压过条目时出栈，避免残留幽灵历史", () => {
  assert.deepEqual(taskDetailHistoryAction({ mobile: true, detailOpen: true, entryActive: true, source: "dismiss" }), { pushEntry: false, popEntry: true, closeDetail: false });
  assert.deepEqual(taskDetailHistoryAction({ mobile: true, detailOpen: true, entryActive: false, source: "dismiss" }), { pushEntry: false, popEntry: false, closeDetail: false });
  assert.deepEqual(taskDetailHistoryAction({ mobile: false, detailOpen: true, entryActive: true, source: "dismiss" }), { pushEntry: false, popEntry: false, closeDetail: false });
  assert.equal(typeof TASK_DETAIL_HISTORY_MARK, "string");
  assert.ok(TASK_DETAIL_HISTORY_MARK.length > 0);
});

test("详情工作区把手机返回接到历史条目上，关闭与卸载都清理干净", () => {
  assert.match(view, /window\.history\.pushState\(\{ \.\.\.\(window\.history\.state \|\| \{\}\), \[TASK_DETAIL_HISTORY_MARK\]: true \}, ""\)/);
  assert.match(view, /window\.addEventListener\("popstate", onDetailHistoryPopState\)/);
  assert.match(view, /window\.removeEventListener\("popstate", onDetailHistoryPopState\)/);
  assert.match(view, /if \(action\.closeDetail\) closeDetail\(\)/);
  assert.match(view, /watch\(\s*\(\) => detailPresentation\.value\.mobileTaskDetail,\s*\(open\) => \{\s*if \(open\) armDetailHistory\(\);\s*else releaseDetailHistory\(\);/);
  // 主动关闭/删任务等所有出口都经过 closeDetail，再由 detailPresentation 的 watch 统一出栈
  assert.match(view, /function closeDetail\(\) \{[\s\S]*?\n\}/);
  assert.match(view, /detailHistoryArmed = false;\s+detailHistoryReleasing = false;\s+detailHistoryReopen = false;/);
});
