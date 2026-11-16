<template>
  <article
    class="clone-task-row"
    :class="{
      selected,
      checked: task.status === 'COMPLETED',
      'is-note': helpers.isNoteTask(task),
      'has-children': task.hasVisibleChildren,
      multi,
      'print-content': printMode === 'CONTENT',
      dragging,
      'drag-over': dragOver,
      'drop-before': dragOver && dropPlacement === 'before',
      'drop-after': dragOver && dropPlacement === 'after',
    }"
    :data-depth="task.treeDepth"
    :data-priority="task.priority"
    :style="{ '--task-group-color': groupColor, '--task-depth': task.treeDepth }"
    @dragover.prevent.stop="$emit('drag-target', $event)"
    @drop.prevent.stop="$emit('drop')"
    @dragend="$emit('drag-end')"
    @contextmenu.prevent="$emit('context', $event)"
  >
    <button v-if="task.hasVisibleChildren" type="button" class="clone-subtask-toggle" :aria-label="`${subtaskCollapsed ? '展开' : '收起'}${task.title}的子任务`" :aria-expanded="!subtaskCollapsed" @click.stop="$emit('toggle-subtask')"><ChevronDown :size="14" :class="{ rotated: subtaskCollapsed }" /></button>
    <button
      v-if="!helpers.isNoteTask(task)"
      type="button"
      class="clone-check"
      :aria-label="task.status === 'COMPLETED' ? `重新打开${task.title}` : `完成${task.title}`"
      @click="$emit('check', $event)"
    >
      <Check v-if="task.status === 'COMPLETED'" :size="14" /></button
    ><button type="button" class="clone-drag-handle" :draggable="!batchMode" :aria-label="`拖动${task.title}`" @click.stop @dragstart.stop="$emit('drag-start', $event)"><GripVertical :size="15" /></button><button
      type="button"
      class="clone-task-content"
      @click="$emit('content', $event)"
    >
      <template v-if="editingTitle"><input :value="titleDraft" type="text" maxlength="500" :aria-label="`编辑${task.title}标题`" @input="$emit('update:titleDraft', $event.target.value)" @click.stop @keydown.enter.prevent="$emit('save-inline-title')" @keydown.esc.prevent="$emit('cancel-inline-title')" @blur="$emit('save-inline-title')" /></template><template v-else><span class="clone-print-task-copy"><strong>{{ task.title }}</strong><small v-if="task.description" class="clone-print-description">{{ helpers.printDescription(task.description) }}</small></span></template
      ></button
    ><div class="clone-task-metadata">
      <span v-if="showTags && task.tagIds?.length" class="clone-task-tags" :aria-label="helpers.taskTagSummary(task)">
        <span v-for="tagId in task.tagIds" :key="tagId" class="clone-task-tag-wrap" :style="helpers.taskTagStyle(tagId)"><RouterLink :to="`/tasks/tag/${tagId}`" class="clone-task-tag" @click.stop>{{ helpers.tagName(tagId) }}</RouterLink><button type="button" class="clone-task-tag-remove" :aria-label="`移除标签${helpers.tagName(tagId)}`" @click.stop="$emit('remove-tag', tagId)"><X :size="11" /></button></span>
        <span class="clone-task-tag-count" :data-total="task.tagIds.length" :data-h4="Math.max(0, task.tagIds.length - 4)" :data-h2="Math.max(0, task.tagIds.length - 2)" :data-h1="Math.max(0, task.tagIds.length - 1)" :aria-label="helpers.taskTagSummary(task)" tabindex="0" @mouseenter="$emit('tag-flyout-show', $event)" @mouseleave="$emit('tag-flyout-hide')" @focus="$emit('tag-flyout-show', $event)" @blur="$emit('tag-flyout-hide')"></span>
      </span>
      <button v-if="showDescription && task.description" type="button" class="clone-task-indicator clone-task-indicator-action" :title="task.description" :aria-label="`查看${task.title}详细内容`" @click.stop="$emit('open-indicator', 'description')"><FileText :size="14" /></button>
      <button v-if="showChecklist && task.checklist?.length" type="button" class="clone-task-indicator clone-task-indicator-action" :title="`检查项 ${doneItems}/${task.checklist.length}`" :aria-label="`查看${task.title}检查项进度`" @click.stop="$emit('open-indicator', 'checklist')"><span class="clone-checklist-progress" :style="{ '--check-progress': `${doneItems / task.checklist.length * 100}%` }"></span><small>{{ doneItems }}/{{ task.checklist.length }}</small></button>
      <button v-if="task.totalSubtasks" type="button" class="clone-task-indicator clone-task-indicator-action" :title="`子任务 ${task.completedSubtasks || 0}/${task.totalSubtasks}`" :aria-label="`查看${task.title}子任务`" @click.stop="$emit('open-indicator', 'subtasks')"><ListTodo :size="14" /><small>{{ task.completedSubtasks || 0 }}/{{ task.totalSubtasks }}</small></button>
      <button v-if="task.rrule" type="button" class="clone-task-indicator clone-task-indicator-action" :title="helpers.recurrenceLabel(task.rrule)" :aria-label="`调整${task.title}重复规则`" @click.stop="$emit('open-indicator', 'repeat')"><Repeat2 :size="14" /></button>
      <button v-if="task.focusMinutes" type="button" class="clone-task-indicator clone-task-indicator-action" :title="`累计专注 ${task.focusMinutes} 分钟`" :aria-label="`查看${task.title}专注记录`" @click.stop="$emit('open-indicator', 'focus')"><Timer :size="14" /></button>
      <button v-if="task.attachmentCount || task.attachments?.length" type="button" class="clone-task-indicator clone-task-indicator-action" :title="`附件 ${task.attachmentCount || task.attachments.length} 个`" :aria-label="`查看${task.title}附件`" @click.stop="$emit('open-indicator', 'attachments')"><Paperclip :size="14" /><small>{{ task.attachmentCount || task.attachments.length }}</small></button>
      <span v-if="completionRoute && helpers.taskRangeLabel(task)" class="clone-task-date is-range" :title="`时间段 ${helpers.formatDate(task.startAt)} 至 ${helpers.formatDate(task.dueAt)}`"><CalendarRange :size="14" />{{ helpers.taskRangeLabel(task) }}</span><span v-if="completionRoute && task.completedAt" class="clone-task-date is-completed" :title="`完成于 ${helpers.formatDate(task.completedAt)}`"><CalendarCheck :size="14" />{{ helpers.completedDateLabel(task) }}</span><button v-else-if="task.dueAt" type="button" class="clone-task-date" :class="{ overdue: helpers.isTaskOverdue(task), today: helpers.isTaskDueToday(task) }" data-row-date-trigger :title="`调整日期 ${helpers.formatDate(task.dueAt)}`" @click.stop="$emit('open-date', $event.currentTarget)"><CalendarClock :size="14" />{{ helpers.taskDateLabel(task) }}</button>
      <button v-if="showListLink" type="button" class="clone-task-list-link" :title="helpers.listName(task.listId)" @click.stop="$emit('navigate-list')"><span class="clone-task-list-dot" :style="{ backgroundColor: helpers.taskListColor(task) }"></span>{{ helpers.listName(task.listId) }}</button>
    </div><button
      v-if="!multi"
      type="button"
      class="clone-row-more"
      :aria-label="`更多操作：${task.title}`"
      @click.stop="$emit('context', $event)"
    >
      <Ellipsis :size="17" />
    </button>
  </article>
