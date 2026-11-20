import test from 'node:test'
import assert from 'node:assert/strict'
import { moveChecklistItem, moveChecklistItemBy } from './taskChecklist.js'

test('checklist reordering preserves item data and moves rows to the requested index', () => {
  const items = [
    { id: 'a', title: 'First', completed: true },
    { id: 'b', title: 'Second', completed: false },
    { id: 'c', title: 'Third', completed: true },
  ]

  assert.deepEqual(moveChecklistItem(items, 0, 2), [items[1], items[2], items[0]])
  assert.deepEqual(moveChecklistItem(items, 2, 0), [items[2], items[0], items[1]])
  assert.deepEqual(items.map(item => item.id), ['a', 'b', 'c'])
})

test('checklist reordering leaves invalid and same-position moves unchanged', () => {
  const items = [{ id: 'a' }, { id: 'b' }]
  assert.equal(moveChecklistItem(items, -1, 1), items)
  assert.equal(moveChecklistItem(items, 0, 2), items)
  assert.equal(moveChecklistItem(items, 1, 1), items)
})

test('上下移动一步与拖拽落到相邻位置等价，越界时保持原数组', () => {
  const items = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]

  assert.deepEqual(moveChecklistItemBy(items, 0, 1), moveChecklistItem(items, 0, 1))
  assert.deepEqual(moveChecklistItemBy(items, 2, -1).map(item => item.id), ['a', 'c', 'b'])
  assert.deepEqual(moveChecklistItemBy(items, 1, 1).map(item => item.id), ['a', 'c', 'b'])
  assert.deepEqual(moveChecklistItemBy(items, 1, -1).map(item => item.id), ['b', 'a', 'c'])

  assert.equal(moveChecklistItemBy(items, 0, -1), items)
  assert.equal(moveChecklistItemBy(items, 2, 1), items)
  assert.equal(moveChecklistItemBy([], 0, 1).length, 0)
  assert.equal(items.map(item => item.id).join(''), 'abc')
})
