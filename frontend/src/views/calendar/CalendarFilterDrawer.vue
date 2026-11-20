<template>
  <Teleport to="body">
    <div v-if="open" class="cal-filter-layer">
      <div class="cal-filter-backdrop" @click="$emit('close', 'backdrop')"></div>
      <section
        class="cal-filter-drawer"
        role="dialog"
        aria-modal="true"
        aria-label="日历筛选"
        @click.stop
      >
        <header class="cal-filter-head">
          <div>
            <b>筛选</b>
            <small>{{ count ? `${count} 项条件生效中` : '当前显示全部日程' }}</small>
          </div>
          <button type="button" class="cal-filter-close" aria-label="关闭筛选" @click="$emit('close', 'close')"><X :size="16" /></button>
        </header>

        <div class="cal-filter-body">
          <CalendarSidebar
            variant="drawer"
            :week-start="weekStart"
            :anchor="anchor"
            :today="today"
            :selected="selected"
            :lunar="lunar"
            :holidays="holidays"
            :lists="lists"
            :tags="tags"
            :filters="filters"
            :scope="scope"
            @move-month="$emit('move-month', $event)"
            @go-today="$emit('go-today')"
            @select="$emit('select', $event)"
            @update:scope="$emit('update:scope', $event)"
          />
        </div>

        <footer class="cal-filter-foot">
          <button type="button" class="cal-filter-clear" :disabled="!count" @click="$emit('clear')">清空筛选</button>
          <button type="button" class="cal-filter-done" @click="$emit('close', 'done')">完成</button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'
import { X } from 'lucide-vue-next'
import CalendarSidebar from './CalendarSidebar.vue'
import { countActiveFilters } from './calendarMobile.js'

const props = defineProps({
  open: { type: Boolean, default: false },
  weekStart: { type: Number, default: 1 },
  anchor: { type: Date, required: true },
  today: { type: Date, required: true },
  selected: { type: Date, default: null },
  lunar: { type: Object, default: () => ({}) },
  holidays: { type: Object, default: () => ({}) },
  lists: { type: Array, default: () => [] },
  tags: { type: Array, default: () => [] },
  filters: { type: Array, default: () => [] },
  scope: { type: Object, required: true }
})

defineEmits(['close', 'clear', 'move-month', 'go-today', 'select', 'update:scope'])

const count = computed(() => countActiveFilters(props.scope))
</script>

<style scoped>
.cal-filter-layer {
  position: fixed;
  z-index: 1200;
  inset: 0;
}
.cal-filter-backdrop {
  position: absolute;
  inset: 0;
  background: rgb(15 23 42 / 42%);
  animation: cal-filter-fade .16s ease-out;
}
/* 从左侧滑出：筛选条目是纵向长列表，左侧抽屉的滚动距离比底部抽屉短，拇指也够得着「完成」 */
.cal-filter-drawer {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  display: flex;
  width: min(86vw, 340px);
  max-width: 100vw;
  flex-direction: column;
  border-right: 1px solid var(--line);
  background: var(--card);
  box-shadow: 12px 0 36px rgb(15 23 42 / 22%);
  color: var(--ink);
  animation: cal-filter-slide .2s ease-out;
}
.cal-filter-head {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 12px 10px 16px;
  border-bottom: 1px solid var(--line);
}
.cal-filter-head b {
  display: block;
  font-size: 15px;
  font-weight: 650;
}
.cal-filter-head small {
  display: block;
  margin-top: 2px;
  color: var(--muted);
  font-size: 11px;
}
.cal-filter-close {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink2);
}
.cal-filter-close:hover {
  background: var(--paper);
  color: var(--ink);
}
.cal-filter-body {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}
.cal-filter-foot {
  display: flex;
  flex: 0 0 auto;
  gap: 8px;
  padding: 10px 12px calc(10px + env(safe-area-inset-bottom));
  border-top: 1px solid var(--line);
}
.cal-filter-clear,
.cal-filter-done {
  min-height: 38px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
}
.cal-filter-clear {
  flex: 0 0 auto;
  padding: 0 14px;
  border: 1px solid var(--line2);
  background: transparent;
  color: var(--ink2);
}
.cal-filter-clear:disabled {
  opacity: .45;
}
.cal-filter-done {
  flex: 1 1 auto;
  border: 1px solid var(--accent);
  background: var(--accent);
  color: var(--accent-contrast, #fff);
}
@keyframes cal-filter-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes cal-filter-slide {
  from { transform: translateX(-100%); }
  to { transform: translateX(0); }
}
@media (prefers-reduced-motion: reduce) {
  .cal-filter-backdrop,
  .cal-filter-drawer {
    animation: none;
  }
}
</style>
