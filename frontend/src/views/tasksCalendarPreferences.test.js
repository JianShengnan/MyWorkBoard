import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { SPAN_CONFIG, clampSpan, resolveRange } from './calendar/calendarRange.js'

const view = await readFile(new URL('./TasksCalendarView.vue', import.meta.url), 'utf8')

test('calendar preferences clamp span views to the supported ranges', () => {
  assert.equal(clampSpan('multiDay', 20), SPAN_CONFIG.multiDay.max)
  assert.equal(clampSpan('multiWeek', 1), SPAN_CONFIG.multiWeek.min)
  // 跨度夹紧移到 calendarPreferences.js 的 resolveDevicePreferences / normalizeSpans 里
  assert.match(view, /import \{ mergeDevicePreferences, preferenceDevice, resolveDevicePreferences \} from '\.\/calendar\/calendarPreferences\.js'/)
  assert.match(view, /const prefs = resolveDevicePreferences\(stored, currentDevice\(\)\)/)
  assert.match(view, /Object\.assign\(spans, prefs\.spans\)/)
})

test('calendar preferences persist view, spans, colors and scope per account', () => {
  assert.match(view, /const preferenceKey = computed\(\(\) => `calendar_view_v1:\$\{appStore\.authUser\?\.id \|\| 'local'\}`\)/)
  assert.match(view, /const payload = mergeDevicePreferences\(readStoredPreferences\(\), currentDevice\(\), \{/)
  assert.match(view, /colorSettings: \{ \.\.\.colorSettings \}, scope: scope\.value/)
  assert.match(view, /const currentDevice = \(\) => preferenceDevice\(mobile\.value\)/)
})

test('span views keep their own remembered size when switching views', () => {
  const multiDay = resolveRange({ view: 'multiDay', span: 3, anchor: new Date(2026, 9, 19) })
  const multiWeek = resolveRange({ view: 'multiWeek', span: 6, anchor: new Date(2026, 9, 19) })
  assert.equal(multiDay.days.length, 3)
  assert.equal(multiWeek.days.length, 42)
  assert.match(view, /const range = computed\(\(\) => resolveRange\(\{/)
  assert.match(view, /view: view\.value, span: spans\[view\.value\], anchor: anchor\.value,/)
})
