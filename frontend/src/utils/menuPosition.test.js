import test from 'node:test'
import assert from 'node:assert/strict'
import { clampMenuPoint } from './menuPosition.js'

test('菜单锚点在视口内时原样保留', () => {
  assert.deepEqual(clampMenuPoint(120, 90, 168, 140, 1280, 800), { x: 120, y: 90 })
})

test('菜单超出右下边缘时拉回视口，并留出 pad', () => {
  assert.deepEqual(clampMenuPoint(1270, 790, 168, 140, 1280, 800), { x: 1280 - 168 - 8, y: 800 - 140 - 8 })
})

test('负坐标或菜单比视口更宽时收敛到 pad，不会出现负数位置', () => {
  assert.deepEqual(clampMenuPoint(-20, -10, 100, 80, 320, 640), { x: 8, y: 8 })
  assert.deepEqual(clampMenuPoint(10, 10, 400, 700, 320, 640), { x: 8, y: 8 })
})

test('自定义留白与非法数值都按 pad 处理', () => {
  assert.deepEqual(clampMenuPoint(0, 0, 100, 60, 200, 200, 12), { x: 12, y: 12 })
  assert.deepEqual(clampMenuPoint('abc', null, 100, 60, 200, 200, 12), { x: 12, y: 12 })
})
