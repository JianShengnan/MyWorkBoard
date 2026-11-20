import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PREFERENCE_DEVICES, VALID_PREFERENCE_VIEWS, mergeDevicePreferences, normalizeSpans, preferenceDevice, resolveDevicePreferences
} from './calendarPreferences.js'

const DEFAULTS = { multiDay: 5, multiWeek: 6 }

test('preferenceDevice：按 isMobileWidth 结果落到 mobile / desktop', () => {
  assert.deepEqual(PREFERENCE_DEVICES, ['desktop', 'mobile'])
  assert.equal(preferenceDevice(true), 'mobile')
  assert.equal(preferenceDevice(false), 'desktop')
  assert.equal(preferenceDevice(undefined), 'desktop')
})

test('view 合法集合同时包含普通视图与跨度视图', () => {
  for (const key of ['day', 'week', 'month', 'year', 'agenda', 'multiDay', 'multiWeek']) {
    assert.ok(VALID_PREFERENCE_VIEWS.includes(key), `${key} 应合法`)
  }
})

test('resolveDevicePreferences：空存储时手机与桌面都回到 month + 默认跨度', () => {
  for (const device of ['mobile', 'desktop']) {
    assert.deepEqual(resolveDevicePreferences(undefined, device), { view: 'month', spans: DEFAULTS })
    assert.deepEqual(resolveDevicePreferences({}, device), { view: 'month', spans: DEFAULTS })
  }
})

test('resolveDevicePreferences：桌面读取旧版顶层偏好（升级迁移）', () => {
  const legacy = { view: 'agenda', spans: { multiDay: 8, multiWeek: 3 }, colorSettings: { task: '#000' } }
  assert.deepEqual(resolveDevicePreferences(legacy, 'desktop'), {
    view: 'agenda', spans: { multiDay: 8, multiWeek: 3 }
  })
})

test('resolveDevicePreferences：手机首次进入不继承旧版高层视图', () => {
  const legacy = { view: 'year', spans: { multiDay: 12, multiWeek: 2 } }
  assert.deepEqual(resolveDevicePreferences(legacy, 'mobile'), { view: 'month', spans: DEFAULTS })
})

test('resolveDevicePreferences：设备各自恢复自己的 view 与 spans', () => {
  const stored = {
    view: 'year',
    devices: {
      desktop: { view: 'multiWeek', spans: { multiDay: 6, multiWeek: 4 } },
      mobile: { view: 'agenda', spans: { multiDay: 3, multiWeek: 2 } }
    }
  }
  assert.deepEqual(resolveDevicePreferences(stored, 'mobile'), {
    view: 'agenda', spans: { multiDay: 3, multiWeek: 2 }
  })
  assert.deepEqual(resolveDevicePreferences(stored, 'desktop'), {
    view: 'multiWeek', spans: { multiDay: 6, multiWeek: 4 }
  })
})

test('resolveDevicePreferences：非法 view / 跨度安全回退', () => {
  const stored = { devices: { mobile: { view: 'decade', spans: { multiDay: 'abc', multiWeek: null } } } }
  assert.deepEqual(resolveDevicePreferences(stored, 'mobile'), { view: 'month', spans: DEFAULTS })
})

test('resolveDevicePreferences：跨度按 SPAN_CONFIG 夹紧', () => {
  const stored = { devices: { mobile: { view: 'month', spans: { multiDay: 99, multiWeek: 1 } } } }
  assert.deepEqual(resolveDevicePreferences(stored, 'mobile'), {
    view: 'month', spans: { multiDay: 14, multiWeek: 2 }
  })
})

test('resolveDevicePreferences：损坏的存储结构不影响默认值', () => {
  for (const stored of [null, undefined, 'oops', 42, [], { devices: 'oops' }, { devices: { mobile: [] } }]) {
    assert.deepEqual(resolveDevicePreferences(stored, 'mobile'), { view: 'month', spans: DEFAULTS })
  }
})

test('normalizeSpans：非对象与空串都回退默认跨度', () => {
  assert.deepEqual(normalizeSpans(null), DEFAULTS)
  assert.deepEqual(normalizeSpans(''), DEFAULTS)
  assert.deepEqual(normalizeSpans({ multiDay: '', multiWeek: undefined }), DEFAULTS)
  assert.deepEqual(normalizeSpans({ multiDay: '7', multiWeek: '3' }), { multiDay: 7, multiWeek: 3 })
})

test('mergeDevicePreferences：只写当前设备，另一个设备条目不动', () => {
  const stored = {
    view: 'year',
    spans: { multiDay: 9, multiWeek: 5 },
    colorSettings: { task: '#111' },
    devices: { desktop: { view: 'year', spans: { multiDay: 9, multiWeek: 5 } } }
  }
  const merged = mergeDevicePreferences(stored, 'mobile', { view: 'agenda', spans: { multiDay: 2, multiWeek: 4 } })
  assert.deepEqual(merged.devices.mobile, { view: 'agenda', spans: { multiDay: 2, multiWeek: 4 } })
  assert.deepEqual(merged.devices.desktop, { view: 'year', spans: { multiDay: 9, multiWeek: 5 } })
  assert.deepEqual(merged.colorSettings, { task: '#111' })
  assert.equal(merged.view, 'year')
})

test('mergeDevicePreferences：写桌面时同步顶层字段（旧版可读）', () => {
  const merged = mergeDevicePreferences({}, 'desktop', { view: 'week', spans: { multiDay: 4, multiWeek: 2 } })
  assert.equal(merged.view, 'week')
  assert.deepEqual(merged.spans, { multiDay: 4, multiWeek: 2 })
  assert.deepEqual(merged.devices.desktop, { view: 'week', spans: { multiDay: 4, multiWeek: 2 } })
})

test('mergeDevicePreferences：手机先保存时保留旧版桌面顶层值', () => {
  const legacy = { view: 'agenda', spans: { multiDay: 8, multiWeek: 3 } }
  const merged = mergeDevicePreferences(legacy, 'mobile', { view: 'month', spans: DEFAULTS })
  assert.equal(merged.view, 'agenda')
  assert.deepEqual(merged.spans, { multiDay: 8, multiWeek: 3 })
  assert.deepEqual(resolveDevicePreferences(merged, 'desktop'), { view: 'agenda', spans: { multiDay: 8, multiWeek: 3 } })
})

test('mergeDevicePreferences：非法 view 回落 month，非法跨度回落默认值', () => {
  const merged = mergeDevicePreferences({}, 'mobile', { view: 'decade', spans: { multiDay: 0, multiWeek: 99 } })
  assert.deepEqual(merged.devices.mobile, { view: 'month', spans: { multiDay: 1, multiWeek: 6 } })
})

test('mergeDevicePreferences：损坏存储不抛错', () => {
  for (const stored of [null, undefined, 'oops', []]) {
    const merged = mergeDevicePreferences(stored, 'mobile', { view: 'month', spans: DEFAULTS })
    assert.deepEqual(merged.devices.mobile, { view: 'month', spans: DEFAULTS })
  }
})
