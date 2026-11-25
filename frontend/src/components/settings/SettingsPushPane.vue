<template>
  <div class="card set-group push-config">
    <div class="push-heading">
      <div>
        <h2>推送提醒</h2>
        <p class="hint">任务提醒、检查项提醒和习惯未打卡汇总，除了站内收件箱，还会推到下面开启的通道。手机上不用打开网页也能收到。</p>
      </div>
      <Button size="sm" variant="ghost" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</Button>
    </div>

    <section class="push-block feishu-link">
      <div class="push-heading">
        <h3>连接飞书（双向对话）</h3>
        <span v-if="feishu.binding" class="push-state is-ok">已连接</span>
        <span v-else-if="feishu.configured" class="push-state">未连接</span>
        <span v-else class="push-state">服务端未配置</span>
      </div>

      <p v-if="!feishu.configured" class="hint">
        服务端还没有飞书应用凭证（App ID / App Secret），这块功能暂时用不了。下面「飞书群机器人」的单向推送不受影响。
      </p>

      <template v-else-if="feishu.binding">
        <p class="hint">
          已连接到飞书账号 <code>{{ shortOpenId(feishu.binding.openId) }}</code>，绑定于 {{ formatTime(feishu.binding.boundAt) }}。
          在飞书里直接跟机器人说话就能记账、加任务；写操作会先在网页生成待审批。
        </p>
        <div class="push-actions">
          <Button size="sm" variant="ghost" :disabled="feishuBusy" @click="unbindFeishu">解除绑定</Button>
        </div>
      </template>

      <template v-else>
        <p class="hint">
          绑定后在飞书里直接说「昨天午饭 35 现金」「明天下午三点提醒我交周报」，
          机器人会调站内的 AI 助手处理；同一个会话在网页的「AI 会话」里也能接着聊。
        </p>
        <div class="feishu-bind-row">
          <Button size="sm" :disabled="feishuBusy" @click="createBindCode">
            {{ feishuBusy ? '生成中…' : bindCode ? '重新生成' : '生成绑定码' }}
          </Button>
          <div v-if="bindCode" class="feishu-code">
            <b>{{ bindCode.code }}</b>
            <small>10 分钟内有效 · 在飞书里给机器人发送「绑定 {{ bindCode.code }}」</small>
          </div>
        </div>
      </template>
    </section>

    <div class="push-channels">
      <article v-for="card in channels" :key="card.kind" class="push-channel">
        <header>
          <div class="push-channel-title">
            <b>{{ card.title }}</b>
            <small>{{ card.desc }}</small>
          </div>
          <label class="push-switch">
            <input type="checkbox" :checked="forms[card.kind].enabled" @change="toggle(card.kind, $event.target.checked)" />
            <span>{{ forms[card.kind].enabled ? '已启用' : '已关闭' }}</span>
          </label>
        </header>

        <div class="push-fields">
          <label v-for="field in card.fields" :key="field.key">
            <span>{{ field.label }}</span>
            <input
              v-model="forms[card.kind].config[field.key]"
              type="text"
              :placeholder="field.placeholder"
              autocomplete="off"
              spellcheck="false"
            />
            <small v-if="field.hint">{{ field.hint }}</small>
          </label>
        </div>

        <footer>
          <span class="push-state" :class="stateClass(card.kind)">{{ stateText(card.kind) }}</span>
          <div class="push-actions">
            <Button size="sm" variant="ghost" :disabled="testing === card.kind || saving === card.kind" @click="test(card.kind)">
              {{ testing === card.kind ? '发送中…' : '发送测试' }}
            </Button>
            <Button size="sm" :disabled="saving === card.kind" @click="save(card.kind)">{{ saving === card.kind ? '保存中…' : '保存' }}</Button>
          </div>
        </footer>
      </article>
    </div>

    <section class="push-block">
      <h3>提醒范围</h3>
      <div class="push-toggles">
        <label><input v-model="preference.taskReminder" type="checkbox" />任务到期提醒</label>
        <label><input v-model="preference.checklistReminder" type="checkbox" />检查项提醒</label>
        <label><input v-model="preference.habitDigest" type="checkbox" />习惯未打卡汇总</label>
      </div>
      <div class="push-row">
        <label>习惯汇总时间
          <select v-model.number="preference.habitDigestHour">
            <option v-for="hour in hours" :key="hour" :value="hour">{{ String(hour).padStart(2, '0') }}:00</option>
          </select>
          <small>按账号时区（{{ timezone }}）当天到这个点，还有没打卡的习惯就推一条汇总。</small>
        </label>
        <label class="push-quiet">静默时段
          <span class="push-quiet-range">
            <input v-model="preference.quietFrom" placeholder="23:00" maxlength="5" />
            <i>–</i>
            <input v-model="preference.quietTo" placeholder="07:30" maxlength="5" />
          </span>
          <small>留空表示不静默；跨零点可直接写 23:00 – 07:30。静默期间不推送，也不补发。</small>
        </label>
      </div>
      <div class="push-actions">
        <Button size="sm" :disabled="savingPreference" @click="savePreference">{{ savingPreference ? '保存中…' : '保存提醒设置' }}</Button>
        <span v-if="preferenceNote" class="hint">{{ preferenceNote }}</span>
      </div>
    </section>

    <section class="push-block">
      <div class="push-heading">
        <h3>最近发送</h3>
        <Button size="sm" variant="ghost" :disabled="loading" @click="loadDeliveries">刷新记录</Button>
      </div>
      <div v-if="deliveries.length" class="push-deliveries">
        <div v-for="(row, index) in deliveries" :key="`${row.createdAt}-${index}`" class="push-delivery">
          <span class="push-kind">{{ kindLabel(row.kind) }}</span>
          <b :class="statusClass(row.status)">{{ statusLabel(row.status) }}</b>
          <span class="push-delivery-title">{{ row.title }}</span>
          <small>{{ formatTime(row.createdAt) }}{{ row.detail ? ` · ${row.detail}` : '' }}</small>
        </div>
      </div>
      <p v-else class="hint">还没有推送记录。配置好通道后点「发送测试」会在这里留一条。</p>
    </section>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import Button from '../ui/Button.vue'
