<template>
  <HomeWidgetFrame title="工时与加班" kicker="WORKTIME" :editing="editing">
    <template #actions>
      <button class="hw-btn" type="button" @click="togglePunch"><Timer :size="13" />{{ punchLabel }}</button>
    </template>

    <div class="wt-stats">
      <div><small>工时</small><b>{{ hours(stats.totalMin) }}<i>h</i></b></div>
      <div><small>加班</small><b :class="{ 'is-plus': stats.overtimeMin > 0 }">{{ hours(stats.overtimeMin) }}<i>h</i></b></div>
      <div><small>日均</small><b>{{ averageHours }}<i>h</i></b></div>
      <div><small>{{ stats.label }}工资</small><b>{{ stats.earned > 0 ? `¥${money(stats.earned)}` : '—' }}</b></div>
    </div>

    <div class="wt-chart">
      <div class="wt-chart-head">
        <!-- 范围切换跟图表放一行：卡片收窄到 4 栏后，标题行放不下「切换 + 按钮」两组控件 -->
        <div class="hw-seg">
          <button type="button" :class="{ active: range === 'week' }" @click="range = 'week'">本周</button>
          <button type="button" :class="{ active: range === 'month' }" @click="range = 'month'">本月</button>
          <button type="button" :class="{ active: range === 'year' }" @click="range = 'year'">本年</button>
        </div>
        <span class="wt-chart-note">{{ stats.granularity === 'day' ? '按日' : '按月' }} · 单位 小时</span>
      </div>
      <div class="wt-chart-plot">
        <LedgerReportChart :option="chartOption" aria-label="工时趋势" height="100%" :show-table="false" />
        <span v-if="!stats.days" class="wt-empty-hint">这段时间还没有工时记录</span>
      </div>
    </div>

    <Transition name="wt-punch">
      <form v-if="punchOpen" class="wt-punch" @submit.prevent="savePunch">
        <label>日期<input v-model="punch.date" type="date" required @change="loadPunch" /></label>
        <label>上班<input v-model="punch.start" type="time" required /></label>
        <label>下班<input v-model="punch.end" type="time" required /></label>
        <label>休息<input v-model="punch.rest" type="number" min="0" max="600" step="5" /><small>分钟</small></label>
        <span v-if="punchError" class="wt-error">{{ punchError }}</span>
        <button class="hw-btn" type="button" @click="punchOpen = false">取消</button>
        <button class="hw-btn primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
      </form>
    </Transition>

    <template #footer>
      <span>{{ stats.days }} 天有记录 · 加班 {{ hours(stats.overtimeMin) }}h</span>
      <router-link class="wt-more" to="/stats">工时统计 →</router-link>
    </template>
  </HomeWidgetFrame>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { Timer } from 'lucide-vue-next'
import HomeWidgetFrame from './HomeWidgetFrame.vue'
import LedgerReportChart from '../ledger/LedgerReportChart.vue'
import { useWorktimeStore } from '../../stores/worktime.js'
import { useAppStore } from '../../stores/app.js'
import { message } from '../../services/message.js'
import { dateKeyOf, hoursLabel, worktimeStats } from './homeMetrics.js'
import { punchUpdate } from './homeWidgetEdits.js'

defineProps({ editing: Boolean })

const worktimeStore = useWorktimeStore()
const appStore = useAppStore()
const range = ref('month')
const punchOpen = ref(false)
const saving = ref(false)
const punchError = ref('')
const todayKey = dateKeyOf(new Date())
const hasToday = computed(() => Boolean(worktimeStore.records[todayKey]?.id))
const punch = reactive({ date: todayKey, start: '', end: '', rest: 0 })
const punchLabel = computed(() => {
  if (punchOpen.value) return '收起工时编辑'
  const target = punch.date || todayKey
  if (target === todayKey) return hasToday.value ? '修改本日工时' : '记录本日工时'
  return worktimeStore.records[target]?.id ? `修改 ${target.slice(5)} 工时` : `补记 ${target.slice(5)} 工时`
})

const stats = computed(() => worktimeStats({
  records: worktimeStore.records,
  settings: worktimeStore.settings,
  holidays: appStore.holidays,
  range: range.value,
  today: new Date()
}))

const chartOption = computed(() => {
  const rows = stats.value.series
  const monthly = stats.value.granularity === 'month'
  return {
    // 卡片高度有限：纵轴只留 3 段刻度，否则刻度标签会挤成一团（表现为「看不到纵轴」）
    grid: { left: 2, right: 10, top: 10, bottom: 0, containLabel: true },
    tooltip: {
      trigger: 'axis',
      valueFormatter: value => (value == null ? '无记录' : `${value} 小时`),
      textStyle: { fontSize: 11 }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: rows.map(row => row.label),
      axisLine: { lineStyle: { color: '$line' } },
      axisTick: { show: false },
      axisLabel: { color: '$muted', fontSize: 9, interval: rows.length > 16 ? Math.ceil(rows.length / 8) : 0 }
    },
    yAxis: {
      type: 'value',
      min: 0,
      splitNumber: 3,
      axisLabel: { color: '$muted', fontSize: 9, formatter: value => `${value}h` },
      splitLine: { lineStyle: { color: '$line', type: 'dashed' } }
    },
    series: [{
      name: monthly ? '月工时' : '日工时', type: 'line', smooth: true, connectNulls: true, showSymbol: false,
      // minutes 为 0 表示当天没记录，用 null 让折线断开，避免把休息日画成 0 小时
      data: rows.map(row => (row.minutes ? Math.round(row.minutes / 6) / 10 : null)),
      lineStyle: { color: '$accent', width: 2 },
      itemStyle: { color: '$accent' },
      areaStyle: { color: '$accent', opacity: .12 }
    }]
  }
})

