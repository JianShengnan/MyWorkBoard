<template>
  <div ref="root" v-show="preferences.mode !== 'hidden' && !modalObscured" class="floating-agent" :class="[preferences.side, { open, docked: preferences.mode === 'docked' }]" @keydown.esc.stop="dismiss">
    <span class="sr-only" role="status">{{ sending ? '随身 AI 正在生成回复' : '' }}</span>
    <Transition name="floating-agent-panel">
      <section v-if="open" id="floating-agent-panel" class="floating-agent-panel" role="dialog" aria-modal="false" aria-labelledby="floating-agent-title">
        <header>
          <span class="floating-agent-mark"><Sparkles /></span>
          <div><b id="floating-agent-title">随身 AI</b><small>当前页面 · {{ currentPageTitle }}</small></div>
          <button ref="optionsTrigger" class="floating-agent-header-options" type="button" aria-label="随身 AI 入口选项" :aria-expanded="menuOpen" aria-controls="floating-agent-options" @click="toggleMenu"><MoreHorizontal /></button>
          <button type="button" title="关闭" aria-label="关闭随身 AI" @click="closePanel"><X /></button>
        </header>

        <div ref="messageViewport" class="floating-agent-messages" aria-live="polite">
          <div v-if="!messages.length" class="floating-agent-empty">
            <Sparkles />
            <b>随时问我</b>
            <p>查询账本、工时或报表。需要填写和确认的操作，可进入完整工作台继续。</p>
            <div class="floating-agent-suggestions" aria-label="推荐问题"><button v-for="item in suggestions" :key="item" type="button" @click="prompt = item; submitPrompt()">{{ item }}</button></div>
          </div>
          <article v-for="item in messages" :key="item.id" :class="item.role">
            <div class="floating-agent-message" :class="{ failed: item.status === 'FAILED' }">
              <div v-if="item.role === 'assistant'" class="floating-agent-mark small"><Sparkles /></div>
              <div>
                <div v-if="item.role === 'assistant'" class="floating-agent-markdown" v-html="renderMarkdown(item.content)"></div>
                <p v-else>{{ item.content }}</p>
                <span v-if="item.typing" class="typing-cursor" aria-label="正在输入">▍</span>
                <small v-if="item.toolName"><Wrench />{{ item.toolName }} · {{ item.toolStatus }}</small>
              </div>
            </div>
          </article>
        </div>

        <div v-if="requiresFullView" class="floating-agent-handoff">
          <span><ClipboardCheck />此操作需要补充或确认</span>
          <button type="button" @click="openFullConversation">进入完整对话</button>
        </div>

        <form class="floating-agent-composer" @submit.prevent="submitPrompt">
          <label class="sr-only" for="floating-agent-prompt">给随身 AI 发送消息</label>
          <textarea
            id="floating-agent-prompt"
            v-model="prompt"
            v-auto-grow="132"
            rows="1"
            placeholder="询问当前工作台…"
            :disabled="sending"
            @keydown.enter.exact.prevent="submitPrompt"
          ></textarea>
          <div>
            <button class="floating-agent-expand" type="button" :disabled="!sessionId" @click="openFullConversation"><Maximize2 />完整对话</button>
            <button class="floating-agent-send" type="submit" :disabled="!prompt.trim() || sending" :aria-label="sending ? '正在生成' : '发送'"><LoaderCircle v-if="sending" class="spinning" /><ArrowUp v-else /></button>
          </div>
        </form>
      </section>
    </Transition>

    <div v-if="menuOpen" id="floating-agent-options" class="floating-agent-options" role="group" aria-label="随身 AI 入口选项">
      <button v-if="preferences.mode === 'floating'" type="button" @click="changeEntry({ mode: 'docked' })">贴边收起</button>
      <button v-else type="button" @click="changeEntry({ mode: 'floating' })">展开悬浮按钮</button>
      <button type="button" @click="changeEntry({ side: preferences.side === 'right' ? 'left' : 'right' })">停靠到{{ preferences.side === 'right' ? '左' : '右' }}侧</button>
      <button type="button" @click="hideEntry">隐藏入口</button>
      <small>可在基础设置或任务设置 → 外观中恢复</small>
    </div>
    <div class="floating-agent-controls">
      <button ref="trigger" class="floating-agent-trigger" type="button" :aria-expanded="open" aria-controls="floating-agent-panel" :aria-label="open ? '关闭随身 AI 对话' : preferences.mode === 'docked' ? '打开贴边随身 AI' : '打开随身 AI'" :title="open ? '关闭对话（保留入口）' : '打开随身 AI'" @click="togglePanel">
        <X v-if="open" />
        <Sparkles v-else />
        <span v-if="!open">AI</span>
      </button>
      <button v-if="preferences.mode === 'floating' && !open" class="floating-agent-options-trigger" type="button" aria-label="随身 AI 入口选项" :aria-expanded="menuOpen" aria-controls="floating-agent-options" @click="toggleMenu"><MoreHorizontal /></button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { ArrowUp, ClipboardCheck, LoaderCircle, Maximize2, MoreHorizontal, Sparkles, Wrench, X } from 'lucide-vue-next'
