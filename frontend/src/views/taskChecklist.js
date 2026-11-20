export function moveChecklistItem(items, from, to) {
  if (!Array.isArray(items) || from < 0 || from >= items.length || to < 0 || to >= items.length || from === to) {
    return items
  }
  const result = [...items]
  const [item] = result.splice(from, 1)
  result.splice(to, 0, item)
  return result
}

/** 上移 / 下移：offset 为 -1 / +1，越界时原样返回，和拖拽用同一套落点语义 */
export function moveChecklistItemBy(items, index, offset) {
  return moveChecklistItem(items, index, index + offset)
}
