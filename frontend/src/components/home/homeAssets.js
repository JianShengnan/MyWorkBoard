/**
 * 首页「账户资产」卡片的取数口径。
 *
 * 余额本身不是新数据：账本页早就有账户投影（`accountBalancesAt` 会在非当日
 * 日期上按流水回放），这里只做三件事——过滤掉隐藏/删除的账户、把资产与负债
 * 拆开、整理成卡片要的前 N 行。
 *
 * 保持纯函数，便于对「信用卡算负债」「转账不多算一次」这类口径做单测。
 */

import { accountBalancesAt, isLiabilityAccount } from '../ledger/ledgerAccounting.js'
import { dateKeyOf } from './homeMetrics.js'

const TYPE_LABELS = {
  cash: '现金',
  bank: '银行卡',
  wallet: '电子钱包',
  card: '信用卡',
  credit: '信用卡',
  credit_card: '信用卡',
  loan: '借贷'
}

export function accountTypeLabel(type) {
  return TYPE_LABELS[String(type || '').toLowerCase()] || '其他'
}

/**
 * 汇总账户资产。
 *
 * - 资产 = 非负债账户的正余额之和（透支的储蓄卡不参与，避免把负数当作资产减项）；
 * - 负债 = 负债账户的欠款绝对值之和（信用卡余额为负表示欠款）；
 * - 备付金额（借出未收回）沿用账本口径，不在这里重复计算。
 */
export function assetOverview(accounts, transactions, { today = new Date(), limit = 3 } = {}) {
  const alive = (accounts || []).filter(account => account && !account.deleted && !account.hidden)
  if (!alive.length) return { net: 0, assets: 0, liabilities: 0, rows: [], count: 0 }

  const todayKey = dateKeyOf(today)
  const balances = accountBalancesAt(alive, transactions || [], todayKey, { today: todayKey })
    .map(account => {
      const liability = isLiabilityAccount(account)
      const amount = Number(account.balance || 0)
      return {
        id: String(account.id ?? account.publicId ?? ''),
        name: String(account.name || '未命名账户'),
        icon: account.icon || '',
        type: account.accountType || '',
        typeLabel: accountTypeLabel(account.accountType),
        liability,
        balance: amount
      }
    })

  const assets = balances
    .filter(row => !row.liability)
    .reduce((sum, row) => sum + Math.max(0, row.balance), 0)
  const liabilities = balances
    .filter(row => row.liability)
    .reduce((sum, row) => sum + Math.max(0, -row.balance), 0)

  const scale = Math.max(1, ...balances.map(row => Math.abs(row.balance)))
  const rows = [...balances]
    .sort((left, right) => Math.abs(right.balance) - Math.abs(left.balance))
    .slice(0, Math.max(1, limit))
    .map(row => ({ ...row, share: Math.abs(row.balance) / scale }))

  return { net: assets - liabilities, assets, liabilities, rows, count: balances.length }
}
