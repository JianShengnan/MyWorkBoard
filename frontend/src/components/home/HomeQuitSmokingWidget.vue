<template>
  <HomeWidgetFrame title="戒烟" kicker="QUIT" :meta="metaLabel" :editing="editing">
    <template #actions>
      <router-link class="qw-more" to="/tools/quit">详情 →</router-link>
    </template>

    <div v-if="!store.loaded" class="qw-empty"><p>正在读取戒烟记录…</p></div>

    <div v-else-if="!store.configured" class="qw-empty">
      <p>还没开始记录戒烟</p>
      <router-link class="hw-btn" to="/tools/quit">去设置开始时间</router-link>
    </div>

    <template v-else>
      <div class="qw-today">
        <div class="qw-count">
          <b>{{ store.todaySmoked }}</b>
          <small>支 · 今天</small>
        </div>
        <div class="qw-actions">
          <button class="qw-step" type="button" aria-label="今天少记一支" :disabled="store.todaySmoked <= 0 || saving" @click="quick(-1)">−1</button>
          <button class="qw-step is-primary" type="button" aria-label="今天多记一支" :disabled="saving" @click="quick(1)">+1</button>
          <button class="qw-zero" type="button" :disabled="saving || store.todaySmoked === 0" @click="markTodayClean">一支没抽</button>
        </div>
      </div>

      <ol class="qw-week" aria-label="最近 7 天记录">
        <li v-for="day in week" :key="day.date" :class="{ 'is-today': day.date === todayKey, 'is-clean': day.logged && day.count === 0, 'is-smoked': day.logged && day.count > 0 }">
          <i :style="{ height: `${barHeight(day)}px` }" :title="`${day.date} ${day.logged ? `${day.count} 支` : '未记录'}`" />
          <span>{{ day.weekday }}</span>
        </li>
      </ol>

      <form class="qw-backfill" @submit.prevent="saveBackfill">
        <label>
          <span>补录</span>
          <input v-model="backfill.date" type="date" :min="range.min" :max="range.max" aria-label="补录日期" />
        </label>
        <label class="qw-backfill-count">
          <input v-model.number="backfill.count" type="number" min="0" max="200" step="1" aria-label="当天支数" />
          <span>支</span>
        </label>
        <button class="hw-btn" type="submit" :disabled="saving || !backfill.date">保存</button>
      </form>
      <p v-if="hint" class="qw-hint">{{ hint }}</p>
    </template>

    <template #footer>
      <span>{{ store.currentStreak }} 天连续 · 省下 ¥{{ money(store.savedAmount) }}</span>
      <router-link class="qw-more" to="/tools/quit">记录渴望 →</router-link>
    </template>
  </HomeWidgetFrame>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import HomeWidgetFrame from './HomeWidgetFrame.vue'
import { useQuitSmokingStore } from '../../stores/quitSmoking.js'
import { dateKeyOf } from './homeMetrics.js'
import { quitWeek, recordableRange } from './homeDashboard.js'

defineProps({ editing: Boolean })

const store = useQuitSmokingStore()
const saving = ref(false)
const hint = ref('')
const todayKey = dateKeyOf(new Date())
const range = recordableRange({ today: new Date(), span: 90 })
// 补录默认停在昨天：最常见的是「昨晚忘了记」，点开就能改，不必先选日期
const yesterday = (() => {
  const date = new Date()
  date.setDate(date.getDate() - 1)
  return dateKeyOf(date)
})()
const backfill = reactive({ date: yesterday, count: 0 })

const week = computed(() => quitWeek(store.days, { today: new Date(), span: 7 }))
const maxCount = computed(() => Math.max(1, ...week.value.map(day => day.count ?? 0)))
const metaLabel = computed(() => `${store.currentStreak} 天连续 · 今天 ${store.todaySmoked} 支`)

// 选到哪一天就带出那天已有的支数，避免「补录」把原记录覆盖成 0 还没提示；
// 戒烟数据异步加载，所以 store.loaded 变化也要重新带一次值
watch([() => backfill.date, () => store.loaded], ([date]) => {
  if (!date) return
  backfill.count = Number(store.days?.[date]?.smokedCount ?? 0)
  hint.value = store.days?.[date] ? `这天已有记录：${store.days[date].smokedCount} 支，保存会覆盖` : ''
}, { immediate: true })

