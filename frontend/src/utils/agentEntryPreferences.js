const MODES = ['floating', 'docked', 'hidden']
const SIDES = ['left', 'right']

export function agentEntryStorageKey(accountScope) {
  return `workspace_agent_entry:${encodeURIComponent(accountScope || 'local')}`
}

export function parseAgentEntryPreferences(raw, mobile = false) {
  const defaults = { mode: mobile ? 'docked' : 'floating', side: 'right' }
  try {
    const value = typeof raw === 'string' ? JSON.parse(raw) : raw
    if (!value || typeof value !== 'object' || Array.isArray(value)) return defaults
    return {
      mode: MODES.includes(value.mode) ? value.mode : defaults.mode,
      side: SIDES.includes(value.side) ? value.side : defaults.side
    }
  } catch {
    return defaults
  }
}

export function readAgentEntryPreferences(storage, accountScope, mobile = false) {
  try {
    return parseAgentEntryPreferences(storage.getItem(agentEntryStorageKey(accountScope)), mobile)
  } catch {
    return parseAgentEntryPreferences(null, mobile)
  }
}

export function writeAgentEntryPreferences(storage, accountScope, preferences) {
  try {
    storage.setItem(agentEntryStorageKey(accountScope), JSON.stringify(parseAgentEntryPreferences(preferences)))
    return true
  } catch {
    return false
  }
}
