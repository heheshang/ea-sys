<script setup lang="ts">
/**
 * 驾驶舱（M8）：LLM 调用监控总览 + 图谱管理（八类知识领域登记/状态）+ 洞察 + 追踪。
 * 数据：GET /api/cockpit/{overview,graph,insights,llm-traces}；图谱 CRUD / PATCH status。
 */
import { computed, reactive, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { GRAPH_MODULE_LABELS, GRAPH_MODULES, createGraphEntry, deleteGraphEntry, getCockpitInsights, getCockpitOverview, listGraphEntries, listLlmTraces, setGraphEntryStatus, updateGraphEntry } from '../api/cockpit'
import type { AgentGraphEntrySaveRequest, AgentGraphEntryView, CockpitOverviewView } from '../api/types'

const { data: overview, isLoading: overviewLoading, isError: overviewError, refetch: refetchOverview } =
  useQuery<CockpitOverviewView>({
    queryKey: ['cockpit-overview'],
    queryFn: getCockpitOverview,
  })

async function refreshAll() {
  try {
    await refetchOverview()
    await refetchGraph()
    await refetchInsights()
    await refetchTraces()
  } catch {
    ElMessage.error('刷新失败')
  }
}

/* ---------- LLM 调用追踪 ---------- */
const { data: traces, isLoading: tracesLoading, refetch: refetchTraces } = useQuery({
  queryKey: ['cockpit-traces'],
  queryFn: () => listLlmTraces(20),
})

/* ---------- 图谱管理 ---------- */
const activeModule = ref<string>(GRAPH_MODULES[0])

const { data: graphRows, isLoading: graphLoading, refetch: refetchGraph } = useQuery<AgentGraphEntryView[]>({
  queryKey: ['cockpit-graph', activeModule],
  queryFn: () => listGraphEntries(activeModule.value),
})

const moduleLabel = (m: string) => GRAPH_MODULE_LABELS[m] ?? m

// 新建/编辑对话框
const dialogVisible = ref(false)
const dialogSaving = ref(false)
const editingId = ref<number | null>(null) // null = 新建
const form = reactive<AgentGraphEntrySaveRequest>({
  module: GRAPH_MODULES[0],
  entryKey: '',
  name: '',
  description: '',
  payload: undefined,
  status: 'ENABLED',
  version: '',
})
const payloadText = ref('')
const formError = ref('')

function openCreate() {
  editingId.value = null
  form.module = activeModule.value
  form.entryKey = ''
  form.name = ''
  form.description = ''
  form.payload = undefined
  form.status = 'ENABLED'
  form.version = ''
  payloadText.value = ''
  formError.value = ''
  dialogVisible.value = true
}

function openEdit(row: AgentGraphEntryView) {
  if (row.id == null) return
  editingId.value = row.id
  form.module = row.module
  form.entryKey = row.entryKey
  form.name = row.name
  form.description = row.description ?? ''
  form.status = row.status
  form.version = row.version ?? ''
  payloadText.value = row.payload === undefined || row.payload === null ? '' : JSON.stringify(row.payload, null, 2)
  formError.value = ''
  dialogVisible.value = true
}

async function saveEntry() {
  if (!form.name.trim()) {
    formError.value = '名称不能为空'
    return
  }
  if (editingId.value == null && !form.entryKey.trim()) {
    formError.value = '登记 key 不能为空'
    return
  }
  // payload 文本 → JSON（可空）
  if (payloadText.value.trim()) {
    try {
      form.payload = JSON.parse(payloadText.value)
    } catch {
      formError.value = 'payload 不是合法 JSON'
      return
    }
  } else {
    form.payload = undefined
  }
  dialogSaving.value = true
  try {
    if (editingId.value == null) {
      await createGraphEntry({ ...form })
    } else {
      await updateGraphEntry(editingId.value, { ...form })
    }
    ElMessage.success(editingId.value == null ? '登记成功' : '已保存')
    dialogVisible.value = false
    await refetchGraph()
    await refetchOverview()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    dialogSaving.value = false
  }
}

async function toggleStatus(row: AgentGraphEntryView) {
  if (row.id == null) return
  const next: 'ENABLED' | 'DISABLED' = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    await setGraphEntryStatus(row.id, next)
    ElMessage.success(next === 'ENABLED' ? '已启用' : '已停用')
    await refetchGraph()
    await refetchOverview()
  } catch {
    ElMessage.error('状态更新失败')
  }
}

