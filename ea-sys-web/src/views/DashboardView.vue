<script setup lang="ts">
/**
 * 留存看板（M5）：转化漏斗 / 区间留存 / 渠道效果 / 工作流效果。
 * 数据：GET /api/retention/{funnel,interval,channel-effect,workflows}。
 */
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, FunnelChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'
import { getChannelEffect, getIntervalRetention, getRetentionFunnel, getWorkflowEffect } from '../api/retention'
import type { FunnelView, WorkflowEffectItem } from '../api/types'

use([CanvasRenderer, BarChart, FunnelChart, GridComponent, TooltipComponent])

const days = ref(30)

const { data: funnel, isLoading: funnelLoading, isError: funnelError, refetch } = useQuery<FunnelView>({
  queryKey: ['retention-funnel'],
  queryFn: () => getRetentionFunnel(),
})
const { data: interval, isLoading: intervalLoading } = useQuery({
  queryKey: ['retention-interval', days],
  queryFn: () => getIntervalRetention(days.value),
})
const { data: channel, isLoading: channelLoading, isError: channelError } = useQuery({
  queryKey: ['retention-channel', days],
  queryFn: () => getChannelEffect(days.value),
})
const { data: workflow, isLoading: workflowLoading, isError: workflowError } = useQuery({
  queryKey: ['retention-workflows', days],
  queryFn: () => getWorkflowEffect(days.value),
})

async function refresh() {
  try {
    await refetch()
  } catch {
    ElMessage.error('刷新失败')
  }
}

const pct = (v: number | undefined | null): string => `${Math.round((v ?? 0) * 100)}%`

// 漏斗：圈选 → 执行 → 触达（funnel series 数值降序）
const funnelOption = computed(() => {
  const f = funnel.value
  const data = [
    { name: '圈选', value: f?.seeded ?? 0 },
    { name: '执行', value: f?.executed ?? 0 },
    { name: '触达', value: f?.reached ?? 0 },
  ]
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} 人' },
    series: [
      {
        type: 'funnel',
        left: '6%',
        top: 24,
        bottom: 12,
        width: '88%',
        minSize: '22%',
        maxSize: '100%',
        sort: 'descending',
        gap: 2,
        label: { show: true, position: 'inside', color: '#fff', fontSize: 13, formatter: '{b}\n{c} 人' },
        itemStyle: { borderColor: '#fff', borderWidth: 1 },
        data,
      },
    ],
  }
})

// 渠道效果柱状：sent / failed（空数组时页面显空态）
const channelOption = computed(() => {
  const items = channel.value?.channels ?? []
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { data: ['发送成功', '失败'], top: 0 },
    grid: { left: 40, right: 16, top: 32, bottom: 28 },
    xAxis: { type: 'category', data: items.map((c) => c.channel), axisLabel: { interval: 0 } },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '发送成功', type: 'bar', data: items.map((c) => c.sent), itemStyle: { color: '#409eff' }, barMaxWidth: 28 },
      { name: '失败', type: 'bar', data: items.map((c) => c.failed), itemStyle: { color: '#f56c6c' }, barMaxWidth: 28 },
    ],
  }
})

// 工作流效果柱状：触达 / 留存人数（x 轴显示工作流名称，超长截断，悬浮看全名）
const workflowIdLabel = (w: WorkflowEffectItem) => {
  const name = w.workflowName || `WF #${w.workflowId}`
  return name.length > 8 ? `${name.slice(0, 8)}…` : name
}
const workflowOption = computed(() => {
  const items = workflow.value?.workflows ?? []
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: Array<{ dataIndex: number; marker: string; seriesName: string; value: number }>) => {
        const w = items[params[0].dataIndex]
        const rows = params.map((p) => `${p.marker}${p.seriesName}：${p.value}`).join('<br/>')
        return `<b>${w.workflowName || `WF #${w.workflowId}`}</b><br/>${rows}`
      },
    },
    legend: { data: ['触达', '留存'], top: 0 },
    grid: { left: 40, right: 16, top: 32, bottom: 28 },
    xAxis: {
      type: 'category',
      data: items.map(workflowIdLabel),
      axisLabel: { interval: 0, width: 64, overflow: 'truncate' },
    },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '触达', type: 'bar', data: items.map((w) => w.reached), itemStyle: { color: '#67c23a' }, barMaxWidth: 28 },
      { name: '留存', type: 'bar', data: items.map((w) => w.retained), itemStyle: { color: '#e6a23c' }, barMaxWidth: 28 },
    ],
  }
})

