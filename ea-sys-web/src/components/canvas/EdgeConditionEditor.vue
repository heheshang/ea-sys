<script setup lang="ts">
/**
 * 边条件 DSL 编辑器（递归）：AND/OR 分组树。
 * 模型对齐引擎 ConditionCompiler：
 *  - 分组 { op: 'AND'|'OR', items: [...] }，items 可含条件或嵌套分组
 *  - 条件 { field, op, value? }；field 必须带前缀 event./contact./history.
 *  - op 白名单含符号比较（> >= < <=）+ 语义操作符（与规则引擎 OPS 一致）
 */
import { computed } from 'vue'
import type { ConditionItem, ConditionOp, ConditionRule } from '../../api/types'

const props = defineProps<{
  modelValue: ConditionRule
  /** 是否根节点（根节点隐藏删除） */
  root?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: ConditionRule]
}>()

/* 常用字段快捷选项（contact 列齐后端 ContactResponse；event/history 为自由 key） */
const FIELD_OPTIONS: Array<{ group: string; value: string }> = [
  ...(['id', 'external_id', 'phone', 'email', 'push_token', 'status'] as const).map((c) => ({
    group: '联系人 contact.*',
    value: `contact.${c}`,
  })),
  { group: '联系人 contact.*', value: 'contact.layer' },
  { group: '联系人 contact.*', value: 'contact.churn_risk' },
  { group: '联系人 contact.*', value: 'contact.tags' },
  { group: '事件 event.*', value: 'event.amount' },
  { group: '事件 event.*', value: 'event.channel' },
  { group: '事件 event.*', value: 'event.type' },
  { group: '历史 history.*', value: 'history.lastTouchMinutesAgo' },
  { group: '历史 history.*', value: 'history.touchCount' },
]

const ALL_OPS: ConditionOp[] = [
  '>', '>=', '<', '<=',
  'equals', 'not_equals', 'in', 'not_in', 'contains',
  'exists', 'not_exists',
  'percentage',
]

function scopeOf(field: string): 'event' | 'contact' | 'history' {
  if (field.startsWith('event.')) return 'event'
  if (field.startsWith('history.')) return 'history'
  return 'contact'
}

function opsFor(field: string): ConditionOp[] {
  // contact 列无数值比较；percentage 仅 contact.id 稳定哈希分流，event/history 不提供
  const scope = scopeOf(field)
  if (scope === 'contact') {
    const key = field.slice('contact.'.length)
    if (key === 'layer' || key === 'churn_risk') return ALL_OPS
    return ALL_OPS.filter((op) => !['>', '>=', '<', '<='].includes(op))
  }
  return ALL_OPS.filter((op) => op !== 'percentage')
}

function isGroup(item: ConditionItem | ConditionRule): item is ConditionRule {
  return 'items' in item && Array.isArray(item.items)
}
function isExistsOp(op: ConditionOp): boolean {
  return op === 'exists' || op === 'not_exists'
}
function isInOp(op: ConditionOp): boolean {
  return op === 'in' || op === 'not_in'
}
function isNumericOp(op: ConditionOp): boolean {
  return ['>', '>=', '<', '<='].includes(op)
}
function isPctOp(op: ConditionOp): boolean {
  return op === 'percentage'
}

function emitChange() {
  emit('update:modelValue', props.modelValue)
}

function addCondition() {
  props.modelValue.items.push({ field: 'event.channel', op: 'equals', value: '' })
  emitChange()
}

function addGroup() {
  props.modelValue.items.push({ op: 'AND', items: [{ field: 'event.channel', op: 'equals', value: '' }] })
  emitChange()
}

function removeItem(index: number) {
  props.modelValue.items.splice(index, 1)
  emitChange()
}

/** 嵌套子分组整体替换（递归组件 emit 新值）。 */
function replaceGroup(index: number, value: ConditionRule) {
  props.modelValue.items.splice(index, 1, value)
  emitChange()
}

function toggleOp() {
  props.modelValue.op = props.modelValue.op === 'AND' ? 'OR' : 'AND'
  emitChange()
}

/* 条件行更新（模板内联箭头会丢失类型，统一抽具名函数） */
function setField(item: ConditionItem, value: string) {
  item.field = value
  const ops = opsFor(value)
  if (!ops.includes(item.op)) item.op = ops[0]
  emitChange()
}

function setOp(item: ConditionItem, value: ConditionOp) {
  item.op = value
  if (isExistsOp(value)) item.value = undefined
  else if (isPctOp(value)) item.value = typeof item.value === 'number' ? item.value : 50
  else if (!isInOp(value) && Array.isArray(item.value)) item.value = ''
  else if (item.value === undefined) item.value = ''
  emitChange()
}

function setValue(item: ConditionItem, value: string | number | boolean | Array<string | number | boolean>) {
  item.value = value
  emitChange()
}

