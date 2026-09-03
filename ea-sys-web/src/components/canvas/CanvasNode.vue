<script lang="ts">
import type { ConditionItem, ConditionRule } from '../../api/types'

/** 节点宽度(px)：按展示文本长度估算（13px 字号中文约一字 13px + 左右各 12px padding）。 */
export const NODE_MIN_W = 140
export const NODE_MAX_W = 260
export function nodeWidthOf(text: string): number {
  const len = Array.from(text?.trim() || '触发').length
  const w = 24 + len * 13 + 8
  return Math.min(NODE_MAX_W, Math.max(NODE_MIN_W, Math.ceil(w / 2) * 2))
}

/** 卡片实际宽度(px)：取名称宽与摘要最长行宽的较大者。
 *  与组件渲染宽度同源，父组件 autoLayout 用同一函数计算 dagre 布局宽度，保证布局与渲染一致。 */
export function nodeWidthOfNode(
  nodeKey: string | undefined,
  edges: Array<{ source: string; target: string; condition?: ConditionRule | null }>,
  targetNameOf: (key: string) => string,
  title?: string,
  countSummary = true,
): number {
  const nameW = nodeWidthOf(title ?? '')
  const rows = countSummary ? summaryLinesOf(nodeKey, edges, targetNameOf) : []
  const sumW = Math.max(0, ...rows.map((s) => nodeWidthOf(s)))
  return Math.max(nameW, sumW)
}

/** 节点基础高度(px)（head 26 + name 38 + border 4）；CONDITION/AGENT_SPLIT 加摘要行。 */
export const NODE_H = 68
export const SUMMARY_LINE_H = 18
export const SUMMARY_MAX_LINES = 3

/** 边条件 DSL → 单行可读文本（与 EdgeConditionEditor 同一算子符号）。null = else 兜底。 */
export function conditionText(cond: ConditionRule | null): string {
  if (!cond) return 'else 兜底'
  if (!cond.items.length) return '条件为空'
  const ruleText = (c: ConditionRule): string =>
    c.items
      .map((it) => ('items' in it && (it as ConditionRule).items ? ruleText(it as ConditionRule) : leafText(it as ConditionItem)))
      .join(` ${c.op} `)
  const leafText = (it: ConditionItem): string => {
    const v = Array.isArray(it.value) ? it.value.join(',') : String(it.value ?? '')
    return `${it.field} ${it.op} ${v}`.trim()
  }
  return ruleText(cond)
}

/** 节点摘要行：CONDITION/AGENT_SPLIT 展示每条出边目标与条件（无出边给提示行）。 */
export function summaryLinesOf(nodeKey: string | undefined, edges: Array<{ source: string; target: string; condition?: ConditionRule | null }>, targetNameOf: (key: string) => string): string[] {
  if (!nodeKey) return []
  const outs = edges.filter((e) => e.source === nodeKey)
  if (!outs.length) return ['未连线（拖出分支）']
  return outs.map((e) => `→ ${targetNameOf(e.target)}：${conditionText(e.condition ?? null)}`)
}

/** 节点高度(px)：基础高度 + 摘要行高（最多 SUMMARY_MAX_LINES 行，溢出行隐藏并用 +N 提示）。 */
export function nodeHeightOf(nodeKey: string | undefined, edges: Array<{ source: string }>): number {
  if (!nodeKey) return NODE_H
  const n = edges.filter((e) => e.source === nodeKey).length
  return NODE_H + (n ? Math.min(n, SUMMARY_MAX_LINES) * SUMMARY_LINE_H : 0)
}
</script>

<script setup lang="ts">
/**
 * 画布自定义节点（Vue Flow）。渲染 node.data.real 中携带的后端 WorkflowNodeSpec：
 *  - data.real = { key, type, name, config }
 * CONDITION / AGENT_SPLIT 出边承载条件 DSL（edge.data.condition，null=else 兜底），
 * 卡片在名称下方追加摘要行展示各分支目标与条件，悬停显示完整文本。
 * 宽度按名称与摘要最长行自适应（NODE_MIN_W-NODE_MAX_W），高度随摘要行数增长，
 * 与 dagre 布局 H（nodeHeightOf）保持一致。
 */
import { computed } from 'vue'
import { Handle, Position, useVueFlow } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'
import type { WorkflowNodeType } from '../../api/types'

const props = defineProps<NodeProps>()

const TYPE_META: Record<WorkflowNodeType, { label: string; color: string }> = {
  TRIGGER: { label: '触发', color: '#67c23a' },
  AUDIENCE: { label: '人群', color: '#2f54eb' },
  CONDITION: { label: '条件', color: '#e6a23c' },
  AGENT_SPLIT: { label: 'Agent 分流', color: '#b37feb' },
  DELAY: { label: '延时', color: '#36cfc9' },
  ACTION: { label: '动作', color: '#409eff' },
  UPDATE: { label: '更新', color: '#722ed1' },
  END: { label: '结束', color: '#f5222d' },
}

