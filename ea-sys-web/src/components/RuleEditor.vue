<script setup lang="ts">
/**
 * 圈选规则编辑器（递归）：AND/OR 分组树。
 * 模型对齐后端 RuleCompiler：
 *  - 分组 { op: 'AND'|'OR', items: [...] }，items 可含条件或嵌套分组
 *  - 条件 { field, op, value? }；field 空间 contact.* / attribute.* / tag.*
 *  - op 白名单按字段空间过滤（contact 列无存在性/数值比较，标签仅存在性）
 */
import { computed } from 'vue'
import type { AudienceRule, RuleCondition, RuleOp } from '../api/types'

const props = defineProps<{
  modelValue: AudienceRule
  /** 是否根节点（根节点隐藏删除） */
  root?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: AudienceRule]
}>()

const CONTACT_COLS = ['id', 'external_id', 'phone', 'email', 'push_token', 'status'] as const

const FIELD_OPTIONS: Array<{ group: string; value: string }> = [
  ...CONTACT_COLS.map((c) => ({ group: '联系人字段 contact.*', value: `contact.${c}` })),
  { group: '画像属性 attribute.*', value: 'attribute.layer' },
  { group: '画像属性 attribute.*', value: 'attribute.churn_risk' },
  { group: '标签 tag.*', value: 'tag.vip' },
  { group: '标签 tag.*', value: 'tag.new_user' },
]

const CONTACT_OPS: RuleOp[] = ['equals', 'not_equals', 'in', 'not_in', 'contains']
const TAG_OPS: RuleOp[] = ['equals', 'not_equals', 'in', 'not_in', 'exists', 'not_exists']
const ALL_OPS: RuleOp[] = [
  'equals', 'not_equals', 'in', 'not_in', 'contains',
  'gt', 'gte', 'lt', 'lte', 'exists', 'not_exists',
]

function scopeOf(field: string): 'contact' | 'attribute' | 'tag' {
  if (field.startsWith('contact.')) return 'contact'
  if (field.startsWith('attribute.')) return 'attribute'
  if (field.startsWith('tag.')) return 'tag'
  return 'attribute'
}

function opsFor(field: string): RuleOp[] {
  const scope = scopeOf(field)
  if (scope === 'contact') return CONTACT_OPS
  if (scope === 'tag') return TAG_OPS
  return ALL_OPS
}

function isGroup(item: RuleCondition | AudienceRule): item is AudienceRule {
  return 'items' in item && Array.isArray(item.items)
}

function isExistsOp(op: RuleOp): boolean {
  return op === 'exists' || op === 'not_exists'
}
function isInOp(op: RuleOp): boolean {
  return op === 'in' || op === 'not_in'
}
function isNumericOp(op: RuleOp): boolean {
  return ['gt', 'gte', 'lt', 'lte'].includes(op)
}

function emitChange() {
  emit('update:modelValue', props.modelValue)
}

function addCondition() {
  props.modelValue.items.push({ field: 'contact.phone', op: 'equals', value: '' })
  emitChange()
}

function addGroup() {
  props.modelValue.items.push({ op: 'AND', items: [{ field: 'contact.phone', op: 'equals', value: '' }] })
  emitChange()
}

function removeItem(index: number) {
  props.modelValue.items.splice(index, 1)
  emitChange()
}

function toggleOp() {
  props.modelValue.op = props.modelValue.op === 'AND' ? 'OR' : 'AND'
  emitChange()
}

/* 条件行更新（模板内联箭头会丢失类型，统一抽具名函数） */
function setField(item: RuleCondition, value: string) {
  item.field = value
  if (!opsFor(value).includes(item.op)) item.op = opsFor(value)[0]
  emitChange()
}

function setOp(item: RuleCondition, value: RuleOp) {
  item.op = value
  if (!isExistsOp(value) && item.value === undefined) item.value = ''
  emitChange()
}

function setValue(item: RuleCondition, value: string | number | boolean | Array<string | number | boolean>) {
  item.value = value
  emitChange()
}

