export const CALENDAR_LONG_PRESS_MS = 400
export const CALENDAR_TOUCH_SLOP = 8

export function createGestureState(point, touch = false) {
  return { phase: touch ? 'pending' : 'active', startX: point.clientX, startY: point.clientY, touch }
}

export function moveGestureState(state, point) {
  if (state.phase !== 'pending') return state
  const distance = Math.hypot(point.clientX - state.startX, point.clientY - state.startY)
  return distance > CALENDAR_TOUCH_SLOP ? { ...state, phase: 'cancelled' } : state
}

export function activateGestureState(state) {
  return state.phase === 'pending' ? { ...state, phase: 'active' } : state
}

// 触摸始终保留 touch-action:auto。必须在首个 touchmove 前注册非 passive 监听，
// 只在长按成功且浏览器仍允许取消时接管；不能靠中途修改 touch-action 夺回手势。
export function createCalendarGesture(host = window) {
  let session = null
  let suppressClick = false

  function detach(current) {
    host.clearTimeout(current.timer)
    for (const [type, listener, options] of current.listeners) host.removeEventListener(type, listener, options)
  }

  function finish(cancelled = false, point) {
    const current = session
    if (!current) return
    session = null
    detach(current)
    if (cancelled || (current.state.touch && current.state.phase === 'active')) suppressClick = true
    if (cancelled) current.callbacks.cancel?.()
    else current.callbacks.end?.(point, current.state.phase === 'active')
  }

  function listen(current, type, callback, options) {
    host.addEventListener(type, callback, options)
    current.listeners.push([type, callback, options])
  }

  function start(event, callbacks, touch) {
    if (touch ? event.touches.length !== 1 : event.pointerType === 'touch' || event.button !== 0) return false
    finish(true)
    suppressClick = false
    const point = touch ? event.touches[0] : event
    const current = { state: createGestureState(point, touch), callbacks, listeners: [], timer: null }
    session = current
    const cancel = () => finish(true)
    listen(current, 'blur', cancel)
    if (touch) {
      const id = point.identifier
      const find = touches => Array.from(touches).find(item => item.identifier === id)
      current.timer = host.setTimeout(() => {
        if (session !== current) return
        current.state = activateGestureState(current.state)
        callbacks.activate?.(point)
      }, CALENDAR_LONG_PRESS_MS)
      listen(current, 'touchstart', next => { if (next.touches.length !== 1) cancel() }, { capture: true, passive: true })
      listen(current, 'touchmove', next => {
        const moved = find(next.touches)
        if (!moved || next.touches.length !== 1) return cancel()
        // 浏览器已经接管的触摸滚动应立即取消编辑，包括长按等待阶段。
        if (!next.cancelable) return cancel()
        current.state = moveGestureState(current.state, moved)
        if (current.state.phase === 'cancelled') return cancel()
        if (current.state.phase !== 'active') return
        next.preventDefault()
        callbacks.move?.(moved)
      }, { passive: false })
      listen(current, 'touchend', next => {
        const ended = find(next.changedTouches)
        if (ended) finish(false, ended)
      })
      listen(current, 'touchcancel', cancel)
    } else {
      const id = event.pointerId
      callbacks.activate?.(point)
      listen(current, 'pointermove', next => { if (next.pointerId === id) callbacks.move?.(next) })
      listen(current, 'pointerup', next => { if (next.pointerId === id) finish(false, next) })
      listen(current, 'pointercancel', next => { if (next.pointerId === id) cancel() })
      listen(current, 'lostpointercapture', next => { if (next.pointerId === id) cancel() })
    }
    return true
  }

  return {
    startPointer: (event, callbacks) => start(event, callbacks, false),
    startTouch: (event, callbacks) => start(event, callbacks, true),
    cancel: () => finish(true),
    suppressClick: () => { suppressClick = true },
    resetClick: () => { suppressClick = false },
    guardClick(event) {
      if (!suppressClick || event.detail === 0) return
      suppressClick = false
      event.preventDefault()
      event.stopPropagation()
    },
    guardContextMenu(event) {
      if (session?.state.touch) event.preventDefault()
    },
    dispose() {
      finish(true)
      suppressClick = false
    }
  }
}
