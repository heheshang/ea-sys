<script setup lang="ts">
/**
 * 触达监控（M3）：执行历史列表（干跑/真实触达）+ 执行详情回放。
 * 列表 GET /api/workflows/executions，行点击 → GET /api/workflows/executions/{id}/report。
 */
import { ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { listWorkflowExecutions, getExecutionReport, listWorkflows } from '../api/workflow'
import type { DryRunResponse, ExecutionSummary, WorkflowSummary } from '../api/types'

const { data: workflows } = useQuery<WorkflowSummary[]>({
  queryKey: ['workflows'],
  queryFn: listWorkflows,
})

const dryRunFilter = ref<'all' | 'true' | 'false'>('all')
const workflowFilter = ref<number | undefined>(undefined)

const { data: rows, isLoading, isError, refetch } = useQuery<ExecutionSummary[]>({
  queryKey: ['executions', dryRunFilter, workflowFilter],
  queryFn: () =>
    listWorkflowExecutions({
      dryRun: dryRunFilter.value === 'all' ? undefined : dryRunFilter.value === 'true',
      workflowId: workflowFilter.value,
      limit: 200,
    }),
})

async function refresh() {
  try {
    await refetch()
  } catch {
    ElMessage.error('刷新失败')
  }
}

// 报告回放
const detailVisible = ref(false)
const detailLoading = ref(false)
const report = ref<DryRunResponse | null>(null)
const selected = ref<ExecutionSummary | null>(null)

async function openDetail(row: ExecutionSummary) {
  selected.value = row
  report.value = null
  detailVisible.value = true
  detailLoading.value = true
  try {
    report.value = await getExecutionReport(row.executionId)
  } catch {
    ElMessage.error('执行详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function statusType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status.startsWith('SUCCEEDED') || status === 'done') return 'success'
  if (status.startsWith('FAILED')) return 'danger'
  if (status === 'PENDING' || status === 'RUNNING') return 'warning'
  return 'info'
}
</script>

<template>
  <div class="monitor-page">
    <div class="list-head">
      <h3>触达监控</h3>
      <div class="filters">
        <el-select v-model="dryRunFilter" size="default" style="width: 130px" @change="refresh">
          <el-option label="全部执行" value="all" />
          <el-option label="干跑" value="true" />
          <el-option label="真实触达" value="false" />
        </el-select>
        <el-select
          v-model="workflowFilter"
          size="default"
          placeholder="全部工作流"
          clearable
          style="width: 180px"
          @change="refresh"
        >
          <el-option
            v-for="w in workflows ?? []"
            :key="w.id"
            :label="w.name"
            :value="w.id"
          />
        </el-select>
        <el-button :loading="isLoading" @click="refresh">刷新</el-button>
      </div>
    </div>

    <el-alert
      v-if="isError"
      title="执行历史加载失败"
      type="error"
      :closable="false"
      class="load-error"
    />

    <el-table v-loading="isLoading" :data="rows ?? []" border stripe size="small" highlight-current-row>
      <el-table-column prop="executionId" label="执行ID" width="90" sortable />
      <el-table-column prop="workflowName" label="工作流" min-width="140" show-overflow-tooltip />
      <el-table-column prop="workflowVersion" label="画布版本" width="90" sortable />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.dryRun ? 'warning' : 'success'" size="small">
            {{ row.dryRun ? '干跑' : '触达' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="人群快照" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.audienceName ?? `#${row.audienceSnapshotId ?? '-'}` }}</template>
      </el-table-column>
      <el-table-column prop="memberCount" label="成员数" width="80" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="开始时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openDetail(row)">报告</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无执行记录" :image-size="80" />
      </template>
    </el-table>

    <!-- 执行详情回放 -->
    <el-drawer v-model="detailVisible" :title="`执行报告 #${selected?.executionId ?? ''}`" size="640px">
      <div v-loading="detailLoading">
        <template v-if="report">
          <div class="report-summary">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="执行 ID">#{{ report.executionId }}</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag :type="statusType(report.status)">{{ report.status }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="成员总数">{{ report.totalMembers }}</el-descriptions-item>
              <el-descriptions-item label="耗时">{{ report.durationMs }} ms</el-descriptions-item>
              <el-descriptions-item label="类型">{{ report.dryRun ? '干跑' : '真实触达' }}</el-descriptions-item>
            </el-descriptions>
            <el-alert v-if="report.error" :title="report.error" type="error" :closable="false" class="report-error" />
          </div>
          <el-table :data="report.nodes" size="small" border>
            <el-table-column prop="nodeName" label="节点" min-width="110" show-overflow-tooltip />
            <el-table-column prop="nodeType" label="类型" width="100" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="contacts" label="流入人数" width="90" />
            <el-table-column label="输出" min-width="180">
              <template #default="{ row }">
                <pre v-if="row.output" class="node-output">{{ JSON.stringify(row.output, null, 1) }}</pre>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!detailLoading" description="无报告数据" :image-size="80" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.monitor-page {
  padding: 16px;
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.filters {
  display: flex;
  gap: 8px;
}
.load-error {
  margin-bottom: 12px;
}
.report-summary {
  margin-bottom: 12px;
}
.report-error {
  margin-top: 8px;
}
.node-output {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 140px;
  overflow-y: auto;
}
</style>