/** 条件 item 的辅助访问（模板中避免 union 收敛）。 */
function asCond(item: ConditionItem | ConditionRule): ConditionItem {
  return item as ConditionItem
}
function asGroup(item: ConditionItem | ConditionRule): ConditionRule {
  return item as ConditionRule
}

const isEmpty = computed(() => props.modelValue.items.length === 0)
</script>

<template>
  <div class="edge-rule" :class="{ root, nested: !root }">
    <div class="group-head">
      <span class="group-label">规则组</span>
        <el-segmented
    size="small"
    :model-value="modelValue.op"
    :options="['AND', 'OR']"
    @update:model-value="toggleOp()"
  />
  <span class="group-hint">{{ modelValue.op === 'AND' ? '全部条件满足' : '任一条件满足' }}</span>
    </div>

    <div v-if="isEmpty" class="group-empty">空规则组（恒不命中）</div>

    <div v-for="(item, index) in modelValue.items" :key="index" class="group-item">
      <div v-if="isGroup(item)" class="nested-wrap">
        <el-button size="small" type="danger" plain circle class="nested-del" @click="removeItem(index)">✕</el-button>
        <EdgeConditionEditor :model-value="asGroup(item)" :root="false" @update:model-value="(v: ConditionRule) => replaceGroup(index, v)" />
      </div>
      <div v-else class="cond-row">
        <el-select
          :model-value="asCond(item).field"
          placeholder="字段 (event.xxx / contact.xxx / history.xxx)"
          filterable
          allow-create
          default-first-option
          size="small"
          style="width: 210px"
          @update:model-value="(v: string) => setField(asCond(item), v)"
        >
          <el-option-group v-for="g in ['联系人', '事件', '历史']" :key="g" :label="g">
            <el-option
              v-for="opt in FIELD_OPTIONS.filter((o) => o.group.startsWith(g === '事件' ? 'event' : g === '历史' ? 'history' : 'contact'))"
              :key="opt.value"
              :label="opt.value"
              :value="opt.value"
            />
          </el-option-group>
        </el-select>

        <el-select
          :model-value="asCond(item).op"
          size="small"
          style="width: 120px"
          @update:model-value="(v: ConditionOp) => setOp(asCond(item), v)"
        >
          <el-option v-for="op in opsFor(asCond(item).field)" :key="op" :label="op" :value="op" />
        </el-select>

        <template v-if="isExistsOp(asCond(item).op)">
          <span class="no-value">（无需值）</span>
        </template>
        <template v-else-if="isInOp(asCond(item).op)">
          <el-select
            :model-value="asCond(item).value"
            placeholder="选择多个值"
            multiple
            filterable
            allow-create
            default-first-option
            size="small"
            style="width: 160px"
            @update:model-value="(v: Array<string | number | boolean>) => setValue(asCond(item), v)"
          >
            <el-option
              v-for="opt in FIELD_OPTIONS.filter((o) => o.group.startsWith('联系人'))"
              :key="opt.value"
              :label="opt.value"
              :value="opt.value"
            />
          </el-select>
        </template>
        <template v-else-if="isNumericOp(asCond(item).op)">
          <el-input-number
            :model-value="Number(asCond(item).value ?? 0)"
            size="small"
            controls-position="right"
            style="width: 140px"
            @update:model-value="(v: number | undefined) => setValue(asCond(item), v ?? 0)"
          />
        </template>
        <template v-else-if="isPctOp(asCond(item).op)">
          <el-input-number
            :model-value="Number(asCond(item).value ?? 50)"
            :min="0"
            :max="100"
            :step="1"
            size="small"
            controls-position="right"
            style="width: 140px"
            @update:model-value="(v: number | undefined) => setValue(asCond(item), v ?? 0)"
          />
        </template>
        <template v-else>
          <el-input
            :model-value="String(asCond(item).value ?? '')"
            placeholder="值"
            size="small"
            style="width: 140px"
            @update:model-value="(v: string) => setValue(asCond(item), v)"
          />
        </template>

        <el-button size="small" type="danger" plain circle @click="removeItem(index)">✕</el-button>
      </div>
    </div>

    <div class="group-actions">
      <el-button size="small" @click="addCondition()">+ 条件</el-button>
      <el-button size="small" @click="addGroup()">+ 子分组</el-button>
    </div>
  </div>
</template>

<style scoped>
.edge-rule {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 12px;
}
.edge-rule.root {
  border-color: #c6d8f7;
  background: #fafcff;
}
.edge-rule.nested {
  margin-left: 8px;
  background: #fff;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.group-label {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
}
.group-hint {
  font-size: 12px;
  color: #909399;
}
.group-empty {
  color: #c0c4cc;
  font-size: 12px;
  padding: 4px 0 8px;
}
.group-item {
  margin-bottom: 6px;
}
.nested-wrap {
  position: relative;
  padding-right: 8px;
}
.nested-del {
  position: absolute;
  top: 6px;
  right: 0;
  z-index: 1;
}
.cond-row {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 6px 8px;
}
.no-value {
  font-size: 12px;
  color: #909399;
  min-width: 100px;
}
.group-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>