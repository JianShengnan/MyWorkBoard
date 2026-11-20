import test from 'node:test'
import assert from 'node:assert/strict'
import {
  activateGestureState, CALENDAR_LONG_PRESS_MS, createCalendarGesture, createGestureState, moveGestureState
} from './calendarGesture.js'

const point = (x = 10, y = 20, identifier = 1) => ({ clientX: x, clientY: y, identifier })

function harness() {
  const listeners = new Map()
  const timers = new Map()
  let id = 0
  const host = {
    addEventListener(type, listener, options) {
      if (!listeners.has(type)) listeners.set(type, new Map())
      listeners.get(type).set(listener, options)
    },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener) },
    setTimeout(callback, delay) { timers.set(++id, { callback, delay }); return id },
    clearTimeout(key) { timers.delete(key) }
  }
  return {
    gesture: createCalendarGesture(host),
    fire(type, event = {}) { for (const listener of [...(listeners.get(type)?.keys() || [])]) listener(event) },
    advance() { for (const [key, timer] of [...timers]) { timers.delete(key); timer.callback() } },
    count: () => [...listeners.values()].reduce((sum, entries) => sum + entries.size, 0),
    timers,
    listeners
  }
}

function click(gesture, detail = 1) {
  let blocked = false
  gesture.guardClick({ detail, preventDefault() { blocked = true }, stopPropagation() {} })
  return blocked
}

function touchMove(h, touch, cancelable = true) {
  let prevented = false
  h.fire('touchmove', { touches: [touch], cancelable, preventDefault() { prevented = true } })
  return prevented
}

test('纯状态机：触摸等待，鼠标立即激活，二维越界后不能重新激活', () => {
  const touch = createGestureState(point(), true)
  assert.equal(touch.phase, 'pending')
  assert.equal(createGestureState(point()).phase, 'active')
  assert.equal(moveGestureState(touch, point(15, 25)).phase, 'pending')
  const cancelled = moveGestureState(touch, point(19, 20))
  assert.equal(cancelled.phase, 'cancelled')
  assert.equal(activateGestureState(cancelled).phase, 'cancelled')
  assert.equal(activateGestureState(touch).phase, 'active')
})

test('轻触不阻止滚动、不激活，释放清理并允许正常点击', () => {
  const h = harness()
  let active = false
  h.gesture.startTouch({ touches: [point()] }, { activate: () => { active = true } })
  assert.equal([...h.timers.values()][0].delay, CALENDAR_LONG_PRESS_MS)
  assert.equal(touchMove(h, point(13, 22)), false)
  h.fire('touchend', { changedTouches: [point()] })
  h.advance()
  assert.equal(active, false)
  assert.equal(h.count(), 0)
  assert.equal(h.timers.size, 0)
  assert.equal(click(h.gesture), false)
})

test('长按前横向或纵向滚动取消，不提交编辑并吞掉随后的误点击', () => {
  for (const moved of [point(30, 20), point(10, 40)]) {
    const h = harness()
    let cancelled = 0
    let activated = 0
    h.gesture.startTouch({ touches: [point()] }, {
      activate: () => { activated++ }, cancel: () => { cancelled++ }
    })
    assert.equal(touchMove(h, moved), false)
    h.advance()
    assert.equal(activated, 0)
    assert.equal(cancelled, 1)
    assert.equal(h.count(), 0)
    assert.equal(click(h.gesture), true)
    assert.equal(click(h.gesture), false)
  }
})

test('400ms长按后才阻止touchmove并编辑，释放不再误开事件', () => {
  const h = harness()
  let activated = 0
  let moved = 0
  let ended = 0
  h.gesture.startTouch({ touches: [point()] }, {
    activate: () => { activated++ }, move: () => { moved++ }, end: (_, active) => { if (active) ended++ }
  })
  assert.equal([...h.listeners.get('touchmove').values()][0].passive, false)
  h.advance()
  assert.equal(activated, 1)
  assert.equal(touchMove(h, point(10, 50)), true)
  assert.equal(moved, 1)
  h.fire('touchend', { changedTouches: [point(10, 50)] })
  assert.equal(ended, 1)
  assert.equal(h.count(), 0)
  assert.equal(click(h.gesture), true)
})