import { message } from '../../services/message.js'
import {
  apiFeishuBindCode, apiFeishuStatus, apiFeishuUnbind,
  apiGetPushPreference, apiListPushChannels, apiListPushDeliveries,
  apiSavePushChannel, apiSavePushPreference, apiTestPushChannel
} from '../../../packages/api-client/src/index.js'

const CHANNELS = [
  {
    kind: 'FEISHU',
    title: '飞书群机器人',
    desc: '建一个只拉自己的群，加「自定义机器人」后把 Webhook 粘过来；手机端是原生通知。',
    fields: [
      { key: 'webhook', label: 'Webhook 地址', placeholder: 'https://open.feishu.cn/open-apis/bot/v2/hook/…' },
      { key: 'secret', label: '签名密钥（可选）', placeholder: '机器人开了「签名校验」时填', hint: '不填表示机器人未开启签名校验。' }
    ]
  },
  {
    kind: 'BARK',
    title: 'Bark（iOS）',
    desc: 'App Store 装 Bark，首页复制 Device Key；通知进 iOS 通知中心，后台杀掉也能收到。',
    fields: [
      { key: 'key', label: 'Device Key', placeholder: '在 Bark App 首页复制' },
      { key: 'server', label: '服务器（可选）', placeholder: '默认 https://api.day.app', hint: '用自建 Bark 服务时填自己的地址。' }
    ]
  },
  {
    kind: 'EMAIL',
    title: '邮件',
    desc: '收件人在这里填；发件服务器是服务端环境变量（PUSH_SMTP_HOST 等），避免把邮箱密码存到网页里。',
    fields: [
      { key: 'to', label: '收件邮箱', placeholder: 'you@example.com' }
    ]
  }
]

