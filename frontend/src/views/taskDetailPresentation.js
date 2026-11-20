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
