import assert from 'node:assert/strict'
import { test } from 'node:test'
import { copyText } from './clipboard.js'

/** 造一个能记录调用的假 textarea */
function fakeTextarea() {
  return {
    value: '',
    style: { cssText: '' },
    setAttribute() {},
    focus() {},
    select() { this.selected = true },
    setSelectionRange() {},
    remove() { this.removed = true }
  }
}

/** contentEditable 兜底用的假 div */
function fakeEditable() {
  return {
    textContent: '',
    style: { cssText: '' },
    setAttribute() {},
    focus() {},
    remove() { this.removed = true }
  }
}

function fakeDocument({ execResult = true, execThrows = false } = {}) {
  const appended = []
  const removed = []
  return {
    body: {
      appendChild(node) { appended.push(node); return node }
    },
    createElement(tag) {
      const node = tag === 'div' ? fakeEditable() : fakeTextarea()
      const originalRemove = node.remove.bind(node)
      node.remove = () => { removed.push(node); originalRemove() }
      return node
    },
    createRange: () => ({ selectNodeContents() {} }),
    getSelection: () => null,
    execCommand(command) {
      assert.equal(command, 'copy')
      if (execThrows) throw new Error('execCommand blocked')
      return execResult
    },
    __appended: appended,
    __removed: removed
  }
}

async function withGlobals(globals, run) {
  const backup = {}
  for (const key of Object.keys(globals)) {
    backup[key] = Object.getOwnPropertyDescriptor(globalThis, key)
    Object.defineProperty(globalThis, key, { value: globals[key], configurable: true, writable: true })
  }
  try {
    // 必须 await：copyText 是异步的，不 await 会在第一个 await 前就还原全局，
    // 导致回退分支看到的是原始环境（曾让「writeText 被拒后回退」这条用例假失败）。
    return await run()
  } finally {
    for (const key of Object.keys(globals)) {
      if (backup[key]) Object.defineProperty(globalThis, key, backup[key])
      else delete globalThis[key]
    }
  }
}

test('安全上下文：直接用 navigator.clipboard.writeText', async () => {
  const written = []
  const doc = fakeDocument()
  const ok = await withGlobals(
    { navigator: { clipboard: { writeText: async v => { written.push(v) } } }, document: doc },
    () => copyText('hello')
  )
  assert.equal(ok, true)
  assert.deepEqual(written, ['hello'])
  assert.equal(doc.__appended.length, 0, '不应走兜底')
})

test('线上 HTTP（clipboard 为 undefined）：走 execCommand 兜底', async () => {
  const doc = fakeDocument()
  const ok = await withGlobals({ navigator: {}, document: doc }, () => copyText('http 场景'))
  assert.equal(ok, true)
  assert.equal(doc.__appended.length, 1)
  assert.equal(doc.__appended[0].value, 'http 场景')
  assert.equal(doc.__removed.length, 1, '临时 textarea 必须清理掉')
})

test('兜底元素必须留在视口内且不是全透明', async () => {
  // 回归：早期用 left:-9999px + opacity:0，Chrome 会裁掉选区，
  // execCommand 返回 true 但剪贴板为空，用户看到「已复制」却粘贴不出东西。
  const doc = fakeDocument()
  await withGlobals({ navigator: {}, document: doc }, () => copyText('视口内'))
  const style = doc.__appended[0].style.cssText
  assert.ok(!style.includes('-9999px'), `不能在视口外：${style}`)
  assert.ok(!/opacity:\s*0\s*[;$]/.test(style), `不能完全透明：${style}`)
  assert.ok(style.includes('position:fixed'), '必须 fixed 定位，避免影响布局')
})

test('textarea 方案失败后回退 contentEditable', async () => {
  let calls = 0
  const doc = fakeDocument()
  const original = doc.execCommand
  doc.execCommand = command => {
    calls += 1
    // 第一次（textarea）失败，第二次（contentEditable）成功
    return calls === 1 ? false : original.call(doc, command)
  }
  const ok = await withGlobals({ navigator: {}, document: doc }, () => copyText('二级兜底'))
  assert.equal(ok, true)
  assert.equal(calls, 2, '两个方案都要尝试')
  assert.equal(doc.__appended.length, 2, 'textarea + div 各一个')
})

test('writeText 被拒（权限/非聚焦）：回退 execCommand', async () => {
  const doc = fakeDocument()
  const ok = await withGlobals(
    { navigator: { clipboard: { writeText: async () => { throw new Error('NotAllowedError') } } }, document: doc },
    () => copyText('fallback')
  )
  assert.equal(ok, true)
  assert.equal(doc.__appended.length, 1)
})

test('execCommand 抛错：最终抛异常，让调用方 catch 提示失败', async () => {
  const doc = fakeDocument({ execThrows: true })
  await assert.rejects(
    () => withGlobals({ navigator: {}, document: doc }, () => copyText('x')),
    /复制失败/
  )
  // 两级兜底各挂一个临时节点，都要清理干净
  assert.equal(doc.__removed.length, doc.__appended.length, '所有临时节点都必须清理')
  assert.ok(doc.__appended.length > 0)
})

test('execCommand 返回 false：同样抛异常', async () => {
  const doc = fakeDocument({ execResult: false })
  await assert.rejects(
    () => withGlobals({ navigator: {}, document: doc }, () => copyText('x')),
    /复制失败/
  )
})

test('非字符串入参不报错', async () => {
  const written = []
  await withGlobals(
    { navigator: { clipboard: { writeText: async v => { written.push(v) } } }, document: fakeDocument() },
    () => copyText(null)
  )
  assert.deepEqual(written, [''])
})