/** 条件 item 的辅助访问（模板中避免 union 收敛）。 */
function asCond(item: RuleCondition | AudienceRule): RuleCondition {
  return item as RuleCondition
}
function asGroup(item: RuleCondition | AudienceRule): AudienceRule {
  return item as AudienceRule
}

const isEmpty = computed(() => props.modelValue.items.length === 0)
</script>

<template>
  <div class="rule-group" :class="{ root: root, nested: !root }">
    <div class="group-head">
      <span class="group-label">分组</span>
      <el-radio-group :model-value="modelValue.op" size="small" @update:model-value="toggleOp">
        <el-radio-button :value="'AND'">AND（全部满足）</el-radio-button>
        <el-radio-button :value="'OR'">OR（任一满足）</el-radio-button>
      </el-radio-group>
      <span v-if="!root" class="group-hint">items: {{ modelValue.items.length }}</span>
    </div>

    <div v-if="isEmpty" class="group-empty">该分组下暂无条件</div>

    <div v-for="(item, i) in modelValue.items" :key="i" class="group-item">
      <template v-if="isGroup(item)">
        <div class="nested-wrap">
          <RuleEditor :model-value="asGroup(item)" @update:model-value="emitChange" />
          <el-button class="nested-del" link type="danger" :circle="true" @click="removeItem(i)">✕</el-button>
        </div>
      </template>
      <template v-else>
        <div class="cond-row">
          <el-select
            :model-value="asCond(item).field"
            filterable
            allow-create
            default-first-option
            style="width: 240px"
            @update:model-value="(v: string) => setField(asCond(item), v)"
          >
            <el-option-group
              v-for="g in FIELD_OPTIONS.map((o) => o.group).filter((g2, idx, arr) => arr.indexOf(g2) === idx)"
              :key="g"
              :label="g"
            >
              <el-option v-for="o in FIELD_OPTIONS.filter((x) => x.group === g)" :key="o.value" :label="o.value" :value="o.value" />
            </el-option-group>
          </el-select>

          <el-select
            :model-value="asCond(item).op"
            style="width: 130px"
            @update:model-value="(v: RuleOp) => setOp(asCond(item), v)"
          >
            <el-option v-for="op in opsFor(asCond(item).field)" :key="op" :label="op" :value="op" />
          </el-select>

          <template v-if="!isExistsOp(asCond(item).op)">
            <el-select
              v-if="isInOp(asCond(item).op)"
              :model-value="asCond(item).value"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="输入值回车添加"
              style="min-width: 200px; flex: 1"
              @update:model-value="(v: Array<string | number | boolean>) => setValue(asCond(item), v)"
            >
              <el-option
                v-for="(cv, j) in (asCond(item).value as Array<string | number | boolean>)"
                :key="j"
                :value="cv"
                :label="String(cv)"
              />
            </el-select>
            <el-input-number
              v-else-if="isNumericOp(asCond(item).op)"
              :model-value="asCond(item).value as number | undefined"
              :controls="false"
              placeholder="数值"
              style="flex: 1; min-width: 140px"
              @update:model-value="(v: number | undefined) => setValue(asCond(item), v ?? 0)"
            />
            <el-input
              v-else
              :model-value="asCond(item).value as string | undefined"
              placeholder="值"
              style="flex: 1; min-width: 140px"
              @update:model-value="(v: string) => setValue(asCond(item), v)"
            />
          </template>

          <el-button link type="danger" :circle="true" @click="removeItem(i)">✕</el-button>
        </div>
      </template>
    </div>

    <div class="group-actions">
      <el-button size="small" type="primary" plain @click="addCondition">+ 条件</el-button>
      <el-button size="small" type="primary" plain @click="addGroup">+ 子分组</el-button>
    </div>
  </div>
</template>

<style scoped>
.rule-group {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 10px 12px;
}
.rule-group.root {
  border-color: #c6d8f7;
  background: #fafcff;
}
.rule-group.nested {
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
.group-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
</style>