const fmtTime = (iso: string | null | undefined): string => {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const hasChannels = computed(() => (channel.value?.channels ?? []).length > 0)
const hasWorkflows = computed(() => (workflow.value?.workflows ?? []).length > 0)
</script>

<template>
  <div class="dashboard-page">
    <div class="page-head">
      <h3>留存看板</h3>
      <div class="controls">
        <el-radio-group v-model="days" size="default">
          <el-radio-button :value="7">7 天</el-radio-button>
          <el-radio-button :value="30">30 天</el-radio-button>
          <el-radio-button :value="90">90 天</el-radio-button>
        </el-radio-group>
        <el-button :loading="funnelLoading" @click="refresh">刷新</el-button>
      </div>
    </div>

    <div class="grid">
      <!-- ① 转化漏斗 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">转化漏斗</div>
          <div class="panel-sub">圈选 → 执行 → 触达</div>
        </template>
        <div v-loading="funnelLoading">
          <el-alert v-if="funnelError" title="漏斗数据加载失败" type="error" :closable="false" class="panel-error" />
          <div v-else-if="(funnel?.seeded ?? 0) === 0" class="empty-wrap">
            <el-empty description="暂无真实触达数据，请先执行工作流" :image-size="80" />
          </div>
          <template v-else>
            <VChart :option="funnelOption" autoresize class="chart" style="height: 240px" />
            <div class="rate-cards">
              <div class="rate-card">
                <span class="rate-num">{{ pct(funnel?.seededToExecutedRate) }}</span>
                <span class="rate-label">圈选 → 执行</span>
              </div>
              <div class="rate-card">
                <span class="rate-num">{{ pct(funnel?.executedToReachedRate) }}</span>
                <span class="rate-label">执行 → 触达</span>
              </div>
            </div>
          </template>
        </div>
      </el-card>

      <!-- ② 区间留存 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">区间留存</div>
          <div class="panel-sub">{{ days }} 天双窗口活跃留存</div>
        </template>
        <div v-loading="intervalLoading" class="retention-body">
          <div class="retention-hero">
            <span class="retention-rate">{{ pct(interval?.rate) }}</span>
            <span class="retention-desc">{{ interval?.retained ?? 0 }} / {{ interval?.cohort ?? 0 }} 人周期内仍活跃</span>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="前窗口">{{
              fmtTime(interval?.priorWindowStart) }} ～ {{ fmtTime(interval?.priorWindowEnd) }}</el-descriptions-item>
            <el-descriptions-item label="前窗口活跃 (cohort)">{{ interval?.cohort ?? 0 }} 人</el-descriptions-item>
            <el-descriptions-item label="当前窗口">{{
              fmtTime(interval?.currentWindowStart) }} ～ {{ fmtTime(interval?.currentWindowEnd) }}</el-descriptions-item>
            <el-descriptions-item label="当前窗口仍活跃">{{ interval?.retained ?? 0 }} 人</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-card>

      <!-- ③ 渠道效果 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">渠道效果</div>
          <div class="panel-sub">最近 {{ days }} 天送达情况</div>
        </template>
        <div v-loading="channelLoading">
          <el-alert v-if="channelError" title="渠道数据加载失败" type="error" :closable="false" class="panel-error" />
          <div v-else-if="!hasChannels" class="empty-wrap">
            <el-empty description="暂无渠道触达数据" :image-size="80" />
          </div>
          <template v-else>
            <VChart :option="channelOption" autoresize class="chart" style="height: 220px" />
            <el-table :data="channel?.channels ?? []" size="small" border class="mini-table">
              <el-table-column prop="channel" label="渠道" width="90" />
              <el-table-column prop="total" label="总数" width="70" />
              <el-table-column prop="sent" label="成功" width="70" />
              <el-table-column prop="failed" label="失败" width="70" />
              <el-table-column prop="distinctContacts" label="去重人数" width="80" />
              <el-table-column label="送达率">
                <template #default="{ row }">
                  <el-progress :percentage="Math.round(row.deliveryRate * 100)" :stroke-width="10" />
                </template>
              </el-table-column>
            </el-table>
          </template>
        </div>
      </el-card>

      <!-- ④ 工作流效果 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">工作流效果</div>
          <div class="panel-sub">各工作流最近一次执行的触达与留存</div>
        </template>
        <div v-loading="workflowLoading">
          <el-alert v-if="workflowError" title="工作流效果加载失败" type="error" :closable="false" class="panel-error" />
          <div v-else-if="!hasWorkflows" class="empty-wrap">
            <el-empty description="暂无真实触达，运行工作流后展示效果" :image-size="80" />
          </div>
          <template v-else>
            <VChart :option="workflowOption" autoresize class="chart" style="height: 220px" />
            <el-table :data="workflow?.workflows ?? []" size="small" border class="mini-table">
              <el-table-column label="工作流" width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.workflowName || `WF #${row.workflowId}` }}</template>
              </el-table-column>
              <el-table-column prop="reached" label="触达人数" width="90" />
              <el-table-column prop="retained" label="留存人数" width="90" />
              <el-table-column label="留存率">
                <template #default="{ row }">
                  <el-progress :percentage="Math.round(row.retentionRate * 100)" :stroke-width="10" />
                </template>
              </el-table-column>
            </el-table>
          </template>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.dashboard-page {
  padding: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.controls {
  display: flex;
  align-items: center;
  gap: 8px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 1200px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
.panel :deep(.el-card__header) {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.panel-title {
  font-weight: 600;
  font-size: 15px;
}
.panel-sub {
  color: #909399;
  font-size: 12px;
}
.panel-error {
  margin-bottom: 8px;
}
.empty-wrap {
  display: flex;
  justify-content: center;
  padding: 24px 0;
}
.rate-cards {
  display: flex;
  gap: 12px;
  margin-top: 8px;
}
.rate-card {
  flex: 1;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.rate-num {
  font-size: 20px;
  font-weight: 600;
  color: #409eff;
}
.rate-label {
  font-size: 12px;
  color: #909399;
}
.retention-body {
  min-height: 240px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.retention-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 20px 0 8px;
  gap: 4px;
}
.retention-rate {
  font-size: 40px;
  font-weight: 700;
  color: #67c23a;
}
.retention-desc {
  color: #909399;
  font-size: 13px;
}
.chart {
  width: 100%;
}
.mini-table {
  margin-top: 10px;
}
</style>