<template>
  <div class="reports-page">
    <header class="hero no-print">
      <div class="hero-top">
        <div class="hero-label">{{ period === 'MONTH' ? 'Monthly Report' : 'Weekly Report' }}</div>
        <div class="hero-tabs">
          <button type="button" :class="{ active: period === 'WEEK' }" @click="switchPeriod('WEEK')">周报</button>
          <button type="button" :class="{ active: period === 'MONTH' }" @click="switchPeriod('MONTH')">月报</button>
        </div>
      </div>
      <h1>{{ headline }}</h1>
      <p class="hero-sub">
        {{ label }}<template v-if="report?.partial"> · 本期进行中，统计截至今天</template>
      </p>
      <div class="hero-actions">
        <button class="hero-btn" type="button" @click="shift(-1)">上一{{ unit }}</button>
        <button class="hero-btn" type="button" :disabled="isCurrent" @click="shift(1)">下一{{ unit }}</button>
        <button class="hero-btn" type="button" :disabled="loading" @click="reload">刷新</button>
        <button class="hero-btn" type="button" @click="exportPdf">导出 PDF</button>
      </div>
    </header>

    <p v-if="error" class="hint error no-print">{{ error }}</p>

    <template v-if="report">
      <section class="summary">
        <div class="section-head">
          <h2>本期总结</h2>
          <button v-if="summary.content" class="mini-btn no-print" type="button" :disabled="summaryBusy"
            @click="regenerate">{{ summaryBusy ? '重新生成中…' : '重新生成' }}</button>
        </div>
        <p v-if="summary.content" class="summary-text">{{ summary.content }}</p>
        <p v-else-if="summaryBusy" class="summary-text placeholder">正在根据本期数据生成总结…</p>
        <p v-else class="summary-text placeholder">
          还没有总结。<button class="link-btn no-print" type="button" @click="regenerate">立即生成</button>
        </p>
      </section>

      <section class="kpi-grid">
        <div v-for="item in kpis" :key="item.label" class="kpi">
          <span class="kpi-label">{{ item.label }}</span>
          <b class="kpi-value">{{ item.value }}</b>
          <span class="kpi-note">
            {{ item.note }}
            <i v-if="item.delta !== null" :class="['delta', item.delta >= 0 ? 'up' : 'down']">
              {{ item.delta >= 0 ? '+' : '' }}{{ item.delta }}%
            </i>
          </span>
        </div>
      </section>

      <section class="block">
        <div class="section-head">
          <h2>任务</h2>
          <span class="section-note">按完成时间统计，含重复任务的每日实例</span>
        </div>
        <div class="chart-row">
          <div ref="taskChart" class="chart"></div>
          <ul class="bar-list">
            <li v-for="row in report.taskLists" :key="row.label">
              <span class="bar-name">{{ row.label }}</span>
              <span class="bar-track"><i :style="{ width: percent(row.value, maxTask) }"></i></span>
              <span class="bar-value">{{ row.value }}</span>
            </li>
            <li v-if="!report.taskLists.length" class="hint">本期没有完成的任务</li>
          </ul>
        </div>
        <ul v-if="report.taskItems.length" class="item-list">
          <li v-for="(item, index) in report.taskItems" :key="index" @click="go(item.link)">
            <span class="item-title">{{ item.title }}</span>
            <span class="item-meta">{{ item.meta }}</span>
          </li>
        </ul>
        <router-link class="more-link no-print" to="/tasks/all">去任务列表 →</router-link>
      </section>

      <section class="block">
        <div class="section-head">
          <h2>支出</h2>
          <span class="section-note">只统计支出与收入，账户间转账已排除</span>
        </div>
        <div class="chart-row">
          <div ref="expenseChart" class="chart"></div>
          <ul class="bar-list">
            <li v-for="row in report.expenseCategories" :key="row.label">
              <span class="bar-name">{{ row.label }}</span>
              <span class="bar-track"><i :style="{ width: percent(row.value, maxExpense) }"></i></span>
              <span class="bar-value">¥{{ money(row.value) }}</span>
            </li>
            <li v-if="!report.expenseCategories.length" class="hint">本期没有支出记录</li>
          </ul>
        </div>
        <ul v-if="report.expenseItems.length" class="item-list">
          <li v-for="(item, index) in report.expenseItems" :key="index" @click="go(item.link)">
            <span class="item-title">{{ item.title }}</span>
            <span class="item-note">{{ item.note }}</span>
            <span class="item-meta">{{ item.meta }}</span>
          </li>
        </ul>
        <router-link class="more-link no-print" to="/ledger/transactions">去账本流水 →</router-link>
      </section>

      <section class="block">
        <div class="section-head">
          <h2>工时</h2>
          <span class="section-note">净工时 = 起止时间 − 午休 − 其他休息</span>
        </div>
        <div ref="workChart" class="chart wide"></div>
        <ul v-if="report.workItems.length" class="item-list compact">
          <li v-for="(item, index) in report.workItems" :key="index" @click="go(item.link)">
            <span class="item-title">{{ item.title }}</span>
            <span class="item-note">{{ item.note }}</span>
            <span class="item-meta">{{ item.meta }}</span>
          </li>
        </ul>
        <router-link class="more-link no-print" to="/records">去工时记录 →</router-link>
      </section>

      <section class="block">
        <div class="section-head">
          <h2>习惯</h2>
          <span class="section-note">只统计打卡记录，未记录的不计入分母</span>
        </div>
        <div class="chart-row">
          <div ref="habitChart" class="chart"></div>
          <ul class="bar-list">
            <li v-for="row in report.habitStats" :key="row.label">
              <span class="bar-name">{{ row.label }}</span>
              <span class="bar-track"><i :style="{ width: percent(row.value, maxHabit) }"></i></span>
              <span class="bar-value">{{ row.value }} / {{ row.count }}</span>
            </li>
            <li v-if="!report.habitStats.length" class="hint">本期没有打卡记录</li>
          </ul>
        </div>
      </section>

      <section class="block">
        <div class="section-head">
          <h2>专注</h2>
          <span class="section-note">只统计已完成的专注，单位为分钟</span>
        </div>
        <div class="chart-row">
          <div ref="focusChart" class="chart"></div>
          <ul class="item-list inline">
            <li v-for="(item, index) in report.focusItems.slice(0, 6)" :key="index" @click="go(item.link)">
              <span class="item-title">{{ item.title }}</span>
              <span class="item-meta">{{ item.meta }}</span>
            </li>
            <li v-if="!report.focusItems.length" class="hint">本期没有专注记录</li>
          </ul>
        </div>
      </section>

      <section class="block">
        <div class="section-head">
          <h2>倒数日</h2>
          <span class="section-note">本期内到期与未来 30 天内即将到来</span>
        </div>
        <ul v-if="report.countdownItems.length" class="item-list inline">
          <li v-for="(item, index) in report.countdownItems" :key="index" @click="go(item.link)">
            <span class="item-title">{{ item.title }}</span>
            <span class="item-note">{{ item.note }}</span>
            <span class="item-meta">{{ item.meta }}</span>
          </li>
        </ul>
        <p v-else class="hint">没有临近的倒数日</p>
        <router-link class="more-link no-print" to="/tasks/countdowns">去倒数日 →</router-link>
      </section>

      <section v-if="external && external.length" class="block external">
        <div class="section-head">
          <h2>生活与其他</h2>
          <span class="section-note">由 WorkBuddy 从邮箱、音乐、GitHub 等处补充</span>
        </div>
        <div class="external-grid">
          <article v-for="group in external" :key="group.key" class="external-card">
            <h3>{{ group.title }}</h3>
            <p v-if="group.text" class="external-text">{{ group.text }}</p>
            <ul v-if="group.items && group.items.length">
              <li v-for="(row, index) in group.items" :key="index">
                <span>{{ row.title }}</span>
                <b>{{ row.meta }}</b>
              </li>
            </ul>
          </article>
        </div>
      </section>

      <p class="footer-note">数据来自 WorkBoard 自建站 · 生成于 {{ generatedAt }}</p>
    </template>

    <p v-else-if="!error" class="hint">正在汇总数据…</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { apiReport, apiReportSummary } from '../../packages/api-client/src/index.js'

