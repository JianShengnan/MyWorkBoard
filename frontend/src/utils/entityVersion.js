/**
 * 行内容版本号。
 *
 * store 里就地改一条任务的字段时（`applySyncChanges`），对象引用保持不变。列表行
 * 用 `v-memo` 判断要不要重新渲染，如果只看对象引用就会认为「没变」，于是改了状态
 * 但界面不刷新；反过来如果每次都重建对象，700 多行就得全部重新 patch。
 *
 * 所以给每个被就地改过的实体打一个自增版本号，`v-memo` 把它和对象引用一起比较：
 * 只有真正被改过的那几行会失效。
 *
 * 用 WeakMap 存，实体被垃圾回收时版本号跟着走，不需要手动清理。
 */
const versions = new WeakMap()

export function bumpEntityVersion(entity) {
  if (!entity) return
  versions.set(entity, (versions.get(entity) || 0) + 1)
}

export function entityVersion(entity) {
  return entity ? versions.get(entity) || 0 : 0
}
