<script setup lang="ts">
/**
 * 工作流列表：每业务 id 族最新可用行（列表端点 GET /api/workflows）。
 * 行点击 → 画布编辑 /canvas/:id；新建 → /canvas。
 */
import { ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listWorkflows, listWorkflowSnapshots } from '../api/workflow'
import type { WorkflowDryRun, WorkflowSummary, WorkflowVersion } from '../api/types'

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

function statusOf(row: WorkflowSummary | WorkflowVersion) {
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

const snapshotsVisible = ref(false)
const snapshotsLoading = ref(false)
const publishSnapshots = ref<WorkflowVersion[]>([])
const dryRunSnapshots = ref<WorkflowDryRun[]>([])

async function openSnapshots(row: WorkflowSummary) {
  publishSnapshots.value = []
  dryRunSnapshots.value = []
  snapshotsVisible.value = true
  snapshotsLoading.value = true
  try {
    const snap = await listWorkflowSnapshots(row.id)
    publishSnapshots.value = snap.publishSnapshots
    dryRunSnapshots.value = snap.dryRunSnapshots
  } catch {
    ElMessage.error('快照记录加载失败')
  } finally {
    snapshotsLoading.value = false
  }
}

async function refresh() {
  try {
    await refetch()
  } catch {
    ElMessage.error('刷新失败')
  }
}

function fmtIso(iso: string | null | undefined): string {
  return fmtTime(iso)
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
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click.stop="openWf(row)">打开画布</el-button>
          <el-button size="small" type="info" link @click.stop="openSnapshots(row)">快照记录</el-button>
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

    <el-dialog v-model="snapshotsVisible" title="快照记录" width="760px">
      <div v-loading="snapshotsLoading">
        <el-tabs>
          <el-tab-pane :label="`发布快照 (${publishSnapshots.length})`">
            <el-table :data="publishSnapshots" border stripe size="small">
              <el-table-column prop="version" label="版本" width="80" sortable />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="statusOf(row).type" size="small">{{ statusOf(row).label }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="发布时间" width="170">
                <template #default="{ row }">{{ fmtIso(row.publishedAt) }}</template>
              </el-table-column>
              <el-table-column prop="publishedBy" label="发布人" width="100">
                <template #default="{ row }">{{ row.publishedBy ?? '-' }}</template>
              </el-table-column>
              <el-table-column prop="createdBy" label="创建人" width="100" />
              <el-table-column label="创建时间" width="170">
                <template #default="{ row }">{{ fmtIso(row.createdAt) }}</template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无发布快照" :image-size="80" />
              </template>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`干跑快照 (${dryRunSnapshots.length})`">
            <el-table :data="dryRunSnapshots" border stripe size="small">
              <el-table-column prop="executionId" label="执行ID" width="90" sortable />
              <el-table-column prop="workflowVersion" label="画布版本" width="90" sortable />
              <el-table-column prop="audienceName" label="人群快照" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">{{ row.audienceName ?? `#${row.audienceSnapshotId ?? '-'}` }}</template>
              </el-table-column>
              <el-table-column prop="memberCount" label="成员数" width="80" />
              <el-table-column label="状态" width="110">
                <template #default="{ row }">
                  <el-tag
                    :type="String(row.status).startsWith('SUCCEEDED') ? 'success' : String(row.status).startsWith('FAILED') ? 'danger' : 'warning'"
                    size="small"
                  >{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="开始时间" width="160">
                <template #default="{ row }">{{ fmtIso(row.startedAt) }}</template>
              </el-table-column>
              <template #empty>
                <el-empty description="暂无干跑快照" :image-size="80" />
              </template>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>
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