const router = useRouter()
const route = useRoute()
const period = ref('WEEK')
const anchor = ref(new Date())
const report = ref(null)
const external = ref(null)
const summary = reactive({ content: '', cached: false })
const summaryBusy = ref(false)
const loading = ref(false)
const error = ref('')
const charts = []
const el = { task: ref(null), expense: ref(null), work: ref(null), habit: ref(null), focus: ref(null) }
const taskChart = el.task, expenseChart = el.expense, workChart = el.work, habitChart = el.habit, focusChart = el.focus

const PALETTE = ['#1D9E75', '#378ADD', '#BA7517', '#D4537E', '#7F77DD', '#E24B4A', '#639922', '#888780']

const unit = computed(() => (period.value === 'MONTH' ? '月' : '周'))
const label = computed(() => report.value?.label || '')
const isCurrent = computed(() => !report.value?.partial && !inFuture())
const generatedAt = computed(() => {
  if (!report.value) return ''
  return new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-')
})

function inFuture() {
  if (!report.value) return false
  return new Date(report.value.start + 'T00:00:00') > new Date()
}

const headline = computed(() => {
  const kpi = report.value?.kpi
  if (!kpi) return '周期记录'
  if (kpi.completed === 0) return `这${unit.value}还没有完成的任务`
  return `完成 ${kpi.completed} 项，净工时 ${kpi.netHours} 小时`
})

