export function taskDetailPresentation({ mobile, workspaceView, hasTask, editorOpen, batchCount, listActivityOpen, hasHabit }) {
  const hasTaskDetail = Boolean(hasTask || editorOpen);
  const mobileTaskDetail = Boolean(mobile && workspaceView === "LIST" && hasTaskDetail && !batchCount && !listActivityOpen && !hasHabit);
  const visible = Boolean(hasTaskDetail || batchCount || listActivityOpen || hasHabit);
  return {
    mobileTaskDetail,
    hidden: Boolean(mobile && !visible),
    dialog: Boolean(batchCount || listActivityOpen || (workspaceView !== "LIST" && hasTaskDetail) || (mobile && visible)),
  };
}

/** 手机整屏详情压在浏览器历史里的标记键，popstate 里靠它认领自己压的那条 */
export const TASK_DETAIL_HISTORY_MARK = "taskDetailSheet";

/**
 * 手机整屏任务详情的历史状态机：决定「要不要压条目 / 要不要出栈 / 返回时该关什么」。
 * source：
 *  - "open"      详情（含手机新建/编辑表单）打开
 *  - "popstate"  浏览器返回或 Android 返回键
 *  - "dismiss"   主动关闭（返回按钮、Esc、切视图）
 * 桌面（mobile=false）永远不压也不出栈，行为与改动前一致。
 */
export function taskDetailHistoryAction({ mobile, detailOpen, entryActive, source }) {
  const armed = Boolean(mobile && entryActive);
  if (source === "open") return { pushEntry: Boolean(mobile && detailOpen && !entryActive), popEntry: false, closeDetail: false };
  if (source === "popstate") return { pushEntry: false, popEntry: false, closeDetail: Boolean(armed && detailOpen) };
  if (source === "dismiss") return { pushEntry: false, popEntry: armed, closeDetail: false };
  return { pushEntry: false, popEntry: false, closeDetail: false };
}