async function removeEntry(row: AgentGraphEntryView) {
  if (row.id == null) return
  try {
    await ElMessageBox.confirm(`确认删除图谱登记「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteGraphEntry(row.id)
    ElMessage.success('已删除')
    await refetchGraph()
    await refetchOverview()
  } catch {
    ElMessage.error('删除失败')
  }
}

/* ---------- 洞察 ---------- */
const queryClient = useQueryClient()
const { data: insights, isLoading: insightsLoading, refetch: refetchInsights } = useQuery({
  queryKey: ['cockpit-insights'],
  queryFn: () => getCockpitInsights(false),
})

async function forceInsights() {
  insightsLoading.value = true
  try {
    const fresh = await getCockpitInsights(true)
    queryClient.setQueryData(['cockpit-insights'], fresh)
    ElMessage.success('洞察已重新生成')
  } catch {
    ElMessage.error('洞察生成失败')
  } finally {
    insightsLoading.value = false
  }
}

const healthColor = computed(() => {
  const h = insights.value?.overallHealth ?? 100
  if (h >= 80) return '#67c23a'
  if (h >= 60) return '#e6a23c'
  return '#f56c6c'
})

const levelType = (level: string): 'danger' | 'warning' | 'info' => {
  if (level === 'critical') return 'danger'
  if (level === 'warning') return 'warning'
  return 'info'
}

const pct = (v: number | null | undefined): string => `${Math.round((v ?? 0) * 100)}%`
// Token 未计量（确定性模式 tokens 恒为 null → SUM=0）：显示 — 而非无意义的 0
const fmtToken = (v: number | null | undefined): string => (v ?? 0) === 0 ? '—' : String(v)
// 上下文构成：key → 中文类别（注入上下文优先用 synthetic 元数据判定，见 LlmUsageMiddleware）
const CONTEXT_LABELS: Record<string, string> = {
  system: '系统提示词',
  tool_schema: '工具Schema',
  user: '用户消息',
  assistant: '助手消息',
  injected: '注入上下文',
  tool_result: '工具结果',
}
const contextLabel = (key: string): string => CONTEXT_LABELS[key] ?? key
const contextPct = (tokens: number): string => {
  const total = overview.value?.llm.context?.tokens ?? 0
  if (total <= 0) return '0.0%' // 构成总 Token 为 0（全空模型调用）时避免除零
  return `${((tokens / total) * 100).toFixed(1)}%`
}
const fmtTime = (iso: string | null | undefined): string => {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const statusType = (status: string): 'success' | 'danger' | 'warning' | 'info' => {
  if (status.startsWith('SUCCESS') || status === 'done') return 'success'
  if (status.startsWith('ERROR') || status.startsWith('FAILED')) return 'danger'
  if (status === 'FALLBACK') return 'warning'
  return 'info'
}
</script>

<template>
  <div class="cockpit-page">
    <div class="page-head">
      <h3>驾驶舱</h3>
      <el-button :loading="overviewLoading" @click="refreshAll">刷新</el-button>
    </div>

    <el-alert
      v-if="overviewError"
      title="监控总览加载失败"
      type="error"
      :closable="false"
      class="load-error"
    />

    <!-- ① 监控总览卡片 -->
    <div v-loading="overviewLoading" class="overview-grid">
      <!-- LLM 调用聚合 -->
      <el-card shadow="never" class="panel llm-panel">
        <template #header>
          <div class="panel-head">
            <span class="panel-title">LLM 调用</span>
            <el-tag :type="overview?.llm.enabled ? 'success' : 'info'" size="small">
              {{ overview?.llm.enabled ? '已启用' : '未启用（确定性降级）' }}
            </el-tag>
          </div>
        </template>
        <template v-if="overview">
          <div class="stat-grid wide">
            <div class="stat-cell"><span class="stat-num" :title="fmtToken(overview.llm.sumTokens) === '—' ? '确定性模式未计量 token' : undefined">{{ fmtToken(overview.llm.sumTokens) }}</span><span class="stat-label">总 Token</span></div>
            <div class="stat-cell"><span class="stat-num">{{ overview.llm.rounds }}</span><span class="stat-label">提问轮次</span></div>
            <div class="stat-cell"><span class="stat-num">{{ overview.llm.calls }}</span><span class="stat-label">调用</span></div>
            <div class="stat-cell"><span class="stat-num">{{ fmtToken(overview.llm.sumInputTokens) }}</span><span class="stat-label">输入 Token</span></div>
            <div class="stat-cell"><span class="stat-num">{{ fmtToken(overview.llm.sumOutputTokens) }}</span><span class="stat-label">输出 Token</span></div>
            <div class="stat-cell"><span class="stat-num">{{ fmtToken(overview.llm.sumCachedTokens) }}</span><span class="stat-label">缓存命中</span></div>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="模型">{{ overview.llm.modelId ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="平均耗时">{{ overview.llm.avgDurationMs }} ms</el-descriptions-item>
            <el-descriptions-item label="成功">{{ overview.llm.success }}</el-descriptions-item>
            <el-descriptions-item label="降级">{{ overview.llm.fallback }}</el-descriptions-item>
            <el-descriptions-item label="成本">{{ overview.llm.sumCost }}</el-descriptions-item>
            <el-descriptions-item label="Schema 有效">{{ pct(overview.llm.schemaValidRate) }}</el-descriptions-item>
            <el-descriptions-item label="错误率">{{ pct(overview.llm.errorRate) }}</el-descriptions-item>
            <el-descriptions-item label="降级率">{{ pct(overview.llm.fallbackRate) }}</el-descriptions-item>
          </el-descriptions>

          <div class="sub-title">上下文构成 <span class="panel-sub">估算：字符折算，占比分母为构成总和</span></div>
          <div v-if="overview.llm.context" class="context-block">
            <el-table :data="overview.llm.context.categories" size="small" border class="mini-table">
              <el-table-column label="类别" min-width="110">
                <template #default="{ row }">{{ contextLabel(row.key) }}</template>
              </el-table-column>
              <el-table-column prop="entries" label="条目数" width="80" />
              <el-table-column label="Token" width="100">
                <template #default="{ row }">{{ fmtToken(row.tokens) }}</template>
              </el-table-column>
              <el-table-column label="占比" width="90">
                <template #default="{ row }">{{ contextPct(row.tokens) }}</template>
              </el-table-column>
            </el-table>
            <div class="context-summary">共 {{ overview.llm.context.entries }} 条 / {{ fmtToken(overview.llm.context.tokens) }} Token（估算）</div>
          </div>
          <div v-else class="context-empty">暂无对话会话，无上下文构成数据（有聊天记录即展示最近一次转录构成）</div>

          <div class="sub-title">近 7 天趋势</div>
          <el-table :data="overview.llm.trend" size="small" border class="mini-table">
            <el-table-column prop="day" label="日期" width="100" />
            <el-table-column prop="calls" label="调用" width="70" />
            <el-table-column prop="success" label="成功" width="70" />
            <el-table-column label="Token" width="90">
              <template #default="{ row }">{{ fmtToken(row.sumTokens) }}</template>
            </el-table-column>
            <el-table-column prop="sumCost" label="成本" width="90" />
          </el-table>

          <div class="sub-title">按 Agent 类型</div>
          <el-table :data="overview.llm.byAgent" size="small" border class="mini-table">
            <el-table-column prop="name" label="Agent" width="110" />
            <el-table-column label="调/成/降/错" width="130">
              <template #default="{ row }">{{ row.calls }} / {{ row.success }} / {{ row.fallback }} / {{ row.error }}</template>
            </el-table-column>
            <el-table-column label="Token" width="90">
              <template #default="{ row }">{{ fmtToken(row.sumTokens) }}</template>
            </el-table-column>
            <el-table-column prop="avgDurationMs" label="耗时 ms" width="90" />
          </el-table>

          <div class="sub-title">按模型</div>
          <el-table :data="overview.llm.byModel" size="small" border class="mini-table">
            <el-table-column prop="name" label="模型" min-width="140" />
            <el-table-column label="调/成/降/错" width="130">
              <template #default="{ row }">{{ row.calls }} / {{ row.success }} / {{ row.fallback }} / {{ row.error }}</template>
            </el-table-column>
            <el-table-column label="Token" width="90">
              <template #default="{ row }">{{ fmtToken(row.sumTokens) }}</template>
            </el-table-column>
            <el-table-column prop="sumCost" label="成本" width="90" />
          </el-table>
        </template>
      </el-card>

      <!-- 图谱 / 知识库 / 记忆 / Agent 目录 -->
      <div class="side-col">
        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">图谱状态</span>
              <span class="panel-sub">可用 {{ overview?.graph.enabled ?? 0 }} / {{ overview?.graph.total ?? 0 }}</span>
            </div>
          </template>
          <el-table :data="overview?.graph.modules ?? []" size="small" border class="mini-table">
            <el-table-column label="领域" min-width="110">
              <template #default="{ row }">{{ moduleLabel(row.module) }}</template>
            </el-table-column>
            <el-table-column label="启用" width="60">
              <template #default="{ row }">
                <el-tag :type="row.enabled > 0 ? 'success' : 'info'" size="small">{{ row.enabled }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="total" label="总数" width="60" />
          </el-table>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">知识库与记忆</span>
            </div>
          </template>
          <div class="stat-grid">
            <div class="stat-cell"><span class="stat-num">{{ overview?.knowledge.docs ?? 0 }}</span><span class="stat-label">文档</span></div>
            <div class="stat-cell"><span class="stat-num">{{ overview?.knowledge.chunks ?? 0 }}</span><span class="stat-label">切片</span></div>
            <div class="stat-cell"><span class="stat-num">{{ overview?.memory.keys ?? 0 }}</span><span class="stat-label">记忆键</span></div>
          </div>
          <div class="sub-title">Agent 目录（LLM 状态）</div>
          <el-table :data="overview?.agents.byType ?? []" size="small" border class="mini-table">
            <el-table-column prop="name" label="Agent" min-width="130" />
            <el-table-column label="LLM" width="80">
              <template #default="{ row }">
                <el-tag :type="row.llmEnabled ? 'success' : 'info'" size="small">{{ row.llmEnabled ? '启用' : '降级' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="modelId" label="模型" min-width="120" show-overflow-tooltip />
          </el-table>
        </el-card>
      </div>
    </div>

    <!-- ② 洞察 -->
    <el-card shadow="never" class="panel insights-panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">系统洞察</span>
          <div class="panel-actions">
            <span class="panel-sub">生成于 {{ fmtTime(insights?.generatedAt) }} · 缓存 300s</span>
            <el-button size="small" :loading="insightsLoading" @click="forceInsights">强制重新生成</el-button>
          </div>
        </div>
      </template>
      <div v-loading="insightsLoading">
        <div class="health-line">
          <span class="health-label">总体健康度</span>
          <el-progress
            :percentage="insights?.overallHealth ?? 100"
            :color="healthColor"
            :stroke-width="18"
            :text-inside="true"
            style="width: 300px"
          />
        </div>
        <div v-if="(insights?.insights ?? []).length" class="insight-list">
          <div v-for="(ins, i) in insights!.insights" :key="i" class="insight-row">
            <el-tag :type="levelType(ins.level)" size="small" class="insight-level">{{ ins.level }}</el-tag>
            <div class="insight-body">
              <div class="insight-detail">
                <b>{{ ins.dimension }}：</b>{{ ins.detail }}
              </div>
              <div class="insight-suggestion">建议：{{ ins.suggestion }}</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无洞察" :image-size="60" />
      </div>
    </el-card>

    <!-- ③ 图谱管理 -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">图谱管理</span>
          <el-button size="small" type="primary" @click="openCreate">新建登记</el-button>
        </div>
      </template>
      <el-tabs v-model="activeModule" class="module-tabs">
        <el-tab-pane v-for="m in GRAPH_MODULES" :key="m" :label="moduleLabel(m)" :name="m" />
      </el-tabs>
      <el-table v-loading="graphLoading" :data="graphRows ?? []" border stripe size="small">
        <el-table-column prop="entryKey" label="Key" min-width="150" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch
              v-if="row.id != null"
              :model-value="row.status === 'ENABLED'"
              size="small"
              @change="toggleStatus(row)"
            />
            <el-tag v-else :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
              {{ row.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="90" />
        <el-table-column label="来源" width="80">
          <template #default="{ row }">
            <el-tag :type="row.source === 'user' ? 'primary' : 'info'" size="small">
              {{ row.source === 'user' ? '用户' : '内置' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="payload" min-width="160">
          <template #default="{ row }">
            <pre v-if="row.payload !== undefined && row.payload !== null" class="payload-pre">{{ JSON.stringify(row.payload) }}</pre>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <template v-if="row.id != null">
              <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" link type="danger" @click="removeEntry(row)">删除</el-button>
            </template>
            <span v-else class="builtin-hint">内置项</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="该领域暂无登记项" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- ④ LLM 调用追踪 -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">LLM 调用追踪</span>
          <span class="panel-sub">最近 20 条（audit_log）</span>
        </div>
      </template>
      <el-table v-loading="tracesLoading" :data="traces ?? []" border stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="agentType" label="Agent" width="110" />
        <el-table-column prop="action" label="动作" min-width="120" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="130" show-overflow-tooltip />
        <el-table-column prop="tokens" label="Token" width="80" />
        <el-table-column prop="durationMs" label="耗时 ms" width="80" />
        <el-table-column label="Schema" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.schemaValid != null" :type="row.schemaValid ? 'success' : 'danger'" size="small">
              {{ row.schemaValid ? '有效' : '无效' }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无 LLM 调用记录" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 图谱登记对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新建图谱登记' : '编辑图谱登记'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px" size="default">
        <el-form-item label="领域">
          <el-select v-model="form.module" :disabled="editingId != null" style="width: 100%">
            <el-option v-for="m in GRAPH_MODULES" :key="m" :label="moduleLabel(m)" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="Key">
          <el-input v-model="form.entryKey" :disabled="editingId != null" placeholder="如 workflow_dialogue" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="登记项名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-input v-model="form.version" placeholder="如 v1.0" />
        </el-form-item>
        <el-form-item label="Payload">
          <el-input v-model="payloadText" type="textarea" :rows="5" placeholder="JSON 对象（可空）" class="payload-input" />
        </el-form-item>
        <el-form-item v-if="formError">
          <span class="form-error">{{ formError }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="dialogSaving" @click="saveEntry">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cockpit-page {
  padding: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.load-error {
  margin-bottom: 12px;
}
.overview-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  align-items: start;
}
@media (max-width: 1200px) {
  .overview-grid {
    grid-template-columns: 1fr;
  }
}
.side-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel {
  margin-bottom: 16px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.panel-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.panel-title {
  font-weight: 600;
  font-size: 15px;
}
.panel-sub {
  color: #909399;
  font-size: 12px;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}
.stat-grid.wide {
  grid-template-columns: repeat(6, 1fr);
}
.context-block {
  margin-bottom: 4px;
}
.context-summary {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
.context-empty {
  padding: 14px 0;
  font-size: 13px;
  color: var(--el-text-color-secondary, #909399);
}
.stat-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 8px 4px;
  background: #f5f7fa;
  border-radius: 4px;
}
.stat-num {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
}
.stat-num.success {
  color: #67c23a;
}
.stat-num.warning {
  color: #e6a23c;
}
.stat-num.danger {
  color: #f56c6c;
}
.stat-label {
  font-size: 12px;
  color: #909399;
}
.sub-title {
  margin: 14px 0 8px;
  font-size: 13px;
  color: var(--el-text-color-primary, #303133);
}
.mini-table {
  margin-bottom: 4px;
}
.insights-panel {
  margin-bottom: 16px;
}
.health-line {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 14px;
}
.health-label {
  font-size: 13px;
  color: #606266;
}
.insight-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.insight-row {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  background: #f5f7fa;
  border-radius: 4px;
}
.insight-level {
  flex-shrink: 0;
  text-transform: uppercase;
}
.insight-detail {
  font-size: 13px;
  color: #303133;
}
.insight-suggestion {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}
.module-tabs {
  margin-top: -6px;
}
.payload-pre {
  margin: 0;
  font-size: 11px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 90px;
  overflow-y: auto;
}
.builtin-hint {
  color: #909399;
  font-size: 12px;
}
.form-error {
  color: #f56c6c;
  font-size: 12px;
}
.payload-input :deep(textarea) {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
</style>