const type = computed(() => (props.data.real?.type ?? 'END') as WorkflowNodeType)
const meta = computed(() => TYPE_META[type.value] ?? TYPE_META.END)
const displayName = computed(() => props.data.real?.name || meta.value.label)

/** 摘要节点：条件/分流的分支信息挂在出边上，从当前画布 store 取（避免父组件逐处注入）。 */
const { edges: flowEdges, nodes: flowNodes } = useVueFlow()
const showSummary = computed(() => type.value === 'CONDITION' || type.value === 'AGENT_SPLIT' || type.value === 'AUDIENCE')

/** AUDIENCE：摘要为绑定人群名（后端 view 已注入 audienceName；缺省回退人群 id）。 */
const audienceSummary = computed(() => {
  const cfg = props.data.real?.config as Record<string, unknown> | null
  const name = cfg?.audienceName ? String(cfg.audienceName) : ''
  const id = cfg?.audienceId
  return `人群：${name || (id ? '#' + String(id) : '未配置')}`
})

const summaryLines = computed(() => {
  if (type.value === 'AUDIENCE') return [audienceSummary.value]
  if (!showSummary.value) return []
  const key = props.data.real?.key
  if (!key) return []
  const targetName = (k: string): string => {
    const t = (flowNodes.value as Array<{ id: string; data: { real?: { name?: string } } }>).find((n) => n.id === k)
    return t?.data?.real?.name?.trim() || k
  }
  const es = flowEdges.value as Array<{ source: string; target: string; data: { condition: ConditionRule | null } }>
  return summaryLinesOf(key, es.map((e) => ({ source: e.source, target: e.target, condition: e.data?.condition ?? null })), targetName)
})

const shownLines = computed(() => summaryLines.value.slice(0, SUMMARY_MAX_LINES))
const moreCount = computed(() => Math.max(0, summaryLines.value.length - SUMMARY_MAX_LINES))
const summaryTitle = computed(() => (summaryLines.value.length ? summaryLines.value.join('\n') : ''))

const nodeWidth = computed(() => {
  if (type.value === 'AUDIENCE') {
    // AUDIENCE 摘要为人群名（非边驱动），直接按名称与摘要行取较大者
    return Math.max(nodeWidthOf(displayName.value), nodeWidthOf(audienceSummary.value))
  }
  const targetName = (k: string): string => {
    const t = (flowNodes.value as Array<{ id: string; data: { real?: { name?: string } } }>).find((n) => n.id === k)
    return t?.data?.real?.name?.trim() || k
  }
  const es = flowEdges.value as Array<{ source: string; target: string; data: { condition: ConditionRule | null } }>
  return nodeWidthOfNode(
    props.data.real?.key,
    es.map((e) => ({ source: e.source, target: e.target, condition: e.data?.condition ?? null })),
    targetName,
    displayName.value,
    showSummary.value,
  )
})
const nodeHeight = computed(() => NODE_H + shownLines.value.length * SUMMARY_LINE_H)
const hasTarget = computed(() => type.value !== 'TRIGGER')
const hasSource = computed(() => type.value !== 'END')
</script>

<template>
  <div
    class="wf-node"
    :class="{ 'wf-node--pending': props.data?.pendingConnect }"
    :style="{ borderColor: meta.color, width: nodeWidth + 'px', height: nodeHeight + 'px' }"
  >
    <Handle v-if="hasTarget" type="target" :position="Position.Left" />
    <div class="wf-node-head" :style="{ background: meta.color }">
      <span class="wf-node-type">{{ meta.label }}</span>
      <span class="wf-node-live" />
    </div>
    <div class="wf-node-name" :title="displayName">{{ displayName }}</div>
    <div v-if="showSummary" class="wf-node-summary" :title="summaryTitle">
      <div v-for="line in shownLines" :key="line" class="wf-node-summary-line">{{ line }}</div>
      <div v-if="moreCount" class="wf-node-summary-more">+{{ moreCount }} 分支…</div>
    </div>
    <Handle v-if="hasSource" type="source" :position="Position.Right" />
  </div>
</template>

<style scoped>
.wf-node {
  background: #fff;
  border: 2px solid;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.12);
  font-size: 13px;
}
/* 双选连线：待连源节点虚线圈出 */
.wf-node--pending {
  outline: 2px dashed #409eff;
  outline-offset: 3px;
  box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.12);
}
.wf-node-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 12px;
  border-radius: 6px 6px 0 0;
  color: #fff;
}
.wf-node-type {
  font-weight: 600;
  font-size: 12px;
  line-height: 16px;
}
.wf-node-live {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.85);
}
.wf-node-name {
  padding: 9px 12px;
  font-size: 13px;
  line-height: 20px;
  color: #303133;
  text-align: center;
  min-height: 38px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wf-node-summary {
  border-top: 1px dashed #e4e7ed;
  padding: 4px 12px 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.wf-node-summary-line {
  font-size: 12px;
  line-height: 16px;
  color: #606266;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.wf-node-summary-more {
  font-size: 12px;
  line-height: 16px;
  color: #909399;
  white-space: nowrap;
}
</style>