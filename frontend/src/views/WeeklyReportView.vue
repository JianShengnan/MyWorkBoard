<template>
  <div class="report-view">
    <header class="report-hero">
      <div class="hero-label">Weekly Report · 周报</div>
      <h1>{{ headline }}</h1>
      <p class="hero-sub">{{ range }}<template v-if="data?.partial">（本周进行中，统计截至今天）</template></p>
      <div class="hero-actions">
        <button class="hero-btn" type="button" @click="shift(-7)">上一周</button>
        <button class="hero-btn" type="button" :disabled="isCurrentWeek" @click="shift(7)">下一周</button>
        <button class="hero-btn" type="button" @click="reload">刷新</button>
      </div>
    </header>

    <p v-if="error" class="hint error">{{ error }}</p>

    <template v-if="data">
      <section class="kpi-grid">
        <div v-for="item in kpis" :key="item.label" class="kpi">
          <span class="kpi-label">{{ item.label }}</span>
          <b class="kpi-value">{{ item.value }}</b>
          <span class="kpi-note">{{ item.note }}</span>
        </div>
      </section>

      <section class="report-block">
        <div class="block-head">
          <h2>任务</h2>
          <span class="block-note">按完成时间统计，含重复任务的每日实例</span>
        </div>
        <div class="block-body two-col">
          <div ref="taskChart" class="chart"></div>
          <ul class="bar-list">
            <li v-for="row in data.taskLists" :key="row.label">
              <span class="bar-name">{{ row.label }}</span>
              <span class="bar-track"><i :style="{ width: percent(row.value, maxTask) }"></i></span>
              <span class="bar-value">{{ row.value }}</span>
            </li>
            <li v-if="!data.taskLists.length" class="hint">本周没有完成的任务</li>
          </ul>
        </div>
      </section>

      <section class="report-block">
        <div class="block-head">
          <h2>支出</h2>
          <span class="block-note">只统计支出与收入，账户间转账已排除</span>
        </div>
        <div class="block-body two-col">
          <div ref="expenseChart" class="chart"></div>
          <ul class="bar-list">
            <li v-for="row in data.expenseCategories" :key="row.label">
              <span class="bar-name">{{ row.label }}</span>
              <span class="bar-track"><i :style="{ width: percent(row.value, maxExpense) }"></i></span>
              <span class="bar-value">¥{{ money(row.value) }}</span>
            </li>
            <li v-if="!data.expenseCategories.length" class="hint">本周没有支出记录</li>
          </ul>
        </div>
      </section>

      <section class="report-block">
        <div class="block-head">
          <h2>工时</h2>
          <span class="block-note">净工时 = 起止时间 − 午休 − 其他休息</span>
        </div>
        <div ref="workChart" class="chart wide"></div>
      </section>

      <section class="report-block">
        <div class="block-head">
          <h2>习惯</h2>
          <span class="block-note">只统计打卡记录，未记录的不计入分母</span>
        </div>
        <div class="block-body two-col">
          <div ref="habitChart" class="chart"></div>
          <ul class="bar-list">
            <li v-for="row in data.habitStats" :key="row.label">
              <span class="bar-name">{{ row.label }}</span>
              <span class="bar-track"><i :style="{ width: percent(row.value, maxHabit) }"></i></span>
              <span class="bar-value">{{ row.value }} / {{ row.count }}</span>
            </li>
            <li v-if="!data.habitStats.length" class="hint">本周没有打卡记录</li>
          </ul>
        </div>
      </section>
    </template>

    <p v-else-if="!error" class="hint">正在汇总本周数据…</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { apiWeeklyReport } from '../../packages/api-client/src/index.js'

const data = ref(null)
const error = ref('')
const anchor = ref(new Date())
const taskChart = ref(null)
const expenseChart = ref(null)
const workChart = ref(null)
const habitChart = ref(null)
const charts = []

const PALETTE = ['#1D9E75', '#378ADD', '#BA7517', '#D4537E', '#7F77DD', '#E24B4A', '#639922', '#888780']

const range = computed(() => {
  if (!data.value) return ''
  return `${data.value.weekStart} ～ ${data.value.weekEnd}`
})

const isCurrentWeek = computed(() => {
  if (!data.value) return true
  const today = new Date()
  const start = new Date(data.value.weekStart + 'T00:00:00')
  const end = new Date(data.value.weekEnd + 'T23:59:59')
  return today >= start && today <= end
})

const headline = computed(() => {
  const kpi = data.value?.kpi
  if (!kpi) return '本周记录'
  if (kpi.completed === 0) return '这周还没有完成的任务'
  return `完成 ${kpi.completed} 项，净工时 ${kpi.netHours} 小时`
})

const kpis = computed(() => {
  const kpi = data.value?.kpi
  if (!kpi) return []
  return [
    { label: '完成任务', value: `${kpi.completed}`, note: `新建 ${kpi.created}` },
    { label: '支出', value: `¥${money(kpi.expense)}`, note: `${kpi.expenseCount} 笔` },
    { label: '收入', value: `¥${money(kpi.income)}`, note: `结余 ¥${money(kpi.income - kpi.expense)}` },
    { label: '净工时', value: `${kpi.netHours}h`, note: `出勤 ${kpi.workDays} 天` },
    { label: '加班', value: `${Math.round(kpi.overtimeMinutes / 6) / 10}h`, note: '超出 7.5h 基准' },
    { label: '习惯', value: `${kpi.habitRate}%`, note: `完成 ${kpi.habitDone} · 缺卡 ${kpi.habitMiss}` }
  ]
})

const maxTask = computed(() => Math.max(1, ...(data.value?.taskLists || []).map(row => row.value)))
const maxExpense = computed(() => Math.max(1, ...(data.value?.expenseCategories || []).map(row => row.value)))
const maxHabit = computed(() => Math.max(1, ...(data.value?.habitStats || []).map(row => row.value)))

function money(value) {
  return Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
}

function percent(value, max) {
  return `${Math.max(3, Math.round((value / max) * 100))}%`
}

function weekDays(start) {
  const base = new Date(start + 'T00:00:00')
  return Array.from({ length: 7 }, (_, index) => {
    const day = new Date(base)
    day.setDate(base.getDate() + index)
    return day.toISOString().slice(0, 10)
  })
}

function dayLabel(date) {
  const parts = date.split('-')
  return `${Number(parts[1])}/${Number(parts[2])}`
}

function seriesFor(points, days) {
  const map = new Map((points || []).map(point => [point.date, point.value]))
  return days.map(day => map.get(day) ?? 0)
}

function baseOption() {
  return {
    grid: { left: 8, right: 8, top: 24, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    textStyle: { fontSize: 11 },
    xAxis: {
      type: 'category',
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#888780', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(136,135,128,0.18)' } },
      axisLabel: { color: '#888780', fontSize: 10 }
    }
  }
}

function mount(el, option) {
  if (!el) return
  const chart = echarts.init(el)
  chart.setOption(option)
  charts.push(chart)
}

function renderCharts() {
  charts.splice(0).forEach(chart => chart.dispose())
  const days = weekDays(data.value.weekStart)
  const labels = days.map(dayLabel)

  mount(taskChart.value, {
    ...baseOption(),
    xAxis: { ...baseOption().xAxis, data: labels },
    series: [{
      type: 'bar',
      data: seriesFor(data.value.taskDaily, days),
      itemStyle: { color: '#1D9E75', borderRadius: [4, 4, 0, 0] },
      barMaxWidth: 26,
      name: '完成任务'
    }]
  })

  mount(expenseChart.value, {
    ...baseOption(),
    tooltip: { trigger: 'item', formatter: '{b}：¥{c}（{d}%）' },
    xAxis: { ...baseOption().xAxis, data: labels },
    series: [{
      type: 'bar',
      data: seriesFor(data.value.expenseDaily, days),
      itemStyle: { color: '#378ADD', borderRadius: [4, 4, 0, 0] },
      barMaxWidth: 26,
      name: '支出'
    }]
  })

  mount(workChart.value, {
    ...baseOption(),
    xAxis: { ...baseOption().xAxis, data: labels },
    series: [{
      type: 'bar',
      data: seriesFor(data.value.workDaily, days),
      itemStyle: { color: '#BA7517', borderRadius: [4, 4, 0, 0] },
      barMaxWidth: 34,
      name: '净工时（小时）',
      markLine: {
        silent: true,
        symbol: 'none',
        lineStyle: { color: '#E24B4A', type: 'dashed' },
        data: [{ yAxis: 7.5, label: { formatter: '基准 7.5h', fontSize: 10 } }]
      }
    }]
  })

  const habitSlice = data.value.kpi.habitDone + data.value.kpi.habitMiss
  mount(habitChart.value, {
    tooltip: { trigger: 'item', formatter: '{b}：{c}' },
    legend: { bottom: 0, textStyle: { fontSize: 10, color: '#888780' } },
    series: [{
      type: 'pie',
      radius: ['52%', '74%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderWidth: 0 },
      label: { show: false },
      data: habitSlice === 0
        ? [{ value: 1, name: '无记录', itemStyle: { color: '#D3D1C7' } }]
        : [
            { value: data.value.kpi.habitDone, name: '完成', itemStyle: { color: PALETTE[0] } },
            { value: data.value.kpi.habitMiss, name: '缺卡', itemStyle: { color: PALETTE[5] } }
          ]
    }]
  })
}

async function load() {
  error.value = ''
  try {
    const iso = anchor.value.toISOString().slice(0, 10)
    data.value = await apiWeeklyReport(iso)
    await nextTick()
    renderCharts()
  } catch (exception) {
    error.value = exception?.response?.data?.detail || '周报数据加载失败'
  }
}

function reload() {
  load()
}

function shift(days) {
  const next = new Date(anchor.value)
  next.setDate(anchor.value.getDate() + days)
  anchor.value = next
  load()
}

onMounted(load)
onBeforeUnmount(() => charts.splice(0).forEach(chart => chart.dispose()))
</script>

<style scoped>
.report-view { display: flex; flex-direction: column; gap: 14px; padding: 18px 20px 40px; max-width: 980px; }
.report-hero { padding: 22px 24px; border-radius: 14px; background: #16324f; color: #f4f1ec; }
.hero-label { font-size: 11.5px; letter-spacing: 2px; text-transform: uppercase; opacity: 0.7; }
.report-hero h1 { margin: 8px 0 6px; font-size: 22px; font-weight: 600; line-height: 1.4; }
.hero-sub { margin: 0; font-size: 12.5px; opacity: 0.78; }
.hero-actions { display: flex; gap: 8px; margin-top: 14px; }
.hero-btn { padding: 5px 12px; border: 1px solid rgba(255, 255, 255, 0.28); border-radius: 999px; background: transparent; color: inherit; font-size: 12px; cursor: pointer; }
.hero-btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.12); }
.hero-btn:disabled { opacity: 0.4; cursor: default; }

.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
.kpi { display: flex; flex-direction: column; gap: 3px; padding: 13px 15px; border: 1px solid var(--line); border-radius: 12px; background: var(--card); }
.kpi-label { font-size: 11.5px; color: var(--muted); }
.kpi-value { font-size: 20px; font-weight: 600; line-height: 1.2; }
.kpi-note { font-size: 11px; color: var(--muted); }

.report-block { padding: 16px 18px 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--card); }
.block-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.block-head h2 { margin: 0; font-size: 15px; font-weight: 600; }
.block-note { font-size: 11.5px; color: var(--muted); }
.block-body.two-col { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); gap: 18px; align-items: center; }
.chart { width: 100%; height: 210px; }
.chart.wide { height: 200px; }

.bar-list { display: flex; flex-direction: column; gap: 7px; margin: 0; padding: 0; list-style: none; }
.bar-list li { display: grid; grid-template-columns: 84px minmax(0, 1fr) auto; align-items: center; gap: 8px; font-size: 12px; }
.bar-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); }
.bar-track { height: 6px; border-radius: 999px; background: var(--line); overflow: hidden; }
.bar-track i { display: block; height: 100%; border-radius: 999px; background: var(--accent); }
.bar-value { font-variant-numeric: tabular-nums; }
.hint.error { color: #A32D2D; }

@media (max-width: 760px) {
  .report-view { padding: 14px 14px 32px; }
  .block-body.two-col { grid-template-columns: minmax(0, 1fr); }
  .chart { height: 180px; }
}
</style>