const averageHours = computed(() => {
  const days = stats.value.days || 0
  if (!days) return '—'
  return (Math.round((stats.value.totalMin / days / 60) * 10) / 10).toFixed(1)
})

function hours(minutes) { return hoursLabel(minutes) }
function money(value) { return Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 0 }) }

function togglePunch() {
  if (punchOpen.value) { punchOpen.value = false; return }
  // 默认仍是今天；之前补记过别的日期就沿用上次选的日子，省一次点选
  punch.date = punch.date || todayKey
  loadPunch()
  punchError.value = ''
  punchOpen.value = true
}

/** 切日期时把那天已有的记录读进表单，没记录就按设置里的上下班时间给默认值 */
function loadPunch() {
  const record = worktimeStore.records[punch.date] || {}
  punch.start = record.start || worktimeStore.settings.workStart || '09:00'
  punch.end = record.end || worktimeStore.settings.workEnd || '18:00'
  punch.rest = Number(record.rest || 0)
  punchError.value = ''
}

async function savePunch() {
  const payload = punchUpdate(punch.date, punch)
  if (!payload) { punchError.value = '请填写有效的日期与上下班时间'; return }
  saving.value = true
  punchError.value = ''
  try {
    await worktimeStore.saveRecord(payload)
    message.success(payload.date === todayKey ? '本日工时已保存' : `${payload.date.slice(5)} 工时已保存`)
    punchOpen.value = false
  } catch (error) {
    punchError.value = error?.response?.data?.detail || error?.message || '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* 统计固定单行四联：收到 4 栏宽后靠缩小字号/内边距容纳，不再折成两行把卡片撑高 */
.wt-stats { display: grid; flex: none; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 7px; margin-bottom: 8px; }
.wt-stats > div { min-width: 0; padding: 7px 8px; border: 1px solid var(--line); border-radius: 11px; background: var(--paper); }
.wt-stats small { display: block; overflow: hidden; color: var(--muted); font-size: 9.5px; text-overflow: ellipsis; white-space: nowrap; }
.wt-stats b { display: block; margin-top: 2px; overflow: hidden; color: var(--ink); font-size: 16px; font-weight: 720; letter-spacing: -.03em; text-overflow: ellipsis; white-space: nowrap; font-variant-numeric: tabular-nums; }
.wt-stats b i { margin-left: 2px; color: var(--muted); font-size: 10px; font-style: normal; font-weight: 600; }
.wt-stats b.is-plus { color: var(--down); }
.wt-chart { display: flex; min-height: 0; flex: 1; flex-direction: column; gap: 4px; }
.wt-chart-head { display: flex; flex: none; align-items: center; justify-content: space-between; gap: 8px; color: var(--muted); font-size: 10.5px; }
/* 基准说明在窄卡里让位给切换器，超长省略而不是把 seg 挤变形 */
.wt-chart-note { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wt-chart-plot { position: relative; display: flex; min-height: 92px; flex: 1; flex-direction: column; }
.wt-empty-hint { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--muted); font-size: 11px; }
.wt-chart-plot :deep(.ledger-report-chart-wrap) { display: flex; min-height: 0; flex: 1; flex-direction: column; }
.wt-chart-plot :deep(.ledger-report-chart) { flex: 1; min-height: 0; }
.wt-empty { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; gap: 5px; min-height: 48px; color: var(--muted); font-size: 11.5px; text-align: center; }
.wt-empty b { color: var(--ink2); font-size: 12.5px; }
.wt-empty .hw-btn { margin-top: 4px; text-decoration: none; }
.wt-punch { display: flex; flex: none; flex-wrap: wrap; align-items: flex-end; gap: 8px; margin-top: 10px; padding: 10px 12px; border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--line)); border-radius: 12px; background: color-mix(in srgb, var(--accent-soft) 34%, var(--card)); }
.wt-punch label { display: flex; align-items: center; gap: 5px; color: var(--ink2); font-size: 11px; }
.wt-punch input { height: 30px; padding: 0 7px; border: 1px solid var(--line2); border-radius: 7px; background: var(--card); color: var(--ink); font-size: 12px; }
.wt-punch input[type="number"] { width: 62px; }
/* 日期输入给固定宽度，否则 iOS/Safari 会把整行撑开 */
.wt-punch input[type="date"] { width: 140px; }
.wt-punch label small { color: var(--muted); font-size: 10px; }
.wt-punch .hw-btn { margin-left: auto; }
.wt-punch .hw-btn + .hw-btn { margin-left: 0; }
.wt-error { flex-basis: 100%; color: var(--down); font-size: 11px; }
.wt-more { color: var(--accent); font-weight: 650; }
.wt-punch-enter-active, .wt-punch-leave-active { transition: opacity .16s ease, transform .16s ease; }
.wt-punch-enter-from, .wt-punch-leave-to { opacity: 0; transform: translateY(-4px); }
/* 手机单列时卡片只有 360 上下，四联会挤成一字一行，退回两列 */
@media (max-width: 720px) { .wt-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
