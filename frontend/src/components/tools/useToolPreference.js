import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { apiGetToolPreference, apiPutToolPreference } from '../../../packages/api-client/src/index.js'

/**
 * 工具箱参数按用户保存到数据库。
 *
 * 房贷、个税都是纯前端计算，真正需要记住的只是「上次填的那套数」。
 * 这里做三件事：进页面回填、改动后（防抖）保存、把保存状态交给面板展示。
 * 回填与保存用序列化快照比对，避免「回填 → 触发 watch → 又存一遍」的回环。
 */
export function useToolPreference(tool, { read, apply, delay = 900 } = {}) {
  const status = ref('idle')
  const revision = ref(0)
  const saving = ref(false)
  const applying = ref(false)
  let timer
  let lastSerialized = ''

  const serialize = () => JSON.stringify(read())

  async function load() {
    status.value = 'loading'
    try {
      const result = await apiGetToolPreference(tool)
      revision.value = Number(result?.revision || 0)
      if (result?.payload) {
        applying.value = true
        apply(result.payload)
        lastSerialized = serialize()
        // 回填引发的深度 watch 会排到下一个微任务，等它过去再放开监听
        setTimeout(() => { applying.value = false }, 0)
      }
      status.value = result?.payload ? 'restored' : 'idle'
    } catch {
      // 未登录或离线：不影响本地测算，只是不显示「已保存」
      status.value = 'offline'
    }
  }

  async function flush() {
    if (applying.value || saving.value) return
    const serialized = serialize()
    if (serialized === lastSerialized) return
    saving.value = true
    status.value = 'saving'
    try {
      const saved = await apiPutToolPreference(tool, JSON.parse(serialized), revision.value)
      revision.value = Number(saved?.revision ?? revision.value + 1)
      lastSerialized = serialized
      status.value = 'saved'
    } catch (error) {
      status.value = error?.problem?.code === 'REVISION_CONFLICT' ? 'conflict' : 'error'
    } finally {
      saving.value = false
    }
  }

  watch(read, () => {
    if (applying.value) return
    clearTimeout(timer)
    timer = setTimeout(flush, delay)
  }, { deep: true })

  onMounted(load)
  onBeforeUnmount(() => clearTimeout(timer))

  return { status, revision, saving, reload: load, flush }
}

export function preferenceLabel(status) {
  return ({
    idle: '参数会自动保存到你的账号',
    loading: '正在读取上次参数…',
    restored: '已恢复上次的参数',
    saving: '保存中…',
    saved: '已保存到账号',
    conflict: '别处改过参数，刷新后可继续',
    offline: '当前离线，本次参数不会保存',
    error: '保存失败，稍后重试'
  })[status] || '参数会自动保存到你的账号'
}
