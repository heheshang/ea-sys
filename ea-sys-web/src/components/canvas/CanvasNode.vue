<script lang="ts">
/** 节点宽度(px)：按展示文本长度估算（13px 字号中文约一字 13px + 左右各 12px padding）。
 *  父组件 autoLayout 用同一函数计算 dagre 布局宽度，保证布局与渲染一致。 */
export const NODE_MIN_W = 140
export const NODE_MAX_W = 260
export function nodeWidthOf(text: string): number {
  const len = Array.from(text?.trim() || '触发').length
  const w = 24 + len * 13 + 8
  return Math.min(NODE_MAX_W, Math.max(NODE_MIN_W, Math.ceil(w / 2) * 2))
}
</script>

<script setup lang="ts">
/**
 * 画布自定义节点（Vue Flow）。渲染 node.data.real 中携带的后端 WorkflowNodeSpec：
 *  - data.real = { key, type, name, config }
 * 类型不同仅外观/手柄差异，交互统一。
 * 宽度按名称长度自适应（140-260px），名称超宽单行省略、悬浮显示全名；
 * 高度固定 68px（head 26 + name 38 + border 4），与 dagre 布局 H 一致。
 */
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'
import type { WorkflowNodeType } from '../../api/types'

const props = defineProps<NodeProps>()

const TYPE_META: Record<WorkflowNodeType, { label: string; color: string }> = {
  TRIGGER: { label: '触发', color: '#67c23a' },
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
const nodeWidth = computed(() => nodeWidthOf(displayName.value))
const hasTarget = computed(() => type.value !== 'TRIGGER')
const hasSource = computed(() => type.value !== 'END')
</script>

<template>
  <div class="wf-node" :style="{ borderColor: meta.color, width: nodeWidth + 'px' }">
    <Handle v-if="hasTarget" type="target" :position="Position.Left" />
    <div class="wf-node-head" :style="{ background: meta.color }">
      <span class="wf-node-type">{{ meta.label }}</span>
      <span class="wf-node-live" />
    </div>
    <div class="wf-node-name" :title="displayName">{{ displayName }}</div>
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
</style>