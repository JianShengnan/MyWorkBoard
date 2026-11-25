<template>
  <HomeWidgetFrame title="账户资产" kicker="ASSETS" :meta="metaLabel" :editing="editing">
    <template #actions>
      <router-link class="as-more" to="/ledger">账本 →</router-link>
    </template>

    <div v-if="!overview.count" class="hw-empty">
      <b>还没有账户</b>
      <span>在账本里添加银行卡、现金或信用卡后，这里按余额汇总</span>
      <router-link class="hw-btn" to="/ledger">去账本添加</router-link>
    </div>

    <div v-else class="as-body">
      <div class="as-net">
        <small>净资产</small>
        <b :class="{ 'is-negative': overview.net < 0 }">{{ signed(overview.net) }}</b>
        <div class="as-split">
          <span><i class="as-dot is-asset" aria-hidden="true"></i>资产 {{ compact(overview.assets) }}</span>
          <span><i class="as-dot is-debt" aria-hidden="true"></i>负债 {{ compact(overview.liabilities) }}</span>
        </div>
      </div>

      <ul class="as-list" aria-label="余额最大的账户">
        <li v-for="row in overview.rows" :key="row.id">
          <div class="as-line">
            <span class="as-name" :title="row.name">{{ row.name }}</span>
            <small>{{ row.typeLabel }}</small>
            <b :class="{ 'is-liability': row.liability }">{{ signed(row.balance) }}</b>
          </div>
          <div class="as-track" :class="{ 'is-liability': row.liability }">
            <i :style="{ width: `${Math.max(4, Math.round(row.share * 100))}%` }" />
          </div>
        </li>
      </ul>
    </div>

    <template #footer>
      <span>按账本余额汇总 · 转账不计入</span>
      <router-link class="as-more" to="/ledger/reports">资产报表 →</router-link>
    </template>
  </HomeWidgetFrame>
</template>

<script setup>
import { computed } from 'vue'
import HomeWidgetFrame from './HomeWidgetFrame.vue'
import { useLedgerStore } from '../../stores/ledger.js'
import { assetOverview } from './homeAssets.js'

defineProps({ editing: Boolean })

const ledgerStore = useLedgerStore()

const overview = computed(() => assetOverview(ledgerStore.accounts, ledgerStore.transactions, {
  today: new Date(),
  limit: 3
}))

const metaLabel = computed(() => {
  const { count, liabilities } = overview.value
  if (!count) return '按账户余额汇总'
  return liabilities > 0 ? `${count} 个账户 · 含信用卡欠款` : `${count} 个账户`
})

/** 卡片窄，五位数以上折成「万」，避免金额把账户名挤没 */
function compact(value) {
  const amount = Math.abs(Number(value || 0))
  const text = amount >= 10000 ? `${(amount / 10000).toFixed(1)} 万` : amount.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
  return `¥${text}`
}

function signed(value) {
  const amount = Number(value || 0)
  return `${amount < 0 ? '−' : ''}${compact(amount)}`
}
</script>

<style scoped>
.as-more { color: var(--accent); font-size: 11px; font-weight: 650; text-decoration: none; }
.as-body { display: flex; height: 100%; min-height: 0; flex-direction: column; gap: 10px; }
.as-net { display: flex; flex-direction: column; gap: 2px; }
.as-net small { color: var(--muted); font-size: 10.5px; }
.as-net b { color: var(--ink); font-size: 26px; font-weight: 730; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.as-net b.is-negative { color: var(--down); }
.as-split { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 2px; color: var(--ink2); font-size: 10.5px; }
.as-split span { display: inline-flex; align-items: center; gap: 4px; }
.as-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; }
.as-dot.is-asset { background: var(--accent); }
.as-dot.is-debt { background: color-mix(in srgb, var(--down) 70%, transparent); }
.as-list { display: flex; min-height: 0; flex: 1; flex-direction: column; gap: 8px; overflow-y: auto; margin: 0; padding: 0; list-style: none; scrollbar-width: thin; }
.as-list li { display: grid; gap: 4px; }
.as-line { display: flex; align-items: baseline; gap: 6px; }
.as-name { min-width: 0; flex: 1; overflow: hidden; color: var(--ink); font-size: 12px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
.as-line small { flex: none; color: var(--muted); font-size: 10px; }
.as-line b { flex: none; color: var(--ink); font-size: 12px; font-weight: 700; font-variant-numeric: tabular-nums; }
.as-line b.is-liability { color: var(--down); }
.as-track { height: 4px; overflow: hidden; border-radius: 999px; background: color-mix(in srgb, var(--ink) 7%, transparent); }
.as-track i { display: block; height: 100%; border-radius: inherit; background: color-mix(in srgb, var(--accent) 70%, var(--line)); }
.as-track.is-liability i { background: color-mix(in srgb, var(--down) 60%, var(--line)); }
@media (max-width: 900px) {
  .as-net b { font-size: 30px; }
  .as-list { gap: 10px; }
}
</style>
