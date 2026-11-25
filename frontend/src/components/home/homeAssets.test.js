import test from 'node:test'
import assert from 'node:assert/strict'
import { accountTypeLabel, assetOverview } from './homeAssets.js'

const TODAY = new Date(2026, 10, 25) // 2026-11-25

test('splits assets and liabilities and nets them out', () => {
  const accounts = [
    { id: 'bank', name: '招商银行', accountType: 'bank', balance: 12000 },
    { id: 'cash', name: '现金', accountType: 'cash', balance: 300 },
    { id: 'card', name: '招行信用卡', accountType: 'card', balance: -2500 }
  ]
  const overview = assetOverview(accounts, [], { today: TODAY })
  assert.equal(overview.assets, 12300)
  assert.equal(overview.liabilities, 2500)
  assert.equal(overview.net, 9800)
  assert.equal(overview.count, 3)
})

test('ignores hidden and deleted accounts', () => {
  const accounts = [
    { id: 'bank', name: '储蓄卡', accountType: 'bank', balance: 500 },
    { id: 'old', name: '已销户', accountType: 'bank', balance: 999, deleted: true },
    { id: 'hide', name: '不想看', accountType: 'cash', balance: 888, hidden: true }
  ]
  const overview = assetOverview(accounts, [], { today: TODAY })
  assert.equal(overview.assets, 500)
  assert.equal(overview.count, 1)
  assert.deepEqual(overview.rows.map(row => row.id), ['bank'])
})

test('a negative savings balance is not counted as an asset nor as debt', () => {
  const accounts = [{ id: 'od', name: '透支卡', accountType: 'bank', balance: -80 }]
  const overview = assetOverview(accounts, [], { today: TODAY })
  assert.equal(overview.assets, 0)
  assert.equal(overview.liabilities, 0)
  assert.equal(overview.net, 0)
})

test('rows are ranked by absolute balance and capped by limit', () => {
  const accounts = [
    { id: 'a', name: 'A', accountType: 'bank', balance: 100 },
    { id: 'b', name: 'B', accountType: 'card', balance: -3000 },
    { id: 'c', name: 'C', accountType: 'cash', balance: 600 },
    { id: 'd', name: 'D', accountType: 'wallet', balance: 50 }
  ]
  const overview = assetOverview(accounts, [], { today: TODAY, limit: 2 })
  assert.deepEqual(overview.rows.map(row => row.id), ['b', 'c'])
  assert.equal(overview.rows[0].liability, true)
  assert.equal(overview.rows[0].share, 1)
  assert.equal(overview.rows[1].share, 0.2)
})

test('historical transfers are replayed without counting the same move twice', () => {
  const accounts = [
    { id: 'source', name: '工资卡', accountType: 'bank', openingBalance: 100, balance: 60 },
    { id: 'target', name: '余额宝', accountType: 'wallet', openingBalance: 0, balance: 40 }
  ]
  const transactions = [
    {
      kind: 'TRANSFER', storedKind: 'TRANSFER_OUT', accountId: 'source', targetAccountId: 'target',
      amount: 40, occurredOn: '2026-11-20', transferGroupId: 'g1'
    },
    {
      kind: 'TRANSFER', storedKind: 'TRANSFER_IN', accountId: 'source', targetAccountId: 'target',
      amount: 40, occurredOn: '2026-11-20', transferGroupId: 'g1'
    }
  ]
  const overview = assetOverview(accounts, transactions, { today: TODAY })
  assert.equal(overview.assets, 100)
  assert.equal(overview.rows.find(row => row.id === 'source').balance, 60)
})

test('empty ledger returns a zeroed overview instead of throwing', () => {
  const overview = assetOverview([], [], { today: TODAY })
  assert.deepEqual(overview, { net: 0, assets: 0, liabilities: 0, rows: [], count: 0 })
})

test('unknown account types fall back to a readable label', () => {
  assert.equal(accountTypeLabel('bank'), '银行卡')
  assert.equal(accountTypeLabel('card'), '信用卡')
  assert.equal(accountTypeLabel('crypto'), '其他')
  assert.equal(accountTypeLabel(null), '其他')
})