import { apiChatWithAssistant, apiCreateAgentSession, apiListAgentMessages, apiStreamAgentTurn } from '../../packages/api-client/src/index.js'
import { autoGrowTextarea } from '../directives/autoGrowTextarea.js'
import { message } from '../services/message.js'
import { useAppStore } from '../stores/app.js'
import { useAgentEntryPreferences } from '../utils/useAgentEntryPreferences.js'
import { useExclusivePanels } from '../utils/exclusivePanels.js'

const route = useRoute()
const router = useRouter()
const store = useAppStore()
const vAutoGrow = autoGrowTextarea
const { preferences, storageAvailable, setPreferences } = useAgentEntryPreferences()
const root = ref(null)
const trigger = ref(null)
const optionsTrigger = ref(null)
const menuOpen = ref(false)
const modalObscured = ref(false)
const messageViewport = ref(null)
const open = ref(false)
const prompt = ref('')
const sending = ref(false)
const sessionId = ref('')
const messages = ref([])
const requiresFullView = ref(false)
const suggestions = ['今天有哪些任务？', '创建一个任务', '总结一下今天', '规划今日安排']
let controller = null
let accountEpoch = 0
let modalObserver = null
let modalFrame = 0

const currentPageTitle = computed(() => route.meta?.title || '个人工作台')
const sessionStorageKey = computed(() => `workspace_floating_agent_session:${store.accountScope || 'local'}`)
watch(() => store.accountScope, () => {
  accountEpoch += 1
  controller?.abort()
  controller = null
  open.value = false
  menuOpen.value = false
  prompt.value = ''
  sending.value = false
  requiresFullView.value = false
  messages.value = []
  try { sessionId.value = localStorage.getItem(sessionStorageKey.value) || '' } catch { sessionId.value = '' }
  void restoreSessionMessages()
}, { immediate: true, flush: 'sync' })
watch(() => preferences.value.mode, mode => {
  if (mode === 'hidden') { open.value = false; menuOpen.value = false }
})
useExclusivePanels('floating-agent', [
  ['entry', () => open.value || menuOpen.value, value => {
    if (!value) { open.value = false; menuOpen.value = false }
  }]
])
function uid() { return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 9)}` }
function renderMarkdown(content) {
  return DOMPurify.sanitize(marked.parse(content || '', { breaks: true }), {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'del', 'h1', 'h2', 'h3', 'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'a'],
    ALLOWED_ATTR: ['href', 'title']
  })
}
function togglePanel() {
  menuOpen.value = false
  open.value = !open.value
  if (open.value) {
    void scrollToBottom()
    nextTick(() => root.value?.querySelector('textarea')?.focus())
  }
}
function closePanel() { open.value = false; menuOpen.value = false; nextTick(() => trigger.value?.focus()) }
function toggleMenu() {
  menuOpen.value = !menuOpen.value
  if (menuOpen.value) nextTick(() => root.value?.querySelector('.floating-agent-options button')?.focus())
}
function dismiss() {
  if (menuOpen.value) { menuOpen.value = false; (optionsTrigger.value || trigger.value)?.focus() }
  else closePanel()
}
function changeEntry(patch) {
  setPreferences(patch)
  menuOpen.value = false
  open.value = false
  if (!storageAvailable.value) message.warning('浏览器未允许保存入口设置，当前选择仅本次有效')
  nextTick(() => trigger.value?.focus())
}
function hideEntry() {
  changeEntry({ mode: 'hidden' })
  open.value = false
  message.success('随身 AI 入口已隐藏，可在基础设置或任务设置 → 外观中恢复')
}
async function scrollToBottom() { await nextTick(); if (messageViewport.value) messageViewport.value.scrollTop = messageViewport.value.scrollHeight }
async function ensureSession(text, epoch) {
  if (epoch !== accountEpoch) throw new DOMException('账号已切换', 'AbortError')
  if (sessionId.value) return sessionId.value
  const id = uid()
  const session = await apiCreateAgentSession({ id, title: text.slice(0, 24) || '随身 AI 对话' })
  if (epoch !== accountEpoch) throw new DOMException('账号已切换', 'AbortError')
  sessionId.value = session?.id || id
  try { localStorage.setItem(sessionStorageKey.value, sessionId.value) } catch { /* 禁止存储时仍保留本次会话 */ }
  return sessionId.value
}
function clearSession() {
  sessionId.value = ''
  try { localStorage.removeItem(sessionStorageKey.value) } catch { /* 禁止存储时仅清理内存 */ }
}
async function restoreSessionMessages() {
  if (!sessionId.value) return
  const epoch = accountEpoch
  try {
    const rows = await apiListAgentMessages(sessionId.value)
    if (epoch !== accountEpoch) return
    messages.value = (rows || []).slice(-20).map(row => ({
      id: `server-${row.id}`,
      role: row.role,
      content: row.content,
      typing: false,
      status: 'COMPLETED'
    }))
    await scrollToBottom()
  } catch (error) {
    if (epoch === accountEpoch && error?.response?.status === 404) clearSession()
  }
}
async function submitPrompt() {
  const text = prompt.value.trim()
  if (!text || sending.value) return
  const epoch = accountEpoch
  prompt.value = ''
  requiresFullView.value = false
  messages.value.push({ id: uid(), role: 'user', content: text })
  const assistant = { id: uid(), role: 'assistant', content: '', typing: true, status: 'RECEIVED', toolName: '', toolStatus: '' }
  messages.value.push(assistant)
  sending.value = true
  await scrollToBottom()
  let received = false
  try {
    const id = await ensureSession(text, epoch)
    if (epoch !== accountEpoch) return
    controller = new AbortController()
    await apiStreamAgentTurn(id, { clientRequestId: uid(), message: text, deepThinking: false }, async (event, data) => {
      if (epoch !== accountEpoch) return
      received = true
      if (event === 'assistant.delta') assistant.content += data.content || ''
      else if (event === 'tool.started') Object.assign(assistant, { toolName: data.name || '工具调用', toolStatus: '执行中' })
      else if (event === 'tool.completed') Object.assign(assistant, { toolName: data.execution?.name || assistant.toolName, toolStatus: data.execution?.status === 'SUCCESS' ? '已完成' : '执行结束' })
      else if (['input.required', 'confirmation.required'].includes(event)) requiresFullView.value = true
      else if (event === 'turn.completed') Object.assign(assistant, { content: data.response?.content || assistant.content || '本轮已完成。', typing: false, status: 'COMPLETED' })
      else if (event === 'turn.cancelled') Object.assign(assistant, { content: assistant.content || '本轮已取消。', typing: false, status: 'CANCELLED' })
      else if (event === 'turn.failed') throw new Error(data.message || 'Agent 执行失败')
      await scrollToBottom()
    }, controller.signal)
    if (assistant.typing) assistant.typing = false
  } catch (error) {
    if (epoch !== accountEpoch || error?.name === 'AbortError') return
    if (!received && sessionId.value) {
      try {
        const response = await apiChatWithAssistant(text, sessionId.value)
        if (epoch !== accountEpoch) return
        Object.assign(assistant, { content: response?.content || '本轮已完成。', typing: false, status: 'COMPLETED' })
        requiresFullView.value = Boolean(response?.actions?.length)
        return
      } catch (fallbackError) {
        if (epoch !== accountEpoch) return
        if (fallbackError?.response?.status === 404 || error?.status === 404) {
          clearSession()
          try {
            const renewedSessionId = await ensureSession(text, epoch)
            const response = await apiChatWithAssistant(text, renewedSessionId)
            if (epoch !== accountEpoch) return
            Object.assign(assistant, { content: response?.content || '本轮已完成。', typing: false, status: 'COMPLETED' })
            requiresFullView.value = Boolean(response?.actions?.length)
            return
          } catch (renewedError) { error = renewedError }
        } else error = fallbackError
      }
    }
    if (epoch !== accountEpoch) return
    Object.assign(assistant, { content: error?.response?.data?.detail || error?.message || '暂时无法连接 AI，请稍后重试。', typing: false, status: 'FAILED' })
    message.error('随身 AI 请求失败')
  } finally {
    if (epoch === accountEpoch) {
      controller = null
      sending.value = false
      await scrollToBottom()
    }
  }
}
async function openFullConversation() {
  if (!sessionId.value) return
  open.value = false
  await router.push({ path: '/agent/chat', query: { session: sessionId.value } })
}
function handleOutsidePointer(event) {
  if (root.value && !root.value.contains(event.target)) { open.value = false; menuOpen.value = false }
}
function syncModalObscured() {
  const selector = '[aria-modal="true"], dialog[open], .ui-dialog-backdrop, .ui-sheet-backdrop, .ui-drawer-backdrop, .clone-detail-backdrop, .clone-workspace-detail-backdrop, .clone-sidebar-backdrop, .clone-batch-backdrop, .mobile-more-backdrop, .mobile-nav-menu, .cal-settings-mask, .cal-task-backdrop'
  modalObscured.value = [...document.querySelectorAll(selector)].some(element => !root.value?.contains(element) && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden')
  if (modalObscured.value) { open.value = false; menuOpen.value = false }
}
function scheduleModalCheck(records) {
  if (records.every(record => root.value?.contains(record.target)) || modalFrame) return
  modalFrame = requestAnimationFrame(() => { modalFrame = 0; syncModalObscured() })
}
onMounted(() => {
  document.addEventListener('pointerdown', handleOutsidePointer)
  modalObserver = new MutationObserver(scheduleModalCheck)
  modalObserver.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden', 'aria-hidden', 'aria-modal', 'data-state'] })
  syncModalObscured()
})
onBeforeUnmount(() => {
  accountEpoch += 1
  document.removeEventListener('pointerdown', handleOutsidePointer)
  modalObserver?.disconnect()
  cancelAnimationFrame(modalFrame)
  controller?.abort()
})
</script>

<style scoped>
.floating-agent { position:fixed; z-index:55; right:max(24px,env(safe-area-inset-right)); bottom:calc(24px + env(safe-area-inset-bottom)); display:flex; flex-direction:column; align-items:flex-end; gap:12px; max-width:calc(100vw - 28px); pointer-events:none }
.floating-agent.left { right:auto; left:max(24px,env(safe-area-inset-left)); align-items:flex-start }
.floating-agent > * { pointer-events:auto }
.floating-agent-controls { display:flex; align-items:center; gap:4px }
.floating-agent-options-trigger { display:grid; width:44px; height:44px; place-items:center; padding:0; border:1px solid var(--line); border-radius:50%; background:var(--card); color:var(--ink); box-shadow:0 5px 16px rgba(15,23,42,.1) }
.floating-agent-options-trigger svg { width:19px }
.floating-agent-options { display:grid; width:230px; max-width:100%; gap:3px; padding:8px; border:1px solid var(--line); border-radius:14px; background:var(--card); color:var(--ink); box-shadow:0 12px 32px rgba(15,23,42,.16) }
.floating-agent.open .floating-agent-options { position:absolute; top:64px; right:12px; z-index:1 }
.floating-agent.left.open .floating-agent-options { right:12px; left:auto }
.floating-agent-options button { min-height:44px; padding:8px 12px; border:0; border-radius:8px; background:transparent; color:inherit; font:inherit; font-size:13px; text-align:left }
.floating-agent-options button:hover { background:var(--paper) }
.floating-agent-options small { padding:6px 12px; color:var(--muted); font-size:11px; line-height:1.5 }
.floating-agent :is(button,textarea):focus-visible { outline:2px solid var(--accent); outline-offset:3px }
.floating-agent.docked:not(.open) { right:max(0px,env(safe-area-inset-right)) }
.floating-agent.docked.left:not(.open) { right:auto; left:max(0px,env(safe-area-inset-left)) }
.floating-agent.docked:not(.open) .floating-agent-controls { gap:0; width:44px; overflow:hidden; border:0; border-radius:12px 0 0 12px; background:var(--card); box-shadow:0 5px 16px rgba(15,23,42,.1) }
.floating-agent.docked.left:not(.open) .floating-agent-controls { border-radius:0 12px 12px 0 }
.floating-agent.docked:not(.open) .floating-agent-trigger { min-width:44px; width:44px; height:44px; padding:0; border:0; border-radius:0; box-shadow:none; background:var(--card); color:var(--accent); transform:none }
.floating-agent.docked:not(.open) .floating-agent-trigger svg { display:none }
.floating-agent.docked:not(.open) .floating-agent-options-trigger { border:0; border-radius:0; box-shadow:none }
.floating-agent.docked:not(.open) .floating-agent-options { margin-inline:12px }
.floating-agent-trigger { display:flex; min-width:58px; height:58px; align-items:center; justify-content:center; gap:6px; padding:0 16px; border:1px solid color-mix(in srgb,var(--accent) 54%,var(--line)); border-radius:29px; background:var(--accent); color:#fff; box-shadow:0 14px 36px color-mix(in srgb,var(--accent) 34%,transparent); font-weight:800; transition:transform .18s ease,box-shadow .18s ease }
.floating-agent-trigger:hover { transform:translateY(-2px); box-shadow:0 18px 40px color-mix(in srgb,var(--accent) 42%,transparent) }
.floating-agent-trigger svg { width:21px; height:21px }
.floating-agent.open .floating-agent-trigger { min-width:48px; width:48px; height:48px; padding:0; background:var(--ink); border-color:var(--ink) }
.floating-agent.open .floating-agent-trigger span { display:none }
.floating-agent-panel { display:grid; grid-template-rows:auto minmax(180px,1fr) auto auto; width:min(390px,calc(100vw - 32px)); height:min(590px,calc(100dvh - 150px)); overflow:hidden; border:1px solid var(--line); border-radius:22px; background:var(--card); color:var(--ink); box-shadow:0 24px 70px rgba(15,23,42,.24) }
.floating-agent-panel > header { display:grid; grid-template-columns:38px minmax(0,1fr) 44px 34px; align-items:center; gap:10px; padding:15px 16px; border-bottom:1px solid var(--line) }
.floating-agent-panel > header > div { min-width:0 }.floating-agent-panel > header b,.floating-agent-panel > header small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap }.floating-agent-panel > header b { font-size:14px }.floating-agent-panel > header small { margin-top:2px; color:var(--muted); font-size:10px }
.floating-agent-panel > header button { display:grid; width:34px; height:34px; place-items:center; padding:0; border:0; border-radius:10px; background:transparent; color:var(--muted) }.floating-agent-panel > header button:hover { background:var(--paper); color:var(--ink) }.floating-agent-panel > header button svg { width:17px }
.floating-agent-panel > header .floating-agent-header-options { width:44px; height:44px }
.floating-agent-mark { display:grid; width:38px; height:38px; place-items:center; border-radius:12px; background:var(--accent-soft); color:var(--accent) }.floating-agent-mark svg { width:18px }.floating-agent-mark.small { flex:0 0 27px; width:27px; height:27px; border-radius:9px }.floating-agent-mark.small svg { width:14px }
.floating-agent-messages { min-height:0; overflow-y:auto; padding:16px; scroll-behavior:smooth }.floating-agent-empty { display:flex; height:100%; min-height:210px; flex-direction:column; align-items:center; justify-content:center; text-align:center }.floating-agent-empty > svg { width:28px; color:var(--accent) }.floating-agent-empty b { margin-top:10px; font-size:17px }.floating-agent-empty p { max-width:260px; margin:7px 0 0; color:var(--muted); font-size:12px; line-height:1.7 }
.floating-agent-messages article { display:flex; margin-bottom:14px }.floating-agent-messages article.user { justify-content:flex-end }.floating-agent-message { display:flex; max-width:92%; gap:8px }.floating-agent-message > div:last-child { min-width:0 }.floating-agent-message p { margin:0; white-space:pre-wrap }.floating-agent-messages article.user .floating-agent-message { padding:9px 12px; border-radius:15px 15px 4px 15px; background:var(--paper) }.floating-agent-message.failed { color:var(--down) }.floating-agent-message small { display:flex; align-items:center; gap:4px; margin-top:7px; color:var(--muted); font-size:10px }.floating-agent-message small svg { width:12px }
.floating-agent-markdown { overflow-wrap:anywhere; font-size:13px; line-height:1.7 }.floating-agent-markdown :deep(> :first-child) { margin-top:0 }.floating-agent-markdown :deep(> :last-child) { margin-bottom:0 }.floating-agent-markdown :deep(p),.floating-agent-markdown :deep(ul),.floating-agent-markdown :deep(ol),.floating-agent-markdown :deep(pre),.floating-agent-markdown :deep(blockquote) { margin:0 0 9px }.floating-agent-markdown :deep(ul),.floating-agent-markdown :deep(ol) { padding-left:19px }.floating-agent-markdown :deep(pre) { overflow-x:auto; padding:9px; border-radius:8px; background:var(--paper) }.floating-agent-markdown :deep(code) { font-family:ui-monospace,SFMono-Regular,Menlo,monospace }
.floating-agent-handoff { display:flex; align-items:center; justify-content:space-between; gap:10px; margin:0 14px 10px; padding:9px 10px; border:1px solid color-mix(in srgb,var(--accent) 34%,var(--line)); border-radius:10px; background:var(--accent-soft); color:var(--ink2); font-size:11px }.floating-agent-handoff span { display:flex; align-items:center; gap:5px }.floating-agent-handoff svg { width:14px }.floating-agent-handoff button { flex:0 0 auto; padding:6px 8px; border:0; border-radius:7px; background:var(--accent); color:#fff; font-size:10px; font-weight:700 }
.floating-agent-composer { margin:0 12px 12px; padding:8px 9px; border:1px solid var(--line2); border-radius:17px; background:var(--card); box-shadow:0 8px 24px rgba(15,23,42,.08) }.floating-agent-composer textarea { display:block; width:100%; min-height:42px; max-height:132px; resize:none; padding:6px 7px 8px; border:0; outline:0; background:transparent; color:var(--ink); font:inherit; line-height:1.5 }.floating-agent-composer textarea::placeholder { color:var(--muted) }.floating-agent-composer > div { display:flex; align-items:center; justify-content:space-between; padding-top:6px; border-top:1px solid var(--line) }.floating-agent-composer button { display:flex; align-items:center; justify-content:center; gap:5px; border:0 }.floating-agent-expand { padding:6px 7px; border-radius:7px!important; background:transparent; color:var(--muted); font-size:10px }.floating-agent-expand:hover:not(:disabled) { background:var(--paper); color:var(--ink) }.floating-agent-expand:disabled { opacity:.45 }.floating-agent-expand svg { width:13px }.floating-agent-send { width:34px; height:34px; border-radius:50%; background:var(--accent); color:#fff }.floating-agent-send:disabled { opacity:.4; cursor:not-allowed }.floating-agent-send svg { width:17px }
.spinning { animation:floating-agent-spin .8s linear infinite }.floating-agent-panel-enter-active,.floating-agent-panel-leave-active { transform-origin:right bottom; transition:opacity .18s ease,transform .2s cubic-bezier(.22,1,.36,1) }.floating-agent-panel-enter-from,.floating-agent-panel-leave-to { opacity:0; transform:translateY(12px) scale(.96) } @keyframes floating-agent-spin { to { transform:rotate(360deg) } }
@media (max-width:767px) { .floating-agent { right:max(14px,env(safe-area-inset-right)); bottom:calc(max(var(--app-bottombar-h, 56px), env(safe-area-inset-bottom)) + 32px) }.floating-agent.left { left:max(14px,env(safe-area-inset-left)); right:auto }.floating-agent-panel { width:calc(100vw - 28px); height:min(560px,calc(100dvh - 196px - env(safe-area-inset-bottom))); grid-template-rows:auto minmax(0,1fr) auto auto; border-radius:19px }.floating-agent-trigger { min-width:52px; height:52px; padding:0 14px }.floating-agent.open .floating-agent-trigger { width:44px; min-width:44px; height:44px } }
@media (prefers-reduced-motion:reduce) { .floating-agent-panel-enter-active,.floating-agent-panel-leave-active,.floating-agent-trigger { transition:none }.floating-agent-messages { scroll-behavior:auto }.spinning { animation:none } }
.floating-agent-trigger,
.floating-agent-handoff button,
.floating-agent-send { color:var(--accent-contrast,#fff) }
.floating-agent-trigger:hover,
.floating-agent-handoff button:hover,
.floating-agent-send:hover:not(:disabled) { background:color-mix(in srgb,var(--accent) 86%,#000); color:var(--accent-contrast,#fff) }
</style>
