import test from 'node:test'
import assert from 'node:assert/strict'
import { agentEntryStorageKey, parseAgentEntryPreferences, readAgentEntryPreferences, writeAgentEntryPreferences } from './agentEntryPreferences.js'

function memoryStorage() {
  const values = new Map()
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value) }
}

test('新用户桌面默认悬浮，移动默认贴边', () => {
  assert.deepEqual(parseAgentEntryPreferences(null), { mode: 'floating', side: 'right' })
  assert.deepEqual(parseAgentEntryPreferences(null, true), { mode: 'docked', side: 'right' })
})

test('三态与左右停靠都能还原，已有选择不受屏幕默认值影响', () => {
  for (const mode of ['floating', 'docked', 'hidden']) {
    for (const side of ['left', 'right']) {
      const raw = JSON.stringify({ mode, side })
      assert.deepEqual(parseAgentEntryPreferences(raw, true), { mode, side })
      assert.deepEqual(parseAgentEntryPreferences(raw, false), { mode, side })
    }
  }
})

test('损坏 JSON、非对象和未知状态安全回退，不透传额外字段', () => {
  for (const raw of ['{', 'null', 'false', '[]', '"hidden"', '42']) {
    assert.deepEqual(parseAgentEntryPreferences(raw, true), { mode: 'docked', side: 'right' })
  }
  assert.deepEqual(parseAgentEntryPreferences('{"mode":"invalid","side":"left"}', true), { mode: 'docked', side: 'left' })
  assert.deepEqual(parseAgentEntryPreferences({ mode: 'hidden', side: 'bottom', token: 'ignored' }), { mode: 'hidden', side: 'right' })
})

test('本设备同一账号持久化，各账号与匿名作用域互不覆盖', () => {
  const storage = memoryStorage()
  assert.equal(writeAgentEntryPreferences(storage, 'user:a', { mode: 'hidden', side: 'left' }), true)
  assert.equal(writeAgentEntryPreferences(storage, 'user:b', { mode: 'floating', side: 'right' }), true)
  assert.deepEqual(readAgentEntryPreferences(storage, 'user:a', true), { mode: 'hidden', side: 'left' })
  assert.deepEqual(readAgentEntryPreferences(storage, 'user:b', true), { mode: 'floating', side: 'right' })
  assert.deepEqual(readAgentEntryPreferences(storage, '', true), { mode: 'docked', side: 'right' })
  assert.notEqual(agentEntryStorageKey('user:a'), agentEntryStorageKey('user%3Aa'))
})

test('不可用存储与配额错误不抛异常，写入明确报告失败', () => {
  const storage = {
    getItem() { throw new Error('禁止读取') },
    setItem() { throw new Error('配额不足') }
  }
  assert.deepEqual(readAgentEntryPreferences(storage, 'user:a', true), { mode: 'docked', side: 'right' })
  assert.equal(writeAgentEntryPreferences(storage, 'user:a', { mode: 'hidden', side: 'left' }), false)
})

test('写回仅保留有效显示偏好，不保存会话或聊天内容', () => {
  const storage = memoryStorage()
  writeAgentEntryPreferences(storage, 'user:a', { mode: 'hidden', side: 'left', sessionId: 'secret', messages: ['secret'] })
  assert.deepEqual(JSON.parse(storage.getItem(agentEntryStorageKey('user:a'))), { mode: 'hidden', side: 'left' })
})
