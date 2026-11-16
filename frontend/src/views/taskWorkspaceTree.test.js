import test from "node:test";
import assert from "node:assert/strict";
import { buildTaskListGroups } from "./taskWorkspaceTree.js";
import { bumpEntityVersion } from "../utils/entityVersion.js";

const parent = { publicId: "parent", title: "父任务", sortOrder: 0 };
const child = { publicId: "child", title: "子任务", parentId: "parent", sortOrder: 2 };
const grandchild = { publicId: "grandchild", title: "孙任务", parentId: "child", sortOrder: 0 };
const other = { publicId: "other", title: "其他任务", sortOrder: 1 };

test("nests visible descendants under their root without duplicating them across groups", () => {
  const groups = buildTaskListGroups([
    { key: "first", tasks: [parent, other] },
    { key: "second", tasks: [child, grandchild] },
  ], [parent, child, grandchild, other]);

  assert.deepEqual(groups[0].rows.map(({ publicId, treeDepth }) => [publicId, treeDepth]), [
    ["parent", 0], ["child", 1], ["grandchild", 2], ["other", 0],
  ]);
  assert.equal(groups[0].visibleCount, 4);
  assert.deepEqual(groups[1].rows, []);
});

test("collapses a branch while retaining the root row", () => {
  const groups = buildTaskListGroups([{ key: "all", tasks: [parent, child, grandchild] }], [parent, child, grandchild], new Set(["parent"]));
  assert.deepEqual(groups[0].rows.map(({ publicId }) => publicId), ["parent"]);
  assert.equal(groups[0].rows[0].hasVisibleChildren, true);
});

test("keeps a child at the top level when its parent is outside the visible task set", () => {
  const groups = buildTaskListGroups([{ key: "all", tasks: [child] }], [child]);
  assert.deepEqual(groups[0].rows.map(({ publicId, treeDepth }) => [publicId, treeDepth]), [["child", 0]]);
});

test("reuses row objects across rebuilds so unchanged rows can skip re-rendering", () => {
  const cache = new Map();
  const build = (tasks, collapsed = new Set()) =>
    buildTaskListGroups([{ key: "all", tasks }], tasks, collapsed, cache)[0].rows;

  const first = build([parent, child]);
  const second = build([parent, child]);
  assert.equal(second[0], first[0], "未变的任务要复用同一个行对象");
  assert.equal(second[1], first[1], "子任务也一样");

  // 只有被改过的那一条是新的
  const edited = { ...parent, title: "父任务（改）" };
  const third = build([edited, child]);
  assert.notEqual(third[0], first[0]);
  assert.equal(third[0].title, "父任务（改）");
  assert.equal(third[1], first[1]);
});

test("rebuilds a row when its depth changes but reuses it when only the branch is collapsed", () => {
  const cache = new Map();
  const build = (tasks, collapsed = new Set()) =>
    buildTaskListGroups([{ key: "all", tasks }], tasks, collapsed, cache)[0].rows;

  const expanded = build([parent, child]);
  // 收起父任务只影响父任务下面的行，父任务自己那行的内容（含 hasVisibleChildren）没变
  const collapsed = build([parent, child], new Set(["parent"]));
  assert.equal(collapsed.length, 1);
  assert.equal(collapsed[0], expanded[0], "收起分支不该让根行重建");

  // 子任务升为顶层，depth 从 1 变 0，必须产出新对象
  const promoted = { ...child, parentId: undefined };
  const promotedRows = build([parent, promoted]);
  assert.equal(promotedRows[1].treeDepth, 0);
  assert.notEqual(promotedRows[1], expanded[1]);
});

test("drops cache entries for rows no longer in the list", () => {
  const cache = new Map();
  buildTaskListGroups([{ key: "all", tasks: [parent, other] }], [parent, other], new Set(), cache);
  assert.deepEqual([...cache.keys()].sort(), ["other", "parent"]);
  buildTaskListGroups([{ key: "all", tasks: [other] }], [other], new Set(), cache);
  assert.deepEqual([...cache.keys()], ["other"], "切视图后不再出现的行不能一直留在缓存里");
});

test("rebuilds the row after an in-place field change bumps the version", () => {
  const cache = new Map();
  const task = { publicId: "edit-me", title: "原标题", checklist: [] };
  const build = () => buildTaskListGroups([{ key: "all", tasks: [task] }], [task], new Set(), cache)[0].rows;

  const before = build();
  // store 的 applySyncChanges 就是这么更新的：同一个对象，就地改字段，再打版本号
  task.title = "改后标题";
  bumpEntityVersion(task);

  const after = build();
  assert.notEqual(after[0], before[0], "版本号变了必须产出新行对象，否则界面会停在旧内容上");
  assert.equal(after[0].title, "改后标题");
});