const kpis = computed(() => {
  const kpi = report.value?.kpi
  if (!kpi) return []
  const prev = report.value?.previous
  const d = (current, before) => {
    if (!before || before === 0) return null
    return Math.round(((current - before) / before) * 100)
  }
  return [
    { label: '完成任务', value: `${kpi.completed}`, note: `新建 ${kpi.created}`, delta: d(kpi.completed, prev?.completed) },
    { label: '支出', value: `¥${money(kpi.expense)}`, note: `${kpi.expenseCount} 笔`, delta: d(kpi.expense, prev?.expense) },
    { label: '收入', value: `¥${money(kpi.income)}`, note: `结余 ¥${money(kpi.income - kpi.expense)}`, delta: null },
    { label: '净工时', value: `${kpi.netHours}h`, note: `出勤 ${kpi.workDays} 天`, delta: d(kpi.netHours, prev?.netHours) },
    { label: '加班', value: `${Math.round(kpi.overtimeMinutes / 6) / 10}h`, note: '超出 7.5h 基准', delta: null },
    { label: '习惯', value: `${kpi.habitRate}%`, note: `完成 ${kpi.habitDone} · 缺卡 ${kpi.habitMiss}`, delta: d(kpi.habitRate, prev?.habitRate) },
    { label: '专注', value: `${kpi.focusHours}h`, note: `${kpi.focusSessions} 次`, delta: d(kpi.focusHours, prev?.focusHours) }
  ]
})

const maxTask = computed(() => Math.max(1, ...(report.value?.taskLists || []).map(row => row.value)))
const maxExpense = computed(() => Math.max(1, ...(report.value?.expenseCategories || []).map(row => row.value)))
const maxHabit = computed(() => Math.max(1, ...(report.value?.habitStats || []).map(row => row.value)))

function money(value) {
  return Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
}

function percent(value, max) {
  return `${Math.max(3, Math.round((value / max) * 100))}%`
}

function go(link) {
  if (link) router.push(link)
}

function exportPdf() {
  window.print()
}