const channels = CHANNELS
const hours = Array.from({ length: 24 }, (_, index) => index)
const rows = reactive({})
const forms = reactive(Object.fromEntries(CHANNELS.map(card => [card.kind, {
  enabled: false,
  config: Object.fromEntries(card.fields.map(field => [field.key, '']))
}])))
const preference = reactive({
  taskReminder: true,
  checklistReminder: true,
  habitDigest: true,
  habitDigestHour: 21,
  quietFrom: '',
  quietTo: '',
  revision: 0
})
const deliveries = ref([])
const loading = ref(false)
const saving = ref('')
const testing = ref('')
const savingPreference = ref(false)
const preferenceNote = ref('')
const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'

// 飞书自建应用：凭证在服务端，这里只管「这个账号有没有绑上飞书」
const feishu = reactive({ configured: false, encrypted: false, binding: null })
const bindCode = ref(null)
const feishuBusy = ref(false)

async function loadFeishu() {
  try {
    const status = await apiFeishuStatus()
    feishu.configured = Boolean(status?.configured)
    feishu.encrypted = Boolean(status?.encrypted)
    feishu.binding = status?.binding || null
  } catch {
    // 后端还没升级到带飞书接口的版本时不影响其它推送配置
    feishu.configured = false
  }
}

async function createBindCode() {
  feishuBusy.value = true
  try {
    bindCode.value = await apiFeishuBindCode()
  } catch (error) {
    message.error(error?.response?.data?.detail || '生成绑定码失败')
  } finally {
    feishuBusy.value = false
  }
}

async function unbindFeishu() {
  feishuBusy.value = true
  try {
    await apiFeishuUnbind()
    feishu.binding = null
    bindCode.value = null
    message.success('已解除飞书绑定')
  } catch (error) {
    message.error(error?.response?.data?.detail || '解除绑定失败')
  } finally {
    feishuBusy.value = false
  }
}

function shortOpenId(value) {
  if (!value) return ''
  return value.length <= 12 ? value : `${value.slice(0, 6)}…${value.slice(-4)}`
}

function applyRow(kind, row) {
  if (!row) return
  rows[kind] = row
  forms[kind].enabled = Boolean(row.enabled)
  for (const field of CHANNELS.find(item => item.kind === kind).fields) {
    forms[kind].config[field.key] = row.config?.[field.key] || ''
  }
}

async function load() {
  loading.value = true
  try {
    const [list, pref] = await Promise.all([apiListPushChannels(), apiGetPushPreference(), loadFeishu()])
    for (const row of list || []) applyRow(row.kind, row)
    if (pref) {
      preference.taskReminder = Boolean(pref.taskReminder)
      preference.checklistReminder = Boolean(pref.checklistReminder)
      preference.habitDigest = Boolean(pref.habitDigest)
      preference.habitDigestHour = Number(pref.habitDigestHour ?? 21)
      preference.quietFrom = pref.quietFrom || ''
      preference.quietTo = pref.quietTo || ''
      preference.revision = Number(pref.revision || 0)
    }
  } catch (error) {
    message.error(error?.response?.data?.detail || '推送配置加载失败')
  } finally {
    loading.value = false
  }
}

async function loadDeliveries() {
  try {
    deliveries.value = await apiListPushDeliveries(12)
  } catch {
    deliveries.value = []
  }
}

async function save(kind, quietMode = false) {
  const form = forms[kind]
  saving.value = kind
  try {
    const row = await apiSavePushChannel(kind, {
      enabled: form.enabled,
      config: { ...form.config },
      revision: rows[kind]?.revision
    })
    applyRow(kind, row)
    rows[kind] = row
    if (!quietMode) message.success('已保存')
    return true
  } catch (error) {
    message.error(error?.response?.data?.detail || '保存失败')
    return false
  } finally {
    saving.value = ''
  }
}

