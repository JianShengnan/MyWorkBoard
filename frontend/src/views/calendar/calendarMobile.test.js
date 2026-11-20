import test from 'node:test'
import assert from 'node:assert/strict'
import {
  FILTER_DRAWER_ACTIONS, MOBILE_MAX_WIDTH, countActiveFilters, filterBadgeText, isMobileWidth, reduceFilterDrawer
} from './calendarMobile.js'

test('isMobileWidth 与 1023 断点一致', () => {
  assert.equal(MOBILE_MAX_WIDTH, 1023)
  assert.equal(isMobileWidth(390), true)
  assert.equal(isMobileWidth(768), true)
  assert.equal(isMobileWidth(1023), true)
  assert.equal(isMobileWidth(1024), false)
  assert.equal(isMobileWidth(1440), false)
})

test('isMobileWidth 对非法输入退回桌面布局', () => {
  assert.equal(isMobileWidth(undefined), false)
  assert.equal(isMobileWidth(null), false)
  assert.equal(isMobileWidth(''), false)
  assert.equal(isMobileWidth('390'), true)
})

test('countActiveFilters：所有状态不显示徽标', () => {
  assert.equal(countActiveFilters({ mode: 'all', listIds: [], tagIds: [], filterId: '' }), 0)
  assert.equal(countActiveFilters(null), 0)
  assert.equal(countActiveFilters({}), 0)
})

test('countActiveFilters：自定义范围累加清单与标签', () => {
  assert.equal(countActiveFilters({ mode: 'custom', listIds: ['a', 'b'], tagIds: ['t'] }), 3)
  assert.equal(countActiveFilters({ mode: 'custom', listIds: [], tagIds: ['t1', 't2'] }), 2)
  assert.equal(countActiveFilters({ mode: 'custom', listIds: [], tagIds: [] }), 0)
  assert.equal(countActiveFilters({ mode: 'custom', listIds: null, tagIds: undefined }), 0)
})

test('countActiveFilters：过滤器生效算 1，没选具体过滤器算 0', () => {
  assert.equal(countActiveFilters({ mode: 'filter', filterId: 'f-1', listIds: [], tagIds: [] }), 1)
  assert.equal(countActiveFilters({ mode: 'filter', filterId: '', listIds: ['a'], tagIds: ['b'] }), 0)
})

test('filterBadgeText：0 给空串，超过 99 收成 99+', () => {
  assert.equal(filterBadgeText(0), '')
  assert.equal(filterBadgeText(-3), '')
  assert.equal(filterBadgeText(1), '1')
  assert.equal(filterBadgeText(12), '12')
  assert.equal(filterBadgeText(99), '99')
  assert.equal(filterBadgeText(120), '99+')
  assert.equal(filterBadgeText(undefined), '')
})

test('reduceFilterDrawer：open 打开，其余关闭来源都能关上', () => {
  assert.equal(reduceFilterDrawer(false, 'open'), true)
  assert.equal(reduceFilterDrawer(true, 'open'), true)
  for (const action of ['close', 'backdrop', 'escape', 'done']) {
    assert.equal(reduceFilterDrawer(true, action), false, `${action} 应关闭抽屉`)
  }
  for (const action of FILTER_DRAWER_ACTIONS.filter(item => item !== 'open')) {
    assert.equal(reduceFilterDrawer(true, action), false, `${action} 应关闭抽屉`)
  }
})

test('reduceFilterDrawer：未知动作不改变状态（清空筛选不会误关）', () => {
  assert.equal(reduceFilterDrawer(true, 'clear'), true)
  assert.equal(reduceFilterDrawer(false, 'clear'), false)
  assert.equal(reduceFilterDrawer(true, undefined), true)
  assert.equal(reduceFilterDrawer(false, undefined), false)
})
