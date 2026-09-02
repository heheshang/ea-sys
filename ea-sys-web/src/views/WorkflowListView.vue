<script setup lang="ts">
/**
 * 工作流列表：每业务 id 族最新可用行（列表端点 GET /api/workflows）。
 * 行点击 → 画布编辑 /canvas/:id；新建 → /canvas。
 */
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listWorkflows } from '../api/workflow'
import type { WorkflowSummary } from '../api/types'

const router = useRouter()

const { data: rows, isLoading, isError, refetch } = useQuery<WorkflowSummary[]>({
  queryKey: ['workflows'],
  queryFn: listWorkflows,
})

const STATUS_META: Record<string, { label: string; type: 'success' | 'warning' | 'info' }> = {
  draft: { label: '草稿', type: 'warning' },
  published: { label: '已发布', type: 'success' },
  archived: { label: '已归档', type: 'info' },
}

function statusOf(row: WorkflowSummary) {
  return STATUS_META[row.status] ?? { label: row.status, type: 'info' as const }
}

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '-'
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function openWf(row: WorkflowSummary) {
  router.push(`/canvas/${row.id}`)
}

async function refresh() {
  try {
    await refetch()
  } catch {
    ElMessage.error('刷新失败')
  }
}
</script>

<template>
  <div class="wf-list-page">
    <div class="list-head">
      <h3>工作流</h3>
      <div>
        <el-button @click="refresh">刷新</el-button>
        <el-button type="primary" @click="router.push('/canvas')">新建画布</el-button>
      </div>
    </div>

    <el-table
      v-loading="isLoading"
      :data="rows ?? []"
      border
      stripe
      style="width: 100%"
      @row-click="openWf"
    >
      <el-table-column prop="id" label="ID" width="80" sortable />
      <el-table-column prop="name" label="名称" min-width="200">
        <template #default="{ row }">
          <span class="wf-name">{{ row.name || '(未命名)' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusOf(row).type" size="small">{{ statusOf(row).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="version" label="版本" width="80" sortable />
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人" width="110" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click.stop="openWf(row)">打开画布</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无工作流" :image-size="100">
          <el-button type="primary" @click="router.push('/canvas')">新建画布</el-button>
        </el-empty>
      </template>
    </el-table>
    <el-alert
      v-if="isError"
      title="列表加载失败，请检查后端服务"
      type="error"
      :closable="false"
      style="margin-top: 12px"
    />
  </div>
</template>

<style scoped>
.wf-list-page {
  padding: 16px 20px;
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.list-head h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}
.wf-name {
  color: #303133;
  font-weight: 500;
}
</style>