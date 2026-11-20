import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const habitRow = await readFile(new URL('../components/habits/HabitRow.vue', import.meta.url), 'utf8')
const habitPanel = await readFile(new URL('../components/habits/HabitDetailPanel.vue', import.meta.url), 'utf8')
const habitsView = await readFile(new URL('./TaskHabitsView.vue', import.meta.url), 'utf8')
const workspace = await readFile(new URL('./TaskWorkspaceView.vue', import.meta.url), 'utf8')
const taskRow = await readFile(new URL('../components/tasks/TaskRow.vue', import.meta.url), 'utf8')

test('习惯行有常显的更多按钮，点击不再冒泡成打开详情', () => {
  assert.match(habitRow, /class="hr-more"[\s\S]{0,120}@click\.stop="openMenuFromButton"/)
  assert.match(habitRow, /:aria-label="`更多操作：\$\{habit\.name\}`"/)
  assert.match(habitRow, /function openMenuFromButton\(event\) \{[\s\S]*?getBoundingClientRect\(\)[\s\S]*?emit\('menu'/)
})

test('更多按钮的 36px 命中区不撑高 54px 的习惯行', () => {
  const rule = habitRow.match(/\.hr-more \{[\s\S]*?\n\}/)?.[0]
  assert.ok(rule, '缺少 .hr-more 规则')
  assert.match(rule, /width: 36px/)
  assert.match(rule, /height: 36px/)
  assert.match(rule, /margin: -3px -8px -3px 0/)
})

test('月格选中日期后，当天操作按钮把 dayMenu 锚到选中日期', () => {
  assert.match(habitPanel, /class="hd-day-actions"[\s\S]{0,160}@click="openPickedMenu"/)
  assert.match(habitPanel, /function pickCell\(key\) \{[\s\S]*?pickedKey\.value = key[\s\S]*?emit\('toggle', key\)/)
  assert.match(habitPanel, /function openAnchoredMenu\(event, key\) \{[\s\S]*?state === 'future' \|\| state === 'none'[\s\S]*?emit\('dayMenu', \{/)
  assert.match(habitPanel, /@keydown="onCellKeydown\(\$event, cell\.key\)"/)
  assert.match(habitPanel, /event\.key === 'ContextMenu' \|\| \(event\.shiftKey && event\.key === 'Enter'\)/)
})

test('日期浮层坐标统一收敛到视口内', () => {
  assert.match(habitsView, /clampMenuPoint\(event\.clientX, event\.clientY, rect\.width, rect\.height, window\.innerWidth, window\.innerHeight\)/)
  assert.match(habitsView, /const point = clampMenuPoint\(event\.clientX, event\.clientY, rect\.width, rect\.height, window\.innerWidth, window\.innerHeight\)[\s\S]*?context\.value = \{ \.\.\.context\.value, x, y, flip/)
})

test('详情检查项提供点击式上移下移，并复用同一写回路径', () => {
  assert.match(workspace, /class="clone-checklist-move" :aria-label="`上移检查项\$\{item\.title \|\| index \+ 1\}`" :disabled="index === 0" @click\.stop="moveDetailChecklistItem\(index, -1\)"/)
  assert.match(workspace, /class="clone-checklist-move" :aria-label="`下移检查项\$\{item\.title \|\| index \+ 1\}`" :disabled="index === selectedTask\.checklist\.length - 1" @click\.stop="moveDetailChecklistItem\(index, 1\)"/)
  assert.match(workspace, /function moveDetailChecklistItem\(index, offset\) \{[\s\S]*?moveChecklistItemBy\(task\.checklist, index, offset\)[\s\S]*?applyDetailChecklist\(next\)/)
  assert.match(workspace, /checklist: checklist\.map\(\(item, index\) => \(\{ \.\.\.item, sortOrder: index \}\)\)/)
})

test('拖拽手柄可聚焦并用方向键排序', () => {
  assert.match(workspace, /class="clone-checklist-grip" draggable="true" :aria-label="`拖动排序检查项 \$\{index \+ 1\}[\s\S]{0,80}tabindex="0"[\s\S]{0,80}@keydown\.up\.prevent="moveDetailChecklistItem\(index, -1\)"[\s\S]{0,120}@keydown\.down\.prevent="moveDetailChecklistItem\(index, 1\)"/)
})

test('触摸命中区扩大但视觉尺寸不变', () => {
  const moveRule = workspace.match(/\.clone-checklist-move \{[\s\S]*?\n\}/)?.[0]
  assert.match(moveRule, /width: 32px; height: 32px/)
  assert.match(workspace, /\.clone-checklist-hit \{[\s\S]*?width: 36px; height: 36px;[\s\S]*?margin: -1px 0;/)
  // 列宽必须留够 36px，否则命中区又会外溢到标题上
  assert.match(workspace, /\.clone-checklist-read \.clone-checklist-row \{ grid-template-columns: 16px 36px minmax\(0, 1fr\)/)
  assert.match(workspace, /\.clone-efficiency-check::before \{ position: absolute; inset: -8px; content: ""; \}/)
  assert.match(taskRow, /\.clone-check::after \{ position: absolute; inset: -6px -4px -6px -8px; content: ""; \}/)
})

test('检查项拖拽落点仍只用 box-shadow', () => {
  const rule = workspace.match(/\.clone-checklist-row\.drop-target \{[\s\S]*?\}/)?.[0]
  assert.equal(rule, '.clone-checklist-row.drop-target { box-shadow: inset 0 2px 0 var(--accent); }')
})
