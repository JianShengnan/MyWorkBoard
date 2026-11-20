<template>
  <section class="agent-entry-settings" aria-label="随身 AI 入口设置">
    <h2>随身 AI 入口</h2>
    <p>仅对当前账号、本设备浏览器生效。关闭对话不会隐藏入口；隐藏入口也不会中断正在生成的回复。</p>
    <label>
      <span>显示方式</span>
      <select :value="preferences.mode" @change="setPreferences({ mode: $event.target.value })">
        <option value="floating">悬浮按钮</option>
        <option value="docked">贴边收起</option>
        <option value="hidden">隐藏入口</option>
      </select>
    </label>
    <label>
      <span>停靠位置</span>
      <select :value="preferences.side" @change="setPreferences({ side: $event.target.value })">
        <option value="left">左侧</option>
        <option value="right">右侧</option>
      </select>
    </label>
    <button v-if="preferences.mode === 'hidden'" type="button" @click="setPreferences({ mode: 'docked' })">恢复随身 AI 入口</button>
    <small v-if="!storageAvailable" role="status">浏览器未允许保存，当前选择仅在本次页面有效。</small>
  </section>
</template>

<script setup>
import { useAgentEntryPreferences } from '../../utils/useAgentEntryPreferences.js'

const { preferences, storageAvailable, setPreferences } = useAgentEntryPreferences()
</script>

<style scoped>
.agent-entry-settings { display:grid; gap:12px; padding:18px; border:1px solid var(--line); border-radius:12px; background:var(--card); color:var(--ink) }
.agent-entry-settings h2 { margin:0; font-size:14px }
.agent-entry-settings p,.agent-entry-settings small { margin:0; color:var(--muted); font-size:12px; line-height:1.6 }
.agent-entry-settings label { display:flex; justify-content:space-between; align-items:center; gap:14px; font-size:13px }
.agent-entry-settings select { min-width:130px; min-height:44px; padding:8px 12px; border:1px solid var(--line); border-radius:8px; background:var(--paper); color:var(--ink); font:inherit }
.agent-entry-settings button { justify-self:start; min-height:44px; padding:8px 14px; border:1px solid var(--line); border-radius:8px; background:var(--accent); color:var(--accent-contrast,#fff); font:inherit; font-size:13px }
.agent-entry-settings :is(button,select):focus-visible { outline:2px solid var(--accent); outline-offset:3px }
</style>
