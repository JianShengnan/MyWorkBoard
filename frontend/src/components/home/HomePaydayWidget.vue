<template>
  <HomeWidgetFrame title="发薪日" kicker="PAYDAY" :meta="metaLabel" :editing="editing">
    <template #actions>
      <router-link class="pw-more" to="/tasks/countdowns">倒数日 →</router-link>
    </template>

    <div v-if="summary" class="pw-body">
      <div class="pw-days">
        <b>{{ Math.max(0, summary.days) }}</b>
        <small>{{ summary.days === 0 ? '今天发薪' : '天后' }}</small>
      </div>
      <div class="pw-meta">
        <span>{{ summary.target.slice(5).replace('-', '/') }} {{ summary.weekday }}</span>
      </div>
    </div>
    <div v-else class="pw-empty">
      <p>任务域里的「距离发薪日」已被删除或归档</p>
      <router-link class="hw-btn" to="/tasks/countdowns">恢复内置倒数日</router-link>
    </div>

    <template #footer>
      <span v-if="monthSalary > 0">本月工资 ¥{{ money(monthSalary) }}</span>
      <router-link v-else class="pw-set" to="/worktime/settings">未设置月薪</router-link>
      <router-link class="pw-more" to="/worktime/settings">工资口径 →</router-link>
    </template>
  </HomeWidgetFrame>
</template>

<script setup>
import { computed } from 'vue'
import HomeWidgetFrame from './HomeWidgetFrame.vue'
import { useTasksStore } from '../../stores/tasks.js'
import { useWorktimeStore } from '../../stores/worktime.js'
import { findBuiltinCountdown, paydaySummary } from './homeDashboard.js'
import { monthKeyOf } from './homeMetrics.js'

defineProps({ editing: Boolean })

const tasksStore = useTasksStore()
const worktimeStore = useWorktimeStore()

const countdown = computed(() => findBuiltinCountdown(tasksStore.countdowns, 'payday'))
const summary = computed(() => paydaySummary(countdown.value, { today: new Date() }))
const monthSalary = computed(() => {
  const month = monthKeyOf(new Date())
  const entry = worktimeStore.settings?.salaries?.[month]
  if (!entry) return Number(worktimeStore.settings?.basis === 'pre' ? worktimeStore.settings?.salaryPre : worktimeStore.settings?.salaryPost) || 0
  return Number(worktimeStore.settings?.basis === 'pre' ? entry.pre : entry.post) || 0
})
// 卡片正文已经有「N 天后」，头部改为展示发薪规则，避免同一句话出现两遍
const metaLabel = computed(() => summary.value
  ? '每月 7 日 · 遇非工作日提前'
  : '未找到内置发薪日')

function money(value) {
  return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 })
}
</script>

<style scoped>
.pw-more { color: var(--accent); font-size: 11px; font-weight: 650; }
.pw-body { display: flex; height: 100%; min-height: 0; flex-direction: column; justify-content: center; gap: 10px; }
.pw-days { display: flex; align-items: baseline; gap: 5px; }
.pw-days b { color: var(--ink); font-size: 38px; font-weight: 750; letter-spacing: -.05em; font-variant-numeric: tabular-nums; }
.pw-days small { color: var(--muted); font-size: 11px; }
.pw-meta { display: flex; flex-direction: column; gap: 2px; }
.pw-meta span { color: var(--ink2); font-size: 12px; font-variant-numeric: tabular-nums; }
.pw-meta small { color: var(--muted); font-size: 10px; }
.pw-set { color: var(--accent); font-size: 11px; font-weight: 650; text-decoration: none; }
.pw-empty { display: grid; height: 100%; align-content: center; gap: 8px; justify-items: center; color: var(--muted); font-size: 11.5px; text-align: center; }
.pw-empty p { margin: 0; }
.pw-empty .hw-btn { text-decoration: none; }
</style>