function barHeight(day) {
  if (!day.logged) return 2
  return 6 + Math.round((day.count || 0) / maxCount.value * 26)
}

async function quick(delta) {
  saving.value = true
  try {
    await store.setTodaySmoked(store.todaySmoked + delta)
  } finally {
    saving.value = false
  }
}

async function markTodayClean() {
  saving.value = true
  try {
    await store.setTodaySmoked(0)
  } finally {
    saving.value = false
  }
}

async function saveBackfill() {
  if (!backfill.date || saving.value) return
  saving.value = true
  try {
    const saved = await store.setDaySmoked(backfill.date, backfill.count)
    if (saved) hint.value = `${backfill.date} 已记为 ${saved.smokedCount} 支`
  } finally {
    saving.value = false
  }
}

function money(value) {
  const amount = Number(value || 0)
  return amount >= 10000 ? `${(amount / 10000).toFixed(1)} 万` : amount.toFixed(0)
}
</script>

<style scoped>
.qw-more { color: var(--accent); font-size: 11px; font-weight: 650; }
.qw-empty { display: grid; gap: 8px; justify-items: center; padding: 18px 4px; color: var(--muted); font-size: 12px; text-align: center; }
.qw-empty p { margin: 0; }
.qw-empty .hw-btn { text-decoration: none; }
.qw-today { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.qw-count { display: flex; align-items: baseline; gap: 4px; }
.qw-count b { color: var(--ink); font-size: 30px; font-weight: 740; letter-spacing: -.04em; font-variant-numeric: tabular-nums; }
.qw-count small { color: var(--muted); font-size: 10.5px; }
.qw-actions { display: flex; align-items: center; gap: 4px; }
.qw-step { width: 30px; height: 28px; padding: 0; border: 1px solid var(--line2); border-radius: 8px; background: var(--card); color: var(--ink2); font-size: 12px; font-weight: 650; }
.qw-step.is-primary { border-color: var(--accent); color: var(--accent); }
.qw-step:disabled { opacity: .45; }
.qw-zero { height: 28px; padding: 0 8px; border: 1px solid var(--line2); border-radius: 8px; background: transparent; color: var(--muted); font-size: 10.5px; }
.qw-zero:disabled { opacity: .45; }
.qw-week { display: flex; align-items: flex-end; justify-content: space-between; gap: 3px; height: 42px; margin: 0 0 8px; padding: 0; list-style: none; }
.qw-week li { display: flex; min-width: 0; flex: 1; flex-direction: column; align-items: center; justify-content: flex-end; gap: 3px; }
.qw-week i { display: block; width: 100%; max-width: 14px; border-radius: 3px; background: var(--line2); transition: height .2s ease; }
.qw-week li.is-smoked i { background: color-mix(in srgb, var(--down) 55%, var(--line2)); }
.qw-week li.is-clean i { background: color-mix(in srgb, #2f9e44 55%, var(--line2)); }
.qw-week li.is-today i { outline: 1px solid var(--accent); outline-offset: 1px; }
.qw-week span { color: var(--muted); font-size: 9px; }
.qw-backfill { display: flex; align-items: center; gap: 5px; padding: 6px 7px; border: 1px dashed var(--line2); border-radius: 9px; }
.qw-backfill label { display: flex; min-width: 0; align-items: center; gap: 4px; color: var(--muted); font-size: 10px; }
.qw-backfill input[type="date"] { width: 112px; height: 26px; padding: 0 4px; border: 1px solid var(--line2); border-radius: 6px; background: var(--card); color: var(--ink); font-size: 11px; }
.qw-backfill-count input { width: 44px; height: 26px; padding: 0 4px; border: 1px solid var(--line2); border-radius: 6px; background: var(--card); color: var(--ink); font-size: 11px; text-align: right; }
.qw-backfill .hw-btn { height: 26px; margin-left: auto; padding: 0 8px; }
.qw-hint { margin: 4px 0 0; overflow: hidden; color: var(--muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
</style>