// 注意：不能用 toISOString().slice(0,10)。本地 00:00 在东八区是 UTC 前一天 16:00，
// 直接取前 10 位会把周一的柱子画到周日，整条 x 轴左移一天。
function localDate(date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

function daysBetween(start, end) {
  const base = new Date(start + 'T00:00:00')
  const last = new Date(end + 'T00:00:00')
  const total = Math.round((last - base) / 86400000) + 1
  return Array.from({ length: Math.max(1, total) }, (_, index) => {
    const day = new Date(base)
    day.setDate(base.getDate() + index)
    return localDate(day)
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
    grid: { left: 6, right: 6, top: 22, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis' },
    textStyle: { fontSize: 11 },
    xAxis: {
      type: 'category', axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: '#888780', fontSize: 10, interval: 'auto' }
    },
    yAxis: {
      type: 'value', splitLine: { lineStyle: { color: 'rgba(136,135,128,0.18)' } },
      axisLabel: { color: '#888780', fontSize: 10 }
    }
  }
}

function mount(target, option) {
  if (!target) return
  const chart = echarts.init(target)
  chart.setOption(option)
  charts.push(chart)
}

function renderCharts() {
  charts.splice(0).forEach(chart => chart.dispose())
  const days = daysBetween(report.value.start, report.value.end)
  const labels = days.map(dayLabel)

  mount(taskChart.value, {
    ...baseOption(),
    xAxis: { ...baseOption().xAxis, data: labels },
    series: [{ type: 'bar', name: '完成任务', data: seriesFor(report.value.taskDaily, days),
      itemStyle: { color: '#1D9E75', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 22 }]
  })

  mount(expenseChart.value, {
    ...baseOption(),
    xAxis: { ...baseOption().xAxis, data: labels },
    series: [{ type: 'bar', name: '支出', data: seriesFor(report.value.expenseDaily, days),
      itemStyle: { color: '#378ADD', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 22 }]
  })

  mount(workChart.value, {
    ...baseOption(),
    xAxis: { ...baseOption().xAxis, data: labels },
    series: [{
      type: 'bar', name: '净工时（小时）', data: seriesFor(report.value.workDaily, days),
      itemStyle: { color: '#BA7517', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 28,
      markLine: {
        silent: true, symbol: 'none', lineStyle: { color: '#E24B4A', type: 'dashed' },
        data: [{ yAxis: 7.5, label: { formatter: '基准 7.5h', fontSize: 10 } }]
      }
    }]
  })

  const done = report.value.kpi.habitDone
  const miss = report.value.kpi.habitMiss
  mount(habitChart.value, {
    tooltip: { trigger: 'item', formatter: '{b}：{c}' },
    legend: { bottom: 0, textStyle: { fontSize: 10, color: '#888780' } },
    series: [{
      type: 'pie', radius: ['52%', '74%'], center: ['50%', '44%'], label: { show: false },
      itemStyle: { borderWidth: 0 },
      data: done + miss === 0
        ? [{ value: 1, name: '无记录', itemStyle: { color: '#D3D1C7' } }]
        : [
            { value: done, name: '完成', itemStyle: { color: PALETTE[0] } },
            { value: miss, name: '缺卡', itemStyle: { color: PALETTE[5] } }
          ]
    }]
  })

  mount(focusChart.value, {
    ...baseOption(),
    xAxis: { ...baseOption().xAxis, data: labels },
    series: [{
      type: 'bar', name: '专注（分钟）', data: seriesFor(report.value.focusDaily, days),
      itemStyle: { color: '#7F77DD', borderRadius: [4, 4, 0, 0] }, barMaxWidth: 26
    }]
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const iso = localDate(anchor.value)
    const payload = await apiReport(period.value, iso)
    report.value = payload?.report || payload
    summary.content = payload?.summary?.content || ''
    summary.cached = Boolean(payload?.summary?.cached)
    external.value = report.value?.external || null
    await nextTick()
    renderCharts()
    if (!summary.content) fetchSummary(false)
  } catch (exception) {
    error.value = exception?.response?.data?.detail || '报表加载失败'
  } finally {
    loading.value = false
  }
}

async function fetchSummary(force) {
  if (summaryBusy.value) return
  summaryBusy.value = true
  try {
    const iso = report.value?.start || localDate(anchor.value)
    const result = await apiReportSummary(period.value, iso, force)
    summary.content = result?.content || ''
    summary.cached = Boolean(result?.cached)
  } catch {
    summary.content = summary.content || ''
  } finally {
    summaryBusy.value = false
  }
}

function regenerate() {
  fetchSummary(true)
}

function switchPeriod(next) {
  if (period.value === next) return
  period.value = next
  anchor.value = new Date()
  report.value = null
  summary.content = ''
  load()
}

function shift(direction) {
  const next = new Date(anchor.value)
  if (period.value === 'MONTH') next.setMonth(next.getMonth() + direction)
  else next.setDate(next.getDate() + direction * 7)
  anchor.value = next
  report.value = null
  summary.content = ''
  load()
}

function reload() {
  load()
}

onMounted(() => {
  if (route.query.period === 'MONTH') period.value = 'MONTH'
  load()
})
onBeforeUnmount(() => charts.splice(0).forEach(chart => chart.dispose()))
</script>

<style scoped>
.reports-page { display: flex; flex-direction: column; gap: 16px; width: 100%; max-width: 1000px; margin: 0 auto; padding: 20px 20px 48px; }
.hero { padding: 22px 24px; border-radius: 14px; background: #16324f; color: #f4f1ec; }
.hero-top { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.hero-label { font-size: 11.5px; letter-spacing: 2px; text-transform: uppercase; opacity: 0.72; }
.hero-tabs { display: flex; gap: 4px; padding: 3px; border-radius: 999px; background: rgba(255, 255, 255, 0.12); }
.hero-tabs button { padding: 3px 14px; border: 0; border-radius: 999px; background: transparent; color: inherit; font-size: 12px; cursor: pointer; }
.hero-tabs button.active { background: #f4f1ec; color: #16324f; font-weight: 600; }
.hero h1 { margin: 10px 0 6px; font-size: 22px; font-weight: 600; line-height: 1.42; }
.hero-sub { margin: 0; font-size: 12.5px; opacity: 0.78; }
.hero-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 14px; }
.hero-btn { padding: 5px 12px; border: 1px solid rgba(255, 255, 255, 0.3); border-radius: 999px; background: transparent; color: inherit; font-size: 12px; cursor: pointer; }
.hero-btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.14); }
.hero-btn:disabled { opacity: 0.4; cursor: default; }

.summary { padding: 16px 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--card); }
.summary-text { margin: 0; font-size: 13.5px; line-height: 1.85; }
.summary-text.placeholder { color: var(--muted); }
.mini-btn { padding: 2px 10px; border: 1px solid var(--line); border-radius: 999px; background: transparent; color: var(--muted); font-size: 11.5px; cursor: pointer; }
.link-btn { border: 0; background: none; color: var(--accent); font-size: inherit; cursor: pointer; text-decoration: underline; }

.kpi-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(146px, 1fr)); gap: 10px; }
.kpi { display: flex; flex-direction: column; gap: 3px; padding: 13px 15px; border: 1px solid var(--line); border-radius: 12px; background: var(--card); }
.kpi-label { font-size: 11.5px; color: var(--muted); }
.kpi-value { font-size: 20px; font-weight: 600; line-height: 1.2; }
.kpi-note { font-size: 11px; color: var(--muted); }
.delta { font-style: normal; margin-left: 4px; }
.delta.up { color: #1D9E75; }
.delta.down { color: #E24B4A; }

.block { padding: 16px 18px 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--card); }
.section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.section-head h2 { margin: 0; font-size: 15px; font-weight: 600; }
.section-note { font-size: 11.5px; color: var(--muted); }
.chart-row { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); gap: 18px; align-items: center; }
.chart { width: 100%; height: 200px; }
.chart.wide { height: 190px; }

.bar-list { display: flex; flex-direction: column; gap: 7px; margin: 0; padding: 0; list-style: none; }
.bar-list li { display: grid; grid-template-columns: 80px minmax(0, 1fr) auto; align-items: center; gap: 8px; font-size: 12px; }
.bar-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); }
.bar-track { height: 6px; border-radius: 999px; background: var(--line); overflow: hidden; }
.bar-track i { display: block; height: 100%; border-radius: 999px; background: var(--accent); }
.bar-value { font-variant-numeric: tabular-nums; }

.item-list.inline { margin-top: 0; border-top: 0; }
.item-list { display: flex; flex-direction: column; margin: 12px 0 0; padding: 0; list-style: none; border-top: 1px dashed var(--line); }
.item-list li { display: flex; align-items: baseline; gap: 10px; padding: 8px 2px; border-bottom: 1px dashed var(--line); font-size: 12.5px; cursor: pointer; }
.item-list li:hover { background: rgba(136, 135, 128, 0.06); }
.item-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-note { max-width: 42%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--muted); font-size: 11.5px; }
.item-meta { flex-shrink: 0; color: var(--muted); font-size: 11.5px; font-variant-numeric: tabular-nums; }
.more-link { display: inline-block; margin-top: 10px; font-size: 12px; color: var(--accent); text-decoration: none; }
.more-link:hover { text-decoration: underline; }

.external-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
.external-card { padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); }
.external-card h3 { margin: 0 0 6px; font-size: 12.5px; font-weight: 600; }
.external-text { margin: 0; font-size: 12px; line-height: 1.7; color: var(--muted); }
.external-card ul { display: flex; flex-direction: column; gap: 4px; margin: 8px 0 0; padding: 0; list-style: none; }
.external-card li { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; }
.external-card li b { font-weight: 600; }

.footer-note { margin: 4px 0 0; text-align: center; font-size: 11.5px; color: var(--muted); }
.hint.error { color: #A32D2D; }

@media (max-width: 760px) {
  .reports-page { padding: 14px 14px 40px; }
  .chart-row { grid-template-columns: minmax(0, 1fr); }
  .chart { height: 176px; }
  .item-note { display: none; }
}

@media print {
  .no-print { display: none !important; }
  .reports-page { max-width: none; padding: 0; gap: 12px; }
  .hero { background: #16324f !important; color: #f4f1ec !important; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  .block, .summary, .kpi { break-inside: avoid; }
  .chart { height: 170px; }
}
</style>