</template>

<script setup>
/**
 * 任务列表的一行。
 *
 * 单独成组件不是为了复用，而是为了让 Vue 的更新粒度落在「一行」上：列表能到 700 多行，
 * 之前整个工作区是一个组件，详情面板里勾个检查项就要把 700 多行的 vnode 全部重造、
 * 重新 patch 一遍。拆开之后父组件渲染时不再读每行的 title/status/日期，行内元素
 * 的依赖收集在各自的渲染 effect 里，改一条就只重渲一条。
 *
 * 注意 props 都传「已经算好的标量」而不是让子组件去 store 里查：这样父组件在渲染
 * 700 行时不会顺带把每行的响应式字段都读一遍。
 */
import { computed, inject } from 'vue'
import {
  CalendarCheck, CalendarClock, CalendarRange, Check, ChevronDown, Ellipsis,
  FileText, GripVertical, ListTodo, Paperclip, Repeat2, Timer, X,
} from 'lucide-vue-next'

const props = defineProps({
  task: { type: Object, required: true },
  groupColor: { type: String, default: '' },
  selected: Boolean,
  multi: Boolean,
  dragging: Boolean,
  dragOver: Boolean,
  dropPlacement: { type: String, default: '' },
  subtaskCollapsed: Boolean,
  editingTitle: Boolean,
  titleDraft: { type: String, default: '' },
  printMode: { type: String, default: 'TITLE' },
  batchMode: Boolean,
  showTags: Boolean,
  showDescription: Boolean,
  showChecklist: Boolean,
  showListLink: Boolean,
  completionRoute: Boolean,
})

defineEmits([
  'check', 'content', 'context', 'toggle-subtask', 'drag-start', 'drag-target',
  'drop', 'drag-end', 'remove-tag', 'tag-flyout-show', 'tag-flyout-hide',
  'open-indicator', 'open-date', 'navigate-list', 'save-inline-title',
  'cancel-inline-title', 'update:titleDraft',
])

// 纯格式化函数由父组件注入。它们内部读的是 store，在子组件渲染过程中调用，
// 依赖会收集到子组件自己身上，不牵连父组件。
const helpers = inject('taskRowHelpers')

const doneItems = computed(() => (props.task.checklist || []).filter((item) => item.completed).length)
</script>

<style scoped>
/* 从 TaskWorkspaceView 里原样搬过来的行内样式。父组件那份是 scoped 的，
   作用不到本组件内部元素（内部元素只带本组件的 data-v 标记），所以这里要有一份。 */

.clone-sidebar-head button,
.clone-icon-button,
.clone-row-more {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.clone-sidebar-head button:hover,
.clone-icon-button:hover,
.clone-row-more:hover {
  background: var(--paper);
  color: var(--ink);
}
.clone-task-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 48px;
  /* 行宽即中间栏可用宽度：标签分档收起的容器查询以此为准（对齐滴答按中栏收窄） */
  container: task-meta / inline-size;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 74%, transparent);
  padding: 0 10px 0 13px;
  border-left: 3px solid var(--task-group-color, transparent);
  transition: background 0.12s ease, box-shadow 0.12s ease, opacity 0.12s ease, transform 0.12s ease;
  /* 万条任务时列表会渲染数千行，离屏行不再参与样式计算与布局；
     auto 让浏览器记住每行实测高度，滚动回来不会跳。 */
  content-visibility: auto;
  contain-intrinsic-size: auto 48px;
}
.clone-task-row[data-depth]:not([data-depth="0"]) { width: calc(100% - 40px * var(--task-depth)); margin-left: calc(40px * var(--task-depth)); }
.clone-subtask-toggle { position: absolute; z-index: 2; top: 50%; left: -3px; display: grid; width: 14px; height: 18px; place-items: center; padding: 0; transform: translateY(-50%); border: 0; background: transparent; color: var(--muted); cursor: pointer; }
.clone-task-row.has-children.is-note .clone-drag-handle { margin-left: 7px; }
.clone-subtask-toggle:hover { color: var(--ink); }
.clone-subtask-toggle svg { transition: transform 0.16s ease; }
.clone-subtask-toggle svg.rotated { transform: rotate(-90deg); }
.clone-task-row.dragging { opacity: .42; }
.clone-task-row.drag-over { box-shadow: inset 0 3px 0 var(--accent), inset 3px 0 0 var(--task-group-color, var(--accent)); }
.clone-task-row.drop-before::after,
.clone-task-row.drop-after::after { position: absolute; z-index: 4; left: 7px; width: 7px; height: 7px; border: 1px solid var(--card); border-radius: 50%; background: var(--accent); content: ""; pointer-events: none; }
.clone-task-row.drop-before::before,
.clone-task-row.drop-after::before { position: absolute; z-index: 3; left: 10px; right: 7px; height: 2px; background: var(--accent); content: ""; pointer-events: none; }
.clone-task-row.drop-before::before { top: -1px; }
.clone-task-row.drop-after::before { bottom: -1px; }
.clone-task-row.drop-before::after { top: -4px; }
.clone-task-row.drop-after::after { bottom: -4px; }
.clone-drag-handle { display: grid; flex: none; width: 17px; height: 24px; place-items: center; margin-left: -9px; padding: 0; border: 0; background: transparent; color: var(--muted); opacity: 0; cursor: grab; }
.clone-task-row:hover .clone-drag-handle,
.clone-drag-handle:focus-visible { opacity: .8; }
.clone-drag-handle:active { cursor: grabbing; }
.clone-task-row:hover,
.clone-task-row.selected {
  background: color-mix(in srgb, var(--ink) 5%, transparent);
  box-shadow: none;
}
.clone-task-row.multi {
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}
.clone-task-row.checked .clone-task-content strong {
  color: var(--muted);
  text-decoration: line-through;
}
.clone-drag-handle {
  flex: none;
  color: color-mix(in srgb, var(--muted) 65%, transparent);
}
.clone-check {
  position: relative;
  display: grid;
  flex: none;
  width: 26px;
  height: 28px;
  min-width: 26px;
  min-height: 28px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
}
.clone-check::before {
  width: 14px;
  height: 14px;
  border: 1.5px solid var(--line2);
  border-radius: 4px;
  background: transparent;
  content: "";
}
.clone-check svg { position: absolute; width: 10px; height: 10px; }
.clone-task-content {
  display: flex;
  align-items: center;
  min-width: 0;
  flex: 1;
  gap: 5px;
  border: 0;
  background: transparent;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}
.clone-print-task-copy { display: grid; min-width: 0; gap: 3px; }
.clone-print-description { display: none; overflow: hidden; color: var(--muted); font-size: 11px; font-weight: 400; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.clone-task-content strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 450;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.clone-task-metadata { display: flex; align-items: center; justify-content: flex-end; gap: 7px; flex: 0 1 auto; min-width: 0; overflow: hidden; color: var(--muted); font-size: 12px; white-space: nowrap; }
.clone-task-tags { position: relative; display: flex; align-items: center; gap: 5px; min-width: 0; overflow: visible; }
.clone-task-tag-wrap { position: relative; display: inline-flex; align-items: center; flex: none; min-width: 0; }
.clone-task-tag { display: inline-flex; align-items: center; max-width: 92px; overflow: hidden; padding: 2px 8px; border-radius: 12px; background: color-mix(in srgb, var(--task-tag-color, var(--accent)) 22%, var(--card)); color: color-mix(in srgb, var(--task-tag-color, var(--accent)) 70%, var(--ink)); text-overflow: ellipsis; }
.clone-task-tag-remove { position: absolute; z-index: 2; top: -4px; right: -4px; display: grid; width: 14px; height: 14px; place-items: center; padding: 0; border: 1px solid var(--card); border-radius: 50%; background: var(--ink2); color: var(--card); opacity: 0; cursor: pointer; }
.clone-task-tag-wrap:hover .clone-task-tag-remove,
.clone-task-tag-remove:focus-visible { opacity: 1; }
.clone-task-tag-count { position: relative; display: none; align-items: center; flex: none; padding: 2px 7px; border-radius: 12px; background: color-mix(in srgb, var(--accent) 18%, var(--card)); color: var(--ink2); cursor: default; }
/* 标签溢出计数 chip 由容器查询分档驱动：宽档最多 4 个标签，超出才显示 +N；数字随档位取对应 data-h* 属性 */
/* 档位判定与隐藏只作用于标签行的直接子元素——+N 悬浮浮层里的同名 chips 不参与分档，避免被隐藏 */
.clone-task-tags:has(> .clone-task-tag-wrap:nth-child(5)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
.clone-task-tags:has(> .clone-task-tag-wrap:nth-child(5)) .clone-task-tag-count::before { content: "+" attr(data-h4); font-size: 11px; }
/* metadata 默认 overflow hidden 防行溢出，悬浮标签时临时放开，避免行尾标签的移除按钮被裁剪 */
.clone-task-metadata:has(.clone-task-tag-wrap:hover),
.clone-task-metadata:has(.clone-task-tag-remove:focus-visible) { overflow: visible; }
.clone-checklist-progress { width: 14px; height: 14px; flex: none; border-radius: 50%; background: conic-gradient(var(--accent) var(--check-progress), color-mix(in srgb, var(--line2) 60%, transparent) 0); }
.clone-task-indicator { display: inline-flex; align-items: center; gap: 2px; flex: none; color: var(--muted); }
.clone-task-indicator small { font-size: 10px; }
.clone-task-date { display: inline-flex; align-items: center; gap: 4px; flex: none; color: var(--ink2); }
.clone-task-date { padding: 0; border: 0; background: transparent; font: inherit; cursor: pointer; }
.clone-task-date.overdue { color: var(--down); }
.clone-task-date.is-completed { color: var(--muted); cursor: default; }
.clone-task-date.is-range { color: var(--muted); cursor: default; }
.clone-task-date.today { color: var(--ink2); }
.clone-task-row.is-note { min-height: 54px; }
.clone-task-row.is-note .clone-task-content { min-height: 44px; }
.clone-task-content input { min-width: 0; width: 100%; padding: 4px 6px; border: 0; border-radius: 4px; outline: 0; box-shadow: none; background: transparent; color: var(--ink); font: inherit; }

.clone-task-list-link { display: inline-flex; align-items: center; gap: 5px; min-width: 0; max-width: 120px; overflow: hidden; padding: 0; border: 0; background: transparent; color: var(--ink2); font: inherit; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.clone-task-list-link:hover { color: var(--accent); }
.clone-task-list-dot { width: 7px; height: 7px; flex: none; border-radius: 50%; }
.clone-task-row em {
  font-style: normal;
  font-size: 11px;
}
.clone-task-row em[data-priority="HIGH"] {
  color: var(--down);
}
.clone-task-row em[data-priority="MEDIUM"] {
  color: var(--warn);
}
.clone-task-row[data-priority="HIGH"] .clone-check::before { border-color: var(--priority-high); }
.clone-task-row[data-priority="MEDIUM"] .clone-check::before { border-color: var(--priority-medium); }
.clone-task-row[data-priority="LOW"] .clone-check::before { border-color: var(--priority-low); }
.clone-row-more {
  flex: none;
}
@media (prefers-reduced-motion: reduce) {
  .clone-task-shell,
  .clone-task-sidebar,
  .clone-task-sidebar > *,
  .clone-collapse-panel,
  .clone-sidebar-section > header svg,
  .clone-group-heading svg,
  .clone-sort-move,
  .clone-sort-enter-active,
  .clone-sort-leave-active,
  .clone-task-row {
    transition-duration: 0.01ms !important;
    transition-delay: 0s !important;
  }}
@media (max-width: 1100px) {
  .clone-task-metadata { max-width: 46%; gap: 5px; font-size: 11px; }
  .clone-task-list-link { max-width: 82px; }}
@container task-meta (max-width: 860px) {
  .clone-task-tags > .clone-task-tag-wrap:nth-child(n + 3) { display: none; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(3)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(3)) .clone-task-tag-count::before { content: "+" attr(data-h2); font-size: 11px; }}
@container task-meta (max-width: 720px) {
  .clone-task-tags > .clone-task-tag-wrap:nth-child(n + 2) { display: none; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count::before { content: "+" attr(data-h1); font-size: 11px; }}
@container task-meta (max-width: 640px) {
  .clone-task-tags > .clone-task-tag-wrap { display: none !important; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count::before { content: attr(data-total); font-size: 11px; }
  .clone-task-tags:not(:has(> .clone-task-tag-wrap:nth-child(2))) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:not(:has(> .clone-task-tag-wrap:nth-child(2))) .clone-task-tag-count::before { content: attr(data-total); font-size: 11px; }}
@media (max-width: 760px) {
  .clone-check { width: 30px; height: 32px; min-width: 30px; min-height: 32px; }
  .clone-check::before { width: 12px; height: 12px; }
  .clone-check svg { width: 9px; height: 9px; }
  .clone-task-metadata { max-width: 38%; }
  .clone-task-metadata > .clone-task-indicator:nth-of-type(n + 2),
  .clone-task-metadata > .clone-task-list-link { display: none; }
  .clone-task-tag { max-width: 68px; padding: 2px 6px; }}
@media print {
  .clone-task-sidebar, .clone-detail-pane, .clone-header-actions, .clone-quick-add, .clone-quick-attachments, .clone-quick-attachment-state, .clone-row-more, .clone-drag-handle, .clone-check, .clone-select-box, .clone-task-list-link { display: none !important; }
  .clone-task-metadata, .clone-task-row em { display: none !important; }
  .clone-task-row.print-content .clone-print-description { display: block; overflow: visible; white-space: normal; }
  .clone-task-row, .clone-task-group { break-inside: avoid; }}</style>

<style scoped>
/* 从 TaskWorkspaceView 里原样搬过来的行内样式。父组件那份是 scoped 的，
   作用不到本组件内部元素（内部元素只带本组件的 data-v 标记），所以这里要有一份。 */

.clone-sidebar-head button,
.clone-icon-button,
.clone-row-more {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.clone-sidebar-head button:hover,
.clone-icon-button:hover,
.clone-row-more:hover {
  background: var(--paper);
  color: var(--ink);
}
.clone-task-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 48px;
  /* 行宽即中间栏可用宽度：标签分档收起的容器查询以此为准（对齐滴答按中栏收窄） */
  container: task-meta / inline-size;
  border-bottom: 1px solid color-mix(in srgb, var(--line) 74%, transparent);
  padding: 0 10px 0 13px;
  border-left: 3px solid var(--task-group-color, transparent);
  transition: background 0.12s ease, box-shadow 0.12s ease, opacity 0.12s ease, transform 0.12s ease;
  /* 万条任务时列表会渲染数千行，离屏行不再参与样式计算与布局；
     auto 让浏览器记住每行实测高度，滚动回来不会跳。 */
  content-visibility: auto;
  contain-intrinsic-size: auto 48px;
}
.clone-task-row[data-depth]:not([data-depth="0"]) { width: calc(100% - 40px * var(--task-depth)); margin-left: calc(40px * var(--task-depth)); }
.clone-subtask-toggle { position: absolute; z-index: 2; top: 50%; left: -3px; display: grid; width: 14px; height: 18px; place-items: center; padding: 0; transform: translateY(-50%); border: 0; background: transparent; color: var(--muted); cursor: pointer; }
.clone-task-row.has-children.is-note .clone-drag-handle { margin-left: 7px; }
.clone-subtask-toggle:hover { color: var(--ink); }
.clone-subtask-toggle svg { transition: transform 0.16s ease; }
.clone-subtask-toggle svg.rotated { transform: rotate(-90deg); }
.clone-task-row.dragging { opacity: .42; }
.clone-task-row.drag-over { box-shadow: inset 0 3px 0 var(--accent), inset 3px 0 0 var(--task-group-color, var(--accent)); }
.clone-task-row.drop-before::after,
.clone-task-row.drop-after::after { position: absolute; z-index: 4; left: 7px; width: 7px; height: 7px; border: 1px solid var(--card); border-radius: 50%; background: var(--accent); content: ""; pointer-events: none; }
.clone-task-row.drop-before::before,
.clone-task-row.drop-after::before { position: absolute; z-index: 3; left: 10px; right: 7px; height: 2px; background: var(--accent); content: ""; pointer-events: none; }
.clone-task-row.drop-before::before { top: -1px; }
.clone-task-row.drop-after::before { bottom: -1px; }
.clone-task-row.drop-before::after { top: -4px; }
.clone-task-row.drop-after::after { bottom: -4px; }
.clone-drag-handle { display: grid; flex: none; width: 17px; height: 24px; place-items: center; margin-left: -9px; padding: 0; border: 0; background: transparent; color: var(--muted); opacity: 0; cursor: grab; }
.clone-task-row:hover .clone-drag-handle,
.clone-drag-handle:focus-visible { opacity: .8; }
.clone-drag-handle:active { cursor: grabbing; }
.clone-task-row:hover,
.clone-task-row.selected {
  background: color-mix(in srgb, var(--ink) 5%, transparent);
  box-shadow: none;
}
.clone-task-row.multi {
  background: color-mix(in srgb, var(--accent) 10%, transparent);
}
.clone-task-row.checked .clone-task-content strong {
  color: var(--muted);
  text-decoration: line-through;
}
.clone-drag-handle {
  flex: none;
  color: color-mix(in srgb, var(--muted) 65%, transparent);
}
.clone-check {
  position: relative;
  display: grid;
  flex: none;
  width: 26px;
  height: 28px;
  min-width: 26px;
  min-height: 28px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--accent);
  cursor: pointer;
}
.clone-check::before {
  width: 14px;
  height: 14px;
  border: 1.5px solid var(--line2);
  border-radius: 4px;
  background: transparent;
  content: "";
}
.clone-check svg { position: absolute; width: 10px; height: 10px; }
.clone-task-content {
  display: flex;
  align-items: center;
  min-width: 0;
  flex: 1;
  gap: 5px;
  border: 0;
  background: transparent;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}
.clone-print-task-copy { display: grid; min-width: 0; gap: 3px; }
.clone-print-description { display: none; overflow: hidden; color: var(--muted); font-size: 11px; font-weight: 400; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.clone-task-content strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 450;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.clone-task-metadata { display: flex; align-items: center; justify-content: flex-end; gap: 7px; flex: 0 1 auto; min-width: 0; overflow: hidden; color: var(--muted); font-size: 12px; white-space: nowrap; }
.clone-task-tags { position: relative; display: flex; align-items: center; gap: 5px; min-width: 0; overflow: visible; }
.clone-task-tag-wrap { position: relative; display: inline-flex; align-items: center; flex: none; min-width: 0; }
.clone-task-tag { display: inline-flex; align-items: center; max-width: 92px; overflow: hidden; padding: 2px 8px; border-radius: 12px; background: color-mix(in srgb, var(--task-tag-color, var(--accent)) 22%, var(--card)); color: color-mix(in srgb, var(--task-tag-color, var(--accent)) 70%, var(--ink)); text-overflow: ellipsis; }
.clone-task-tag-remove { position: absolute; z-index: 2; top: -4px; right: -4px; display: grid; width: 14px; height: 14px; place-items: center; padding: 0; border: 1px solid var(--card); border-radius: 50%; background: var(--ink2); color: var(--card); opacity: 0; cursor: pointer; }
.clone-task-tag-wrap:hover .clone-task-tag-remove,
.clone-task-tag-remove:focus-visible { opacity: 1; }
.clone-task-tag-count { position: relative; display: none; align-items: center; flex: none; padding: 2px 7px; border-radius: 12px; background: color-mix(in srgb, var(--accent) 18%, var(--card)); color: var(--ink2); cursor: default; }
/* 标签溢出计数 chip 由容器查询分档驱动：宽档最多 4 个标签，超出才显示 +N；数字随档位取对应 data-h* 属性 */
/* 档位判定与隐藏只作用于标签行的直接子元素——+N 悬浮浮层里的同名 chips 不参与分档，避免被隐藏 */
.clone-task-tags:has(> .clone-task-tag-wrap:nth-child(5)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
.clone-task-tags:has(> .clone-task-tag-wrap:nth-child(5)) .clone-task-tag-count::before { content: "+" attr(data-h4); font-size: 11px; }
/* metadata 默认 overflow hidden 防行溢出，悬浮标签时临时放开，避免行尾标签的移除按钮被裁剪 */
.clone-task-metadata:has(.clone-task-tag-wrap:hover),
.clone-task-metadata:has(.clone-task-tag-remove:focus-visible) { overflow: visible; }
.clone-checklist-progress { width: 14px; height: 14px; flex: none; border-radius: 50%; background: conic-gradient(var(--accent) var(--check-progress), color-mix(in srgb, var(--line2) 60%, transparent) 0); }
.clone-task-indicator { display: inline-flex; align-items: center; gap: 2px; flex: none; color: var(--muted); }
.clone-task-indicator small { font-size: 10px; }
.clone-task-date { display: inline-flex; align-items: center; gap: 4px; flex: none; color: var(--ink2); }
.clone-task-date { padding: 0; border: 0; background: transparent; font: inherit; cursor: pointer; }
.clone-task-date.overdue { color: var(--down); }
.clone-task-date.is-completed { color: var(--muted); cursor: default; }
.clone-task-date.is-range { color: var(--muted); cursor: default; }
.clone-task-date.today { color: var(--ink2); }
.clone-task-row.is-note { min-height: 54px; }
.clone-task-row.is-note .clone-task-content { min-height: 44px; }
.clone-task-content input { min-width: 0; width: 100%; padding: 4px 6px; border: 0; border-radius: 4px; outline: 0; box-shadow: none; background: transparent; color: var(--ink); font: inherit; }

.clone-task-list-link { display: inline-flex; align-items: center; gap: 5px; min-width: 0; max-width: 120px; overflow: hidden; padding: 0; border: 0; background: transparent; color: var(--ink2); font: inherit; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.clone-task-list-link:hover { color: var(--accent); }
.clone-task-list-dot { width: 7px; height: 7px; flex: none; border-radius: 50%; }
.clone-task-row em {
  font-style: normal;
  font-size: 11px;
}
.clone-task-row em[data-priority="HIGH"] {
  color: var(--down);
}
.clone-task-row em[data-priority="MEDIUM"] {
  color: var(--warn);
}
.clone-task-row[data-priority="HIGH"] .clone-check::before { border-color: var(--priority-high); }
.clone-task-row[data-priority="MEDIUM"] .clone-check::before { border-color: var(--priority-medium); }
.clone-task-row[data-priority="LOW"] .clone-check::before { border-color: var(--priority-low); }
.clone-row-more {
  flex: none;
}
@media (prefers-reduced-motion: reduce) {
  .clone-task-shell,
  .clone-task-sidebar,
  .clone-task-sidebar > *,
  .clone-collapse-panel,
  .clone-sidebar-section > header svg,
  .clone-group-heading svg,
  .clone-sort-move,
  .clone-sort-enter-active,
  .clone-sort-leave-active,
  .clone-task-row {
    transition-duration: 0.01ms !important;
    transition-delay: 0s !important;
  }}
@media (max-width: 1100px) {
  .clone-task-metadata { max-width: 46%; gap: 5px; font-size: 11px; }
  .clone-task-list-link { max-width: 82px; }}
@container task-meta (max-width: 860px) {
  .clone-task-tags > .clone-task-tag-wrap:nth-child(n + 3) { display: none; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(3)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(3)) .clone-task-tag-count::before { content: "+" attr(data-h2); font-size: 11px; }}
@container task-meta (max-width: 720px) {
  .clone-task-tags > .clone-task-tag-wrap:nth-child(n + 2) { display: none; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count::before { content: "+" attr(data-h1); font-size: 11px; }}
@container task-meta (max-width: 640px) {
  .clone-task-tags > .clone-task-tag-wrap { display: none !important; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:has(> .clone-task-tag-wrap:nth-child(2)) .clone-task-tag-count::before { content: attr(data-total); font-size: 11px; }
  .clone-task-tags:not(:has(> .clone-task-tag-wrap:nth-child(2))) .clone-task-tag-count { display: inline-flex; font-size: 0; }
  .clone-task-tags:not(:has(> .clone-task-tag-wrap:nth-child(2))) .clone-task-tag-count::before { content: attr(data-total); font-size: 11px; }}
@media (max-width: 760px) {
  .clone-check { width: 30px; height: 32px; min-width: 30px; min-height: 32px; }
  .clone-check::before { width: 12px; height: 12px; }
  .clone-check svg { width: 9px; height: 9px; }
  .clone-task-metadata { max-width: 38%; }
  .clone-task-metadata > .clone-task-indicator:nth-of-type(n + 2),
  .clone-task-metadata > .clone-task-list-link { display: none; }
  .clone-task-tag { max-width: 68px; padding: 2px 6px; }}
@media print {
  .clone-task-sidebar, .clone-detail-pane, .clone-header-actions, .clone-quick-add, .clone-quick-attachments, .clone-quick-attachment-state, .clone-row-more, .clone-drag-handle, .clone-check, .clone-select-box, .clone-task-list-link { display: none !important; }
  .clone-task-metadata, .clone-task-row em { display: none !important; }
  .clone-task-row.print-content .clone-print-description { display: block; overflow: visible; white-space: normal; }
  .clone-task-row, .clone-task-group { break-inside: avoid; }}</style>