test('长按不移动松开仍标记active并吞兼容click，下一短tap恢复', () => {
  const h = harness()
  const ended = []
  h.gesture.startTouch({ touches: [point()] }, { end: (_, active) => ended.push(active) })
  h.advance()
  h.fire('touchend', { changedTouches: [point()] })
  assert.deepEqual(ended, [true])
  assert.equal(click(h.gesture), true)
  h.gesture.startTouch({ touches: [point()] }, { end: (_, active) => ended.push(active) })
  h.fire('touchend', { changedTouches: [point()] })
  assert.deepEqual(ended, [true, false])
  assert.equal(click(h.gesture), false)
  assert.equal(h.count(), 0)
})

test('鼠标up后显式吞兼容click，下一pointerdown恢复正常点击', () => {
  const h = harness()
  h.gesture.startPointer({ ...point(), pointerId: 1, pointerType: 'mouse', button: 0 }, {
    end: () => h.gesture.suppressClick()
  })
  h.fire('pointerup', { pointerId: 1 })
  assert.equal(click(h.gesture), true)
  h.gesture.startPointer({ ...point(), pointerId: 2, pointerType: 'mouse', button: 0 }, {})
  h.fire('pointerup', { pointerId: 2 })
  assert.equal(click(h.gesture), false)
  assert.equal(h.count(), 0)
})

test('原生触摸滚动已不可取消时放弃编辑', () => {
  const h = harness()
  let moved = 0
  let cancelled = 0
  h.gesture.startTouch({ touches: [point()] }, { move: () => { moved++ }, cancel: () => { cancelled++ } })
  h.advance()
  assert.equal(touchMove(h, point(10, 50), false), false)
  assert.equal(moved, 0)
  assert.equal(cancelled, 1)
  assert.equal(h.count(), 0)
})

test('触摸pointercancel不截断独立TouchEvent流，touchcancel才清理', () => {
  const h = harness()
  let cancelled = 0
  h.gesture.startTouch({ touches: [point()] }, { cancel: () => { cancelled++ } })
  h.fire('pointercancel', { pointerId: 1 })
  assert.equal(cancelled, 0)
  h.fire('touchcancel')
  assert.equal(cancelled, 1)
  assert.equal(h.count(), 0)
  assert.equal(h.timers.size, 0)
})

test('多指触摸、blur与卸载均清除长按timer和window监听', () => {
  for (const action of ['multi', 'blur', 'dispose']) {
    const h = harness()
    let activated = 0
    let cancelled = 0
    h.gesture.startTouch({ touches: [point()] }, { activate: () => { activated++ }, cancel: () => { cancelled++ } })
    if (action === 'multi') h.fire('touchstart', { touches: [point(), point(20, 20, 2)] })
    else if (action === 'blur') h.fire('blur')
    else h.gesture.dispose()
    h.advance()
    assert.equal(activated, 0)
    assert.equal(cancelled, 1)
    assert.equal(h.count(), 0)
    assert.equal(h.timers.size, 0)
  }
})

test('鼠标维持立即拖动，只处理匹配pointerId，取消或capture丢失不提交', () => {
  for (const type of ['pointercancel', 'lostpointercapture', 'pointerup']) {
    const h = harness()
    let activated = 0
    let moved = 0
    let cancelled = 0
    let ended = 0
    h.gesture.startPointer({ ...point(), pointerId: 7, pointerType: 'mouse', button: 0 }, {
      activate: () => { activated++ }, move: () => { moved++ }, cancel: () => { cancelled++ }, end: () => { ended++ }
    })
    h.fire('pointermove', { ...point(), pointerId: 6 })
    h.fire('pointermove', { ...point(), pointerId: 7 })
    h.fire(type, { pointerId: 7 })
    assert.equal(activated, 1)
    assert.equal(moved, 1)
    assert.equal(cancelled, type === 'pointerup' ? 0 : 1)
    assert.equal(ended, type === 'pointerup' ? 1 : 0)
    assert.equal(h.count(), 0)
  }
})

test('忽略兼容触摸PointerEvent和右键，键盘点击不被吞，新手势恢复点击', () => {
  const h = harness()
  assert.equal(h.gesture.startPointer({ button: 0, pointerType: 'touch' }, {}), false)
  assert.equal(h.gesture.startPointer({ button: 2, pointerType: 'mouse' }, {}), false)
  h.gesture.suppressClick()
  assert.equal(click(h.gesture, 0), false)
  h.gesture.resetClick()
  assert.equal(click(h.gesture), false)
  assert.equal(h.count(), 0)
})
