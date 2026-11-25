<template>
  <div class="card set-group calendar-feed">
    <h2>日历订阅</h2>
    <p class="hint">
      把「未完成且有截止时间」的任务导出成 iCalendar（.ics），订阅到飞书、苹果日历或 Outlook。
      订阅是只读的：外部日历会定期自己来拉，改完任务不用重新订阅。
    </p>

    <div class="row">
      <div class="lbl">
        包含任务描述
        <small>任务备注会写进事件详情；默认关闭，避免敏感内容进第三方日历</small>
      </div>
      <div class="ctl">
        <SettingSwitch :model-value="includeDescription" label="包含任务描述" :disabled="savingDescription"
          @update:model-value="toggleDescription" />
      </div>
    </div>

    <div class="row">
      <div class="lbl">
        订阅地址
        <small v-if="feed.active">已生成 {{ feed.hint }}<template v-if="lastAccessed"> · 最近拉取 {{ lastAccessed }}</template></small>
        <small v-else>还没有生成，生成后才能添加订阅</small>
      </div>
      <div class="ctl">
        <Button v-if="!feed.active" size="sm" :disabled="busy" @click="create">{{ busy ? '生成中…' : '生成订阅地址' }}</Button>
        <template v-else>
          <Button size="sm" variant="ghost" :disabled="busy" @click="rotate">重新生成</Button>
          <Button size="sm" variant="danger" :disabled="busy" @click="revoke">撤销</Button>
        </template>
      </div>
    </div>

    <div v-if="url" class="feed-url">
      <code ref="codeRef">{{ url }}</code>
      <Button size="sm" variant="ghost" @click="copy">复制</Button>
    </div>
    <p v-else-if="feed.active" class="hint">
      这条地址是旧版本生成的，服务端只留了摘要，无法回显。点「重新生成」会得到一条随时可查看的新地址，旧地址立即失效。
    </p>
    <p v-else class="hint">还没有生成地址。生成后可以随时回到这里查看和复制。</p>

    <ol class="feed-steps">
      <li><b>飞书</b>：日历 → 左侧「添加日历」→「订阅日历」→ 粘贴地址 → 订阅。订阅来的日历只读，颜色可自选。</li>
      <li><b>苹果日历</b>：文件 → 新建日历订阅 → 粘贴地址。</li>
      <li><b>Outlook</b>：添加日历 → 从 Internet 订阅 → 粘贴地址。</li>
    </ol>
    <p class="hint">
      同步周期由客户端决定（飞书实测几分钟到半小时，不是实时）；提醒时间随任务的提醒设置一起导出，
      所以订阅端也会按点弹提醒。
    </p>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import Button from '../ui/Button.vue'
import SettingSwitch from './SettingSwitch.vue'
import { message } from '../../services/message.js'
import { copyText } from '../../utils/clipboard.js'
import {
  apiCreateTaskFeedToken, apiGetTaskFeedToken, apiGetTaskSettings, apiRevokeTaskFeedToken,
  apiRotateTaskFeedToken, apiUpdateTaskSettings
} from '../../../packages/api-client/src/index.js'

const feed = reactive({ active: false, hint: '', createdAt: null, lastAccessedAt: null })
const url = ref('')
const codeRef = ref(null)
const busy = ref(false)
const includeDescription = ref(false)
const savingDescription = ref(false)
let settingsRevision = null

function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const lastAccessed = ref('')

async function load() {
  try {
    const data = await apiGetTaskFeedToken()
    feed.active = Boolean(data?.active)
    feed.hint = data?.hint || ''
    feed.createdAt = data?.createdAt || null
    feed.lastAccessedAt = data?.lastAccessedAt || null
    lastAccessed.value = formatTime(data?.lastAccessedAt)
    // 地址随状态一起返回，刷新页面后仍能看到并复制，不必重新生成
    if (data?.url) url.value = data.url
    if (!feed.active) url.value = ''
  } catch {
    // 后端还没升级到带订阅接口的版本时，整块保持空态，不影响其它设置
  }
}

async function loadSettings() {
  try {
    const data = await apiGetTaskSettings()
    includeDescription.value = Boolean(data?.feedIncludeDescription)
    settingsRevision = data?.revision ?? null
  } catch {
    // 设置接口不可用时开关保持关闭，不影响订阅地址本身
  }
}

async function toggleDescription(next) {
  if (savingDescription.value) return
  const previous = includeDescription.value
  includeDescription.value = next
  savingDescription.value = true
  try {
    const saved = await apiUpdateTaskSettings({ feedIncludeDescription: next }, settingsRevision)
    includeDescription.value = Boolean(saved?.feedIncludeDescription)
    settingsRevision = saved?.revision ?? settingsRevision
    message.success(next ? '订阅会带上任务描述' : '订阅不再带任务描述')
  } catch (error) {
    includeDescription.value = previous
    message.error(error?.response?.data?.detail || '保存失败，请稍后重试')
  } finally {
    savingDescription.value = false
  }
}

async function create() {
  busy.value = true
  try {
    const data = await apiCreateTaskFeedToken()
    url.value = data?.url || ''
    await load()
    message.success('订阅地址已生成，复制后去日历里添加')
  } catch (error) {
    message.error(error?.response?.data?.detail || '生成订阅地址失败')
  } finally {
    busy.value = false
  }
}

async function rotate() {
  if (!window.confirm('重新生成会让旧地址立即失效，已订阅的日历要重新粘贴新地址。确定继续？')) return
  busy.value = true
  try {
    const data = await apiRotateTaskFeedToken()
    url.value = data?.url || ''
    await load()
    message.success('已换成新地址，记得去日历里更新订阅')
  } catch (error) {
    message.error(error?.response?.data?.detail || '重新生成失败')
  } finally {
    busy.value = false
  }
}

async function revoke() {
  if (!window.confirm('撤销后所有已订阅的日历都会拉不到任务。确定撤销？')) return
  busy.value = true
  try {
    await apiRevokeTaskFeedToken()
    url.value = ''
    await load()
    message.success('已撤销订阅地址')
  } catch (error) {
    message.error(error?.response?.data?.detail || '撤销失败')
  } finally {
    busy.value = false
  }
}

async function copy() {
  try {
    await copyText(url.value)
    message.success('已复制')
  } catch {
    // 剪贴板被浏览器策略拦住时，至少把地址选中，用户按一次 Cmd/Ctrl+C 就能带走
    const node = codeRef.value
    if (node && typeof window !== 'undefined' && window.getSelection) {
      const range = document.createRange()
      range.selectNodeContents(node)
      const selection = window.getSelection()
      selection.removeAllRanges()
      selection.addRange(range)
      message.error('浏览器拦截了自动复制，地址已选中，请按 Cmd/Ctrl + C')
      return
    }
    message.error('复制失败，请手动选中地址')
  }
}

onMounted(() => {
  load()
  loadSettings()
})
</script>

<style scoped>
.calendar-feed .hint { margin: 0 0 12px; color: var(--muted); font-size: 11.5px; line-height: 1.7; }
.feed-url {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 4px 0 10px;
  padding: 9px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--paper);
}
.feed-url code {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  line-height: 1.5;
  word-break: break-all;
  color: var(--ink);
}
.feed-steps {
  margin: 12px 0 10px;
  padding-left: 18px;
  color: var(--muted);
  font-size: 11.5px;
  line-height: 1.9;
}
.feed-steps b { color: var(--ink); font-weight: 600; }
@media (max-width: 900px) {
  .feed-url { flex-direction: column; align-items: stretch; }
  .feed-url :deep(button) { min-height: 36px; }
}
</style>
