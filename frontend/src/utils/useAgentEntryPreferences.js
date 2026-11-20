import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAppStore } from '../stores/app.js'
import { agentEntryStorageKey, parseAgentEntryPreferences, readAgentEntryPreferences, writeAgentEntryPreferences } from './agentEntryPreferences.js'

const CHANGE_EVENT = 'workspace-agent-entry-change'

export function useAgentEntryPreferences() {
  const store = useAppStore()
  const mobile = () => window.matchMedia('(max-width: 767px)').matches
  const preferences = ref(parseAgentEntryPreferences(null, mobile()))
  const storageAvailable = ref(true)
  const storage = {
    getItem: key => window.localStorage.getItem(key),
    setItem: (key, value) => window.localStorage.setItem(key, value)
  }
  const reload = () => { preferences.value = readAgentEntryPreferences(storage, store.accountScope, mobile()) }
  watch(() => store.accountScope, reload, { immediate: true, flush: 'sync' })

  function setPreferences(patch) {
    preferences.value = parseAgentEntryPreferences({ ...preferences.value, ...patch }, mobile())
    storageAvailable.value = writeAgentEntryPreferences(storage, store.accountScope, preferences.value)
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT, {
      detail: { scope: store.accountScope, preferences: preferences.value }
    }))
  }
  function handleChange(event) {
    if (event.detail?.scope === store.accountScope) preferences.value = parseAgentEntryPreferences(event.detail.preferences, mobile())
  }
  function handleStorage(event) {
    if (event.key === null || event.key === agentEntryStorageKey(store.accountScope)) reload()
  }
  window.addEventListener(CHANGE_EVENT, handleChange)
  window.addEventListener('storage', handleStorage)
  onBeforeUnmount(() => {
    window.removeEventListener(CHANGE_EVENT, handleChange)
    window.removeEventListener('storage', handleStorage)
  })
  return {
    preferences: computed(() => preferences.value),
    storageAvailable,
    setPreferences
  }
}
