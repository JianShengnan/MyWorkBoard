<template>
  <HomeWidgetFrame title="倒数纪念日" kicker="COUNTDOWN" :meta="metaLabel" :editing="editing">
    <template #actions>
      <router-link class="cw-more" to="/tasks/countdowns">全部 →</router-link>
    </template>

    <ul v-if="rows.length" class="cw-list">
      <li v-for="row in rows" :key="row.id">
        <span class="cw-icon" :style="{ background: `color-mix(in srgb, ${row.color} 18%, transparent)`, color: row.color }">{{ row.icon }}</span>
        <span class="cw-main">
          <b :title="row.title">{{ row.title }}</b>
          <small>{{ row.target.slice(5).replace('-', '/') }} {{ row.weekday }}</small>
        </span>
        <span class="cw-days" :class="{ 'is-past': row.days < 0, 'is-today': row.days === 0 }">
          <b>{{ Math.abs(row.days) }}</b>
          <small>{{ row.days === 0 ? '今天' : row.days > 0 ? '天后' : '天前' }}</small>
        </span>
      </li>
    </ul>
    <div v-else class="cw-empty">
      <p>还没有自建的倒数日</p>
      <router-link class="hw-btn" to="/tasks/countdowns">新建纪念日</router-link>
    </div>

    <template #footer>
      <span>{{ totalCount }} 个倒数日</span>
      <router-link class="cw-more" to="/tasks/countdowns">管理 →</router-link>
    </template>
  </HomeWidgetFrame>
</template>

<script setup>
import { computed } from 'vue'
import HomeWidgetFrame from './HomeWidgetFrame.vue'
import { useTasksStore } from '../../stores/tasks.js'
import { nearestCountdowns } from './homeDashboard.js'

defineProps({ editing: Boolean })

const tasksStore = useTasksStore()
const totalCount = computed(() => tasksStore.countdowns.filter(item => !item.deleted && !item.builtinKey).length)
const rows = computed(() => nearestCountdowns(tasksStore.countdowns, { today: new Date(), limit: 3 }))
const metaLabel = computed(() => rows.value.length
  ? `最近 ${rows.value[0].title} · ${rows.value[0].days === 0 ? '就是今天' : `${Math.abs(rows.value[0].days)} 天${rows.value[0].days > 0 ? '后' : '前'}`}`
  : '暂无自建倒数日')
</script>

<style scoped>
.cw-more { color: var(--accent); font-size: 11px; font-weight: 650; }
.cw-list { display: flex; height: 100%; min-height: 0; flex-direction: column; gap: 2px; overflow-y: auto; margin: 0; padding: 0; list-style: none; scrollbar-width: thin; }
.cw-list li { display: flex; min-height: 0; flex: 1; align-items: center; gap: 7px; padding: 4px 2px; border-bottom: 1px dashed color-mix(in srgb, var(--line) 70%, transparent); }
.cw-list li:last-child { border-bottom: 0; }
.cw-icon { display: grid; width: 26px; height: 26px; flex: 0 0 26px; place-items: center; border-radius: 8px; font-size: 13px; }
.cw-main { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 1px; }
.cw-main b { overflow: hidden; color: var(--ink); font-size: 12.5px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
.cw-main small { color: var(--muted); font-size: 10px; font-variant-numeric: tabular-nums; }
.cw-days { display: flex; flex: 0 0 auto; align-items: baseline; gap: 2px; color: var(--ink2); font-variant-numeric: tabular-nums; }
.cw-days b { font-size: 17px; font-weight: 720; letter-spacing: -.03em; }
.cw-days small { color: var(--muted); font-size: 9.5px; }
.cw-days.is-today b, .cw-days.is-today small { color: var(--accent); }
.cw-days.is-past b { color: var(--muted); }
.cw-empty { display: grid; gap: 8px; justify-items: center; padding: 16px 4px; color: var(--muted); font-size: 12px; text-align: center; }
.cw-empty p { margin: 0; }
.cw-empty .hw-btn { text-decoration: none; }
@media (max-width: 767px) {
  .cw-list li { min-height: 38px; }
}
</style>
