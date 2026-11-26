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
      <li><b>苹果日历</b>：文件 → 新建日历订阅 → 粘贴地址。</li>
      <li><b>Outlook</b>：添加日历 → 从 Internet 订阅 → 粘贴地址。</li>
      <li><b>飞书</b>：不支持这个地址（只能搜索飞书内日历，或绑定 Google/Outlook 账号），请用下面的「飞书日历同步」。</li>
    </ol>
    <p class="hint">
      同步周期由客户端决定（苹果/Outlook 通常几小时）；提醒时间随任务的提醒设置一起导出，
      所以订阅端也会按点弹提醒。
    </p>
  </div>

  <div class="card set-group feishu-calendar">
    <h2>飞书日历同步</h2>
    <p class="hint">
      飞书没有「通过网址订阅」的能力（自带日历：只能搜联系人和公共日历；第三方：只认 Google / Exchange / Outlook 账号），
      所以这里换一种做法——应用在飞书里建一个「WorkBoard」日历并共享给你，定时把任务和倒数日写成日程。
      改完任务不用重新导入，最多 5 分钟自动生效。
    </p>

    <div class="row">
      <div class="lbl">
        同步状态
        <small v-if="fc.enabled">
          已开启 · {{ fc.lastEventCount }} 条日程<template v-if="fc.lastSyncedAt"> · 最近同步 {{ formatTime(fc.lastSyncedAt) }}</template>
        </small>
        <small v-else-if="!fc.configured">服务端未配置飞书应用凭证</small>
        <small v-else-if="!fc.bound">请先在「消息推送」里绑定飞书账号</small>
        <small v-else>未开启，开启后飞书左侧会多一个「WorkBoard」日历</small>
      </div>
      <div class="ctl">
        <Button v-if="!fc.enabled" size="sm" :disabled="fcBusy || !fc.bound" @click="enableFeishu">
          {{ fcBusy ? '创建中…' : '开启同步' }}
        </Button>
        <template v-else>
          <Button size="sm" variant="ghost" :disabled="fcBusy" @click="syncFeishu">
            {{ fcBusy ? '同步中…' : '立即同步' }}
          </Button>
          <Button size="sm" variant="danger" :disabled="fcBusy" @click="disableFeishu">关闭</Button>
        </template>
      </div>
    </div>

    <div v-if="fc.enabled" class="row">
      <div class="lbl">
        同步内容
        <small>任务取近 7 天到未来 45 天，倒数日取近 7 天到未来一年</small>
      </div>
      <div class="ctl">
        <SettingSwitch :model-value="fc.includeTasks" label="任务" :disabled="fcBusy"
          @update:model-value="value => updateScope({ includeTasks: value })" />
        <SettingSwitch :model-value="fc.includeCountdowns" label="倒数日" :disabled="fcBusy"
          @update:model-value="value => updateScope({ includeCountdowns: value })" />
      </div>
    </div>

    <p v-if="fc.lastError" class="hint error">上次同步失败：{{ fc.lastError }}</p>
    <p v-if="fc.enabled" class="hint">
      关闭只停止维护，飞书里的日历和已有日程会保留，可以自己删。同步是单向的：在飞书里改日程不会回写任务。
    </p>
  </div>

  <div class="card set-group caldav">
    <h2>日历同步（CalDAV）</h2>
    <p class="hint">
      CalDAV 是日历的通用协议，iPhone 日历、安卓的 DAVx5、Windows 的 Thunderbird 都原生支持。
      任务、倒数日，以及每天的工时/专注/流水会写进这个日历——后三者合成一条「MM-DD 记录」，点开即为当天明细。
    </p>

    <div class="row">
      <div class="lbl">
        服务器地址
        <small>添加日历时填这个地址与下面的用户名</small>
      </div>
      <div class="ctl">
        <code class="caldav-url">{{ cd.url || '服务端未配置' }}</code>
      </div>
    </div>

    <div class="row">
      <div class="lbl">
        账号
        <small>密码由部署时生成，填写在客户端的「密码」一栏</small>
      </div>
      <div class="ctl"><code>{{ cd.username || '-' }}</code></div>
    </div>

    <div class="row">
      <div class="lbl">
        同步状态
        <small v-if="cd.enabled">
          已开启 · {{ cd.lastEventCount }} 个事件<template v-if="cd.lastSyncedAt"> · 最近同步 {{ formatTime(cd.lastSyncedAt) }}</template>
        </small>
        <small v-else>未开启</small>
      </div>
      <div class="ctl">
        <Button size="sm" variant="ghost" :disabled="cdBusy" @click="syncCaldav">
          {{ cdBusy ? '同步中…' : '立即同步' }}
        </Button>
        <SettingSwitch :model-value="cd.enabled" label="启用" :disabled="cdBusy"
          @update:model-value="toggleCaldav" />
      </div>
    </div>

    <p v-if="cd.lastError" class="hint error">上次同步失败：{{ cd.lastError }}</p>

    <div class="feed-steps">
      <p class="hint"><b>添加方法</b></p>
      <ol>
        <li><b>iPhone / Mac</b>：设置 → 日历 → 账户 → 添加账户 → 其他 → 「添加 CalDAV 账户」→ 服务器填上面的地址，用户名密码照填。</li>
        <li><b>安卓</b>：装 DAVx5 → 添加账户 → 选「使用 URL 和用户名登录」→ 填上面的地址与账号。</li>
        <li><b>Windows</b>：Thunderbird → 新建日历 → 「位于网络上」→ 填地址与账号。</li>
      </ol>
      <p class="hint">
        双向同步：在客户端改**标题或日期**会回写到任务（每 5 分钟检查一次）；完成状态、优先级等字段仍以网站为准。
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import Button from '../ui/Button.vue'
import SettingSwitch from './SettingSwitch.vue'
import { message } from '../../services/message.js'
import { copyText } from '../../utils/clipboard.js'
import { apiCalDavStatus, apiCalDavSync, apiCalDavToggle } from '../../../packages/api-client/src/index.js'
import {
  apiCreateTaskFeedToken, apiFeishuCalendarDisable, apiFeishuCalendarEnable, apiFeishuCalendarScope,
  apiFeishuCalendarStatus, apiFeishuCalendarSync, apiGetTaskFeedToken, apiGetTaskSettings,
  apiRevokeTaskFeedToken, apiRotateTaskFeedToken, apiUpdateTaskSettings
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

// ---------------------------------------------------------------- 飞书日历同步

const fc = reactive({
  configured: false, bound: false, enabled: false, calendarId: '',
  lastSyncedAt: null, lastEventCount: 0, lastError: null,
  includeTasks: true, includeCountdowns: true
})
const fcBusy = ref(false)

async function loadFeishu() {
  try {
    Object.assign(fc, await apiFeishuCalendarStatus())
  } catch {
    // 后端未升级到带日历同步的版本时，这张卡保持未开启状态
  }
}

async function enableFeishu() {
  if (fcBusy.value) return
  fcBusy.value = true
  try {
    const result = await apiFeishuCalendarEnable()
    await loadFeishu()
    message.success(`已在飞书创建日历并写入 ${result?.total ?? 0} 条日程，左侧勾选「WorkBoard」就能看到`)
  } catch (error) {
    await loadFeishu()
    message.error(error?.response?.data?.detail || '开启失败，请确认已绑定飞书账号并已开通日历权限')
  } finally {
    fcBusy.value = false
  }
}

async function syncFeishu() {
  if (fcBusy.value) return
  fcBusy.value = true
  try {
    const result = await apiFeishuCalendarSync()
    await loadFeishu()
    message.success(`同步完成：新增 ${result?.created ?? 0}、更新 ${result?.updated ?? 0}、移除 ${result?.deleted ?? 0}`)
  } catch (error) {
    await loadFeishu()
    message.error(error?.response?.data?.detail || '同步失败')
  } finally {
    fcBusy.value = false
  }
}

async function disableFeishu() {
  if (!window.confirm('关闭后不再同步新改动，飞书里的日历和已有日程不会被删除。确定关闭？')) return
  fcBusy.value = true
  try {
    await apiFeishuCalendarDisable()
    await loadFeishu()
    message.success('已关闭飞书日历同步')
  } catch (error) {
    message.error(error?.response?.data?.detail || '关闭失败')
  } finally {
    fcBusy.value = false
  }
}

async function updateScope(patch) {
  if (fcBusy.value) return
  const previous = { includeTasks: fc.includeTasks, includeCountdowns: fc.includeCountdowns }
  Object.assign(fc, patch)
  fcBusy.value = true
  try {
    Object.assign(fc, await apiFeishuCalendarScope(patch))
    // 改完范围立刻重算一次，否则要等下一个 5 分钟节拍才有变化
    const result = await apiFeishuCalendarSync()
    await loadFeishu()
    message.success(`已更新：新增 ${result?.created ?? 0}、移除 ${result?.deleted ?? 0}`)
  } catch (error) {
    Object.assign(fc, previous)
    message.error(error?.response?.data?.detail || '保存失败')
  } finally {
    fcBusy.value = false
  }
}

// ---------------------------------------------------------------- CalDAV

const cd = reactive({
  configured: false, enabled: false, url: '', username: '',
  lastSyncedAt: null, lastEventCount: 0, lastError: null
})
const cdBusy = ref(false)

async function loadCaldav() {
  try {
    Object.assign(cd, await apiCalDavStatus())
  } catch {
    // 后端版本较旧时保持未配置状态，不打断页面
  }
}

async function syncCaldav() {
  if (cdBusy.value) return
  cdBusy.value = true
  try {
    const result = await apiCalDavSync()
    await loadCaldav()
    message.success(`已同步 ${result?.total ?? 0} 个事件（新增 ${result?.created ?? 0}、更新 ${result?.updated ?? 0}）`)
  } catch (error) {
    await loadCaldav()
    message.error(error?.response?.data?.detail || '同步失败，请稍后重试')
  } finally {
    cdBusy.value = false
  }
}

async function toggleCaldav(value) {
  if (cdBusy.value) return
  cdBusy.value = true
  try {
    Object.assign(cd, await apiCalDavToggle(value))
    message.success(value ? '已开启日历同步' : '已关闭日历同步')
  } catch (error) {
    await loadCaldav()
    message.error(error?.response?.data?.detail || '保存失败')
  } finally {
    cdBusy.value = false
  }
}

onMounted(() => {
  load()
  loadSettings()
  loadFeishu()
  loadCaldav()
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
.caldav-url { display: inline-block; max-width: 100%; overflow-wrap: anywhere; font-size: 12px; }
.feed-steps b { color: var(--ink); font-weight: 600; }
.feishu-calendar .hint { margin: 0 0 12px; color: var(--muted); font-size: 11.5px; line-height: 1.7; }
.feishu-calendar .hint.error { color: var(--danger, #d64545); }
@media (max-width: 900px) {
  .feed-url { flex-direction: column; align-items: stretch; }
  .feed-url :deep(button) { min-height: 36px; }
}
</style>