async function toggle(kind, enabled) {
  const before = forms[kind].enabled
  forms[kind].enabled = enabled
  // 还没保存过、或必填项还空着时，直接把开关留在界面上，等「保存」一起提交；
  // 否则后端会因为缺字段回 400，弹一个"Bark Device Key 不能为空"的错，看起来像开关坏了。
  const row = rows[kind]
  if (!row || !row.ready) {
    message.info(enabled ? '填好下面的配置后点「保存」生效' : '已关闭，点「保存」生效')
    return
  }
  const ok = await save(kind, true)
  if (!ok) forms[kind].enabled = before
  else message.success(enabled ? '已启用该通道' : '已关闭该通道')
}

async function test(kind) {
  const saved = await save(kind, true)
  if (!saved) return
  testing.value = kind
  try {
    const result = await apiTestPushChannel(kind)
    if (result?.ok) message.success('测试消息已发出，去手机上看一眼')
    else message.error(result?.detail || '发送失败')
    await Promise.all([load(), loadDeliveries()])
  } catch (error) {
    message.error(error?.response?.data?.detail || '测试发送失败')
  } finally {
    testing.value = ''
  }
}

async function savePreference() {
  savingPreference.value = true
  preferenceNote.value = ''
  try {
    const result = await apiSavePushPreference({
      taskReminder: preference.taskReminder,
      checklistReminder: preference.checklistReminder,
      habitDigest: preference.habitDigest,
      habitDigestHour: preference.habitDigestHour,
      quietFrom: preference.quietFrom || null,
      quietTo: preference.quietTo || null,
      revision: preference.revision
    })
    preference.revision = Number(result?.revision || 0)
    preferenceNote.value = '提醒设置已保存'
  } catch (error) {
    preferenceNote.value = error?.response?.data?.detail || '提醒设置保存失败'
  } finally {
    savingPreference.value = false
  }
}

function stateText(kind) {
  const row = rows[kind]
  if (!row) return '还没保存'
  if (!row.ready) return kind === 'EMAIL' ? '服务端未配置 SMTP，填了收件人也发不出' : '还缺必填项'
  if (row.lastTestOk === false) return `上次测试失败：${row.lastError || '未知原因'}`
  if (row.lastTestOk === true) return `上次测试成功${row.lastTestAt ? ` · ${formatTime(row.lastTestAt)}` : ''}`
  if (row.lastSentAt) return `最近推送 ${formatTime(row.lastSentAt)}`
  return '已保存，还没测试'
}

function stateClass(kind) {
  const row = rows[kind]
  if (!row || !row.ready) return 'is-off'
  if (row.lastTestOk === false) return 'is-bad'
  return 'is-ok'
}

function kindLabel(kind) {
  return ({ FEISHU: '飞书', BARK: 'Bark', EMAIL: '邮件', '-': '—' })[kind] || kind
}

function statusLabel(status) {
  return ({ SENT: '已发送', FAILED: '失败', SKIPPED: '已跳过' })[status] || status
}

function statusClass(status) {
  return status === 'SENT' ? 'is-ok' : status === 'FAILED' ? 'is-bad' : 'is-off'
}

function formatTime(value) {
  if (!value) return ''
  try {
    return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
  } catch {
    return value
  }
}

onMounted(() => {
  load()
  loadDeliveries()
})
</script>

