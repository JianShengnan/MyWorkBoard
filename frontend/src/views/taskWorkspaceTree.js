import { entityVersion } from "../utils/entityVersion.js";

/**
 * 把分组结果摊平成行。每行在任务本体上挂了两个派生字段（treeDepth / hasVisibleChildren），
 * 所以每次都得展开出一个新对象。
 *
 * 问题在于：列表每重渲染一次就是 700+ 行，如果行对象每次都是新的，
 * 下游的子组件 props 比较、v-memo 判断全部失效，于是一次「点完成」要把 700 多行
 * 全部重新 patch 一遍（实测 728 行时主线程 1s）。所以传入 rowCache 时，
 * 只在该行真正变化（任务引用或版本号变了 / 深度变了 / 有无可见子任务变了）时才产出新对象。
 */
export function buildTaskListGroups(groups, tasks, collapsedIds = new Set(), rowCache = null) {
  const usedKeys = rowCache ? new Set() : null;
  function rowOf(task, depth, hasVisibleChildren) {
    if (!rowCache) return { ...task, treeDepth: depth, hasVisibleChildren };
    usedKeys.add(task.publicId);
    const cached = rowCache.get(task.publicId);
    // 任务本体在 store 里是就地改字段的，引用不变但内容会变。只比对引用会让行对象
    // 一直停在旧的副本上，所以版本号也要一起比对。
    const version = entityVersion(task);
    if (
      cached &&
      cached.source === task &&
      cached.version === version &&
      cached.depth === depth &&
      cached.hasVisibleChildren === hasVisibleChildren
    ) {
      return cached.row;
    }
    const row = { ...task, treeDepth: depth, hasVisibleChildren };
    rowCache.set(task.publicId, { source: task, version, depth, hasVisibleChildren, row });
    return row;
  }

  const visibleById = new Map(tasks.map((task) => [task.publicId, task]));
  const childrenByParent = new Map();

  for (const task of tasks) {
    if (!task.parentId || !visibleById.has(task.parentId)) continue;
    const children = childrenByParent.get(task.parentId) || [];
    children.push(task);
    childrenByParent.set(task.parentId, children);
  }

  for (const children of childrenByParent.values()) {
    children.sort((left, right) => Number(left.sortOrder || 0) - Number(right.sortOrder || 0));
  }

  function appendBranch(task, depth, path, rows) {
    if (path.has(task.publicId)) return;
    const children = childrenByParent.get(task.publicId) || [];
    rows.push(rowOf(task, depth, children.length > 0));
    if (collapsedIds.has(task.publicId)) return;

    const nextPath = new Set(path);
    nextPath.add(task.publicId);
    for (const child of children) appendBranch(child, depth + 1, nextPath, rows);
  }

  const result = groups.map((group) => {
    const rows = [];
    const roots = group.tasks.filter((task) => !visibleById.has(task.parentId));
    for (const task of roots) appendBranch(task, 0, new Set(), rows);
    return { ...group, rows, visibleCount: rows.length };
  });

  // 已经不在这份列表里的行要扔掉，否则切视图/换筛选后缓存会一直长
  if (rowCache) {
    for (const key of rowCache.keys()) {
      if (!usedKeys.has(key)) rowCache.delete(key);
    }
  }
  return result;
}
