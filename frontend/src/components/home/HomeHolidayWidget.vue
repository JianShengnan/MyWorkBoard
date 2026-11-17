<template>
  <HomeWidgetFrame title="下一个节假日" kicker="HOLIDAY" :meta="metaLabel" :editing="editing">
    <template #actions>
      <router-link class="hw-more" to="/calendar">日历 →</router-link>
    </template>

    <div v-if="main" class="hw-body">
      <div class="hw-main">
        <b>{{ holidayName }}</b>
        <span class="hw-days">{{ main.days === 0 ? '今天' : `${main.days} 天后` }}</span>
      </div>
      <p class="hw-date">{{ main.target.slice(5).replace('-', '/') }} {{ main.weekday }}<em v-if="main.days === 0">· 放假中</em></p>
      <p v-if="!upcoming.length" class="hw-next-empty">后续节假日待公布</p>
      <ul v-if="upcoming.length" class="hw-next">
        <li v-for="item in upcoming" :key="`${item.date}-${item.name}`">
          <span>{{ item.name }}</span>
          <small>{{ item.date.slice(5).replace('-', '/') }} · {{ item.days }} 天后</small>
        </li>
      </ul>
    </div>
    <div v-else class="hw-empty-state">
      <p>暂时没有可用的节假日数据</p>
      <router-link class="hw-btn" to="/tasks/countdowns">查看倒数日</router-link>
    </div>

    <template #footer>
      <span>按国务院放假安排</span>
      <router-link class="hw-more" to="/calendar">完整日历 →</router-link>
    </template>
  </HomeWidgetFrame>
</template>

<script setup>
import { computed } from 'vue'
import HomeWidgetFrame from './HomeWidgetFrame.vue'
import { useAppStore } from '../../stores/app.js'
import { useTasksStore } from '../../stores/tasks.js'
import { findBuiltinCountdown, holidaySummary } from './homeDashboard.js'

defineProps({ editing: Boolean })

const store = useAppStore()
const tasksStore = useTasksStore()

const countdown = computed(() => findBuiltinCountdown(tasksStore.countdowns, 'holiday'))
const summary = computed(() => holidaySummary(countdown.value, store.holidays || {}, { today: new Date(), upcoming: 2 }))
const main = computed(() => summary.value.main)
const upcoming = computed(() => summary.value.upcoming)
// 内置标题形如「距离元旦」，卡片标题已经写明「下一个节假日」，这里只留节日名
const holidayName = computed(() => (main.value?.title || '').replace(/^距离/, '') || '节假日')
// 正文已经有「N 天后」，头部展示放假表年份，避免重复同一句天数
const metaLabel = computed(() => (main.value?.target ? `${main.value.target.slice(0, 4)} 年` : '暂无数据'))
const yearLabel = computed(() => (main.value?.target || '').slice(0, 4))
</script>

<style scoped>
.hw-more { color: var(--accent); font-size: 11px; font-weight: 650; }
.hw-body { display: flex; height: 100%; min-height: 0; flex-direction: column; gap: 4px; }
.hw-main { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.hw-main b { overflow: hidden; color: var(--ink); font-size: 20px; font-weight: 730; letter-spacing: -.03em; text-overflow: ellipsis; white-space: nowrap; }
.hw-days { flex: 0 0 auto; color: var(--accent); font-size: 11px; font-weight: 650; }
.hw-date { display: flex; align-items: center; gap: 5px; margin: 0; color: var(--muted); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.hw-date em { color: var(--accent); font-style: normal; }
.hw-next-empty { margin: 6px 0 0; padding-top: 6px; border-top: 1px dashed var(--line); color: var(--muted); font-size: 10.5px; }
.hw-next { display: flex; min-height: 0; flex-direction: column; gap: 3px; margin: 6px 0 0; padding: 6px 0 0; border-top: 1px dashed var(--line); list-style: none; overflow-y: auto; }
.hw-next li { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.hw-next span { overflow: hidden; color: var(--ink2); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.hw-next small { flex: 0 0 auto; color: var(--muted); font-size: 10px; font-variant-numeric: tabular-nums; }
.hw-empty-state { display: grid; height: 100%; align-content: center; gap: 8px; justify-items: center; color: var(--muted); font-size: 11.5px; text-align: center; }
.hw-empty-state p { margin: 0; }
.hw-empty-state .hw-btn { text-decoration: none; }
</style>