<style scoped>
.push-config { display: grid; gap: 16px; }
.push-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.push-heading h2 { margin: 0; }
.push-heading h3 { margin: 0; font-size: 13px; }
.push-heading .hint { margin: 6px 0 0; max-width: 720px; }
.push-channels { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 12px; }
.push-channel { display: flex; min-width: 0; flex-direction: column; gap: 12px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; background: var(--paper); }
.push-channel header { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.push-channel-title { min-width: 0; }
.push-channel-title b { display: block; font-size: 13.5px; }
.push-channel-title small { display: block; margin-top: 3px; color: var(--muted); font-size: 11.5px; line-height: 1.55; }
.push-switch { display: inline-flex; flex: none; align-items: center; gap: 5px; color: var(--ink2); font-size: 11.5px; cursor: pointer; }
.push-switch input { width: 15px; height: 15px; accent-color: var(--accent); }
.push-fields { display: grid; gap: 9px; }
.push-fields label { display: grid; gap: 4px; font-size: 11.5px; color: var(--ink2); }
.push-fields input { min-width: 0; height: 32px; padding: 0 9px; border: 1px solid var(--line2); border-radius: 8px; background: var(--card); color: var(--ink); font-size: 12px; }
.push-fields input:focus-visible { border-color: var(--accent); outline: none; }
.push-fields small { color: var(--muted); font-size: 10.5px; line-height: 1.5; }
.push-channel footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: auto; padding-top: 10px; border-top: 1px dashed var(--line); }
.push-state { min-width: 0; overflow: hidden; color: var(--muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.push-state.is-ok { color: var(--accent); }
.push-state.is-bad { color: var(--down); }
.push-state.is-off { color: var(--muted); }
.push-actions { display: inline-flex; flex: none; align-items: center; gap: 7px; }
.push-block { display: grid; gap: 12px; padding-top: 14px; border-top: 1px solid var(--line); }
.push-toggles { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.push-toggles label { display: inline-flex; align-items: center; gap: 6px; color: var(--ink2); font-size: 12.5px; }
.push-toggles input { width: 15px; height: 15px; accent-color: var(--accent); }
.push-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }
.push-row > label { display: grid; gap: 5px; color: var(--ink2); font-size: 12px; justify-items: start; }
.push-row select { width: 104px; height: 32px; padding: 0 8px; border: 1px solid var(--line2); border-radius: 8px; background: var(--card); color: var(--ink); font-size: 12px; }
.push-row small { color: var(--muted); font-size: 10.5px; line-height: 1.5; }
.push-quiet-range { display: inline-flex; align-items: center; gap: 6px; }
.push-quiet-range input { width: 78px; height: 32px; padding: 0 8px; border: 1px solid var(--line2); border-radius: 8px; background: var(--card); color: var(--ink); font-size: 12px; }
.push-quiet-range i { color: var(--muted); font-style: normal; }
.push-deliveries { display: grid; gap: 6px; }
.push-delivery { display: grid; grid-template-columns: 46px 54px minmax(0, 1fr) auto; align-items: baseline; gap: 9px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 9px; background: var(--paper); font-size: 11.5px; }
.push-kind { color: var(--ink2); font-weight: 650; }
.push-delivery b { font-size: 11px; }
.push-delivery b.is-ok { color: var(--accent); }
.push-delivery b.is-bad { color: var(--down); }
.push-delivery b.is-off { color: var(--muted); }
.push-delivery-title { min-width: 0; overflow: hidden; color: var(--ink); text-overflow: ellipsis; white-space: nowrap; }
.push-delivery small { color: var(--muted); font-size: 10.5px; text-align: right; }
.feishu-bind-row { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.feishu-code { display: flex; align-items: center; gap: 10px; padding: 7px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--paper); }
.feishu-code b { color: var(--accent); font-size: 17px; font-variant-numeric: tabular-nums; letter-spacing: 2px; }
.feishu-code small { color: var(--muted); font-size: 10.5px; }
.feishu-link code { padding: 1px 5px; border-radius: 5px; background: var(--paper); color: var(--ink2); font-size: 11px; }
@media (max-width: 720px) {
  .push-channel footer { flex-wrap: wrap; }
  .push-state { flex: 1 1 100%; }
  .push-actions { width: 100%; justify-content: flex-end; }
  .push-delivery { grid-template-columns: 42px 50px minmax(0, 1fr); }
  .push-delivery small { grid-column: 1 / -1; text-align: left; }
}
</style>
