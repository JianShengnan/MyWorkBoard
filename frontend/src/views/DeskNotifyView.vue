<template>
  <div class="desk-view">
    <header class="desk-head">
      <div class="desk-title">
        <h1>通知面板</h1>
        <p class="hint">
          订阅同一个 ntfy 主题，收到任务提醒时这里实时滚动，并弹出系统通知。
          这个页面开着就能收，可以固定成浏览器标签页或安装成独立窗口。
        </p>
      </div>
      <div class="desk-actions">
        <Button v-if="permission !== 'granted'" size="sm" @click="askPermission">
          允许桌面通知
        </Button>
        <Button size="sm" variant="ghost" :disabled="!items.length" @click="clear">清空</Button>
      </div>
    </header>

    <div v-if="!topic" class="desk-empty">
      <p>还没有可用的 ntfy 主题。</p>
      <p class="hint">先去「设置 → 推送通知 → ntfy」填好服务器与主题，回到这里就会自动连接。</p>
    </div>

    <template v-else>
      <div class="desk-bar">
        <span class="desk-dot" :class="connected ? 'is-on' : 'is-off'"></span>
        <span>{{ connected ? '已连接' : '连接中…' }}</span>
        <span class="desk-sep">·</span>
        <span>{{ maskedTopic }}</span>
        <span class="desk-sep">·</span>
        <span>{{ items.length }} 条</span>
        <span v-if="permission !== 'granted'" class="desk-warn">桌面通知未授权，只能在本页查看</span>
      </div>

      <ul v-if="items.length" class="desk-list">
        <li v-for="item in items" :key="item.id" class="desk-item" @click="open(item)">
          <div class="desk-item-head">
            <b>{{ item.title || '通知' }}</b>
            <time>{{ clock(item.time) }}</time>
          </div>
          <p v-if="item.message">{{ item.message }}</p>
          <div v-if="item.tags && item.tags.length" class="desk-tags">
            <span v-for="tag in item.tags" :key="tag">{{ tag }}</span>
          </div>
        </li>
      </ul>
      <p v-else class="hint desk-waiting">等待第一条通知……</p>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import Button from '../components/ui/Button.vue'
import { apiListPushChannels } from '../../packages/api-client/src/index.js'

const server = ref('')
const topic = ref('')
const items = ref([])
const connected = ref(false)
const permission = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
let source = null

const maskedTopic = computed(() => {
  const value = topic.value
  if (!value) return ''
  return value.length <= 8 ? value : `${value.slice(0, 8)}…`
})

async function loadChannel() {
  try {
    const channels = await apiListPushChannels()
    const ntfy = (channels || []).find(row => row.kind === 'NTFY' && row.ready)
    if (!ntfy) return
    server.value = (ntfy.config?.server || 'https://ntfy.sh').replace(/\/+$/, '')
    topic.value = ntfy.config?.topic || ''
  } catch {
    // 未登录或后端不可用时保持空态，由页面提示引导
  }
}

function connect() {
  if (!topic.value || typeof EventSource === 'undefined') return
  const url = `${server.value}/${encodeURIComponent(topic.value)}/sse`
  source = new EventSource(url)
  source.onopen = () => { connected.value = true }
  source.onerror = () => { connected.value = false }
  source.onmessage = event => {
    if (!event.data) return
    let payload
    try {
      payload = JSON.parse(event.data)
    } catch {
      return
    }
    // ntfy 的 SSE 会发 keepalive/open 事件，只有 message 才是一条真通知
    if (payload.event !== 'message') return
    push(payload)
  }
}

function push(payload) {
  items.value = [payload, ...items.value].slice(0, 100)
  notify(payload)
}

function notify(payload) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return
  if (typeof document !== 'undefined' && document.visibilityState === 'visible' && document.hasFocus()) {
    // 用户正盯着这个页面时不重复弹系统通知，避免双份
    return
  }
  const note = new Notification(payload.title || 'WorkBoard', {
    body: payload.message || '',
    tag: payload.id,
    icon: '/favicon.svg'
  })
  note.onclick = () => {
    window.focus()
    if (payload.click) window.open(payload.click, '_blank')
  }
}

async function askPermission() {
  if (typeof Notification === 'undefined') return
  permission.value = await Notification.requestPermission()
}

function clear() {
  items.value = []
}

function open(item) {
  if (item.click) window.open(item.click, '_blank')
}

function clock(value) {
  if (!value) return ''
  const date = new Date(value * 1000)
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

onMounted(async () => {
  await loadChannel()
  connect()
})

onBeforeUnmount(() => {
  if (source) source.close()
})
</script>

<style scoped>
.desk-view { display: flex; flex-direction: column; gap: 14px; padding: 18px 20px 32px; max-width: 860px; }
.desk-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.desk-title h1 { margin: 0 0 6px; font-size: 18px; font-weight: 600; }
.desk-actions { display: flex; gap: 8px; flex-shrink: 0; }
.desk-empty { padding: 22px; border: 1px dashed var(--line); border-radius: 12px; background: var(--paper); }
.desk-empty p { margin: 0 0 6px; }
.desk-bar { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--muted); }
.desk-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--muted); }
.desk-dot.is-on { background: #1D9E75; }
.desk-dot.is-off { background: #BA7517; }
.desk-sep { opacity: 0.5; }
.desk-warn { margin-left: 8px; color: #BA7517; }
.desk-list { display: flex; flex-direction: column; gap: 8px; margin: 0; padding: 0; list-style: none; }
.desk-item { padding: 12px 14px; border: 1px solid var(--line); border-radius: 10px; background: var(--card); cursor: pointer; }
.desk-item:hover { border-color: var(--line-strong, var(--line)); }
.desk-item-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.desk-item-head b { font-size: 13.5px; font-weight: 600; }
.desk-item-head time { font-size: 11.5px; color: var(--muted); flex-shrink: 0; }
.desk-item p { margin: 6px 0 0; font-size: 12.5px; line-height: 1.65; white-space: pre-wrap; color: var(--ink-2, var(--ink)); }
.desk-tags { display: flex; gap: 6px; margin-top: 8px; }
.desk-tags span { padding: 1px 7px; border-radius: 999px; background: var(--paper); font-size: 11px; color: var(--muted); }
.desk-waiting { padding: 18px 0; }
@media (max-width: 640px) {
  .desk-view { padding: 14px 14px 28px; }
  .desk-head { flex-direction: column; }
}
</style>
