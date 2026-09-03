<script setup lang="ts">
/**
 * 评测中心（M8）：数据集管理 + 用例管理 + 15 内置评测器目录 + 批量运行（openjudge/execute）+ 报告回看。
 * execute 模式真实运行被测智能体（assistant / workflow-dialogue）并做执行维度评测。
 * 数据：GET/POST/PUT/DELETE /api/evaluations/{datasets,cases,run,reports}。
 */
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  EVALUATOR_CATALOG,
  addCase,
  createDataset,
  deleteCase,
  deleteDataset,
  deleteReport,
  listCases,
  listDatasets,
  listReports,
  runEvaluation,
  updateCase,
  updateDataset,
} from '../api/evaluation'
import type { CaseSaveRequest, CaseView, DatasetSaveRequest, DatasetView, ReportView } from '../api/types'

const { data: datasets, isLoading: datasetsLoading, isError: datasetsError, refetch: refetchDatasets } =
  useQuery<DatasetView[]>({
    queryKey: ['eval-datasets'],
    queryFn: listDatasets,
  })
const { data: reports, isLoading: reportsLoading, refetch: refetchReports } = useQuery<ReportView[]>({
  queryKey: ['eval-reports'],
  queryFn: listReports,
})

async function refreshAll() {
  try {
    await refetchDatasets()
    await refetchReports()
  } catch {
    ElMessage.error('刷新失败')
  }
}

/* ---------- 数据集 ---------- */
const datasetDialog = ref(false)
const datasetSaving = ref(false)
const editingDatasetId = ref<number | null>(null)
const datasetForm = reactive<DatasetSaveRequest>({
  name: '',
  description: '',
  scope: 'llm_call',
  mode: 'openjudge',
  agentType: 'assistant',
  status: 'ENABLED',
})

function openDatasetCreate() {
  editingDatasetId.value = null
  datasetForm.name = ''
  datasetForm.description = ''
  datasetForm.scope = 'llm_call'
  datasetForm.mode = 'openjudge'
  datasetForm.agentType = 'assistant'
  datasetForm.status = 'ENABLED'
  datasetDialog.value = true
}

function openDatasetEdit(row: DatasetView) {
  editingDatasetId.value = row.id
  datasetForm.name = row.name
  datasetForm.description = row.description ?? ''
  datasetForm.scope = row.scope
  datasetForm.mode = row.mode
  datasetForm.agentType = row.agentType ?? 'assistant'
  datasetForm.status = row.status
  datasetDialog.value = true
}

async function saveDataset() {
  if (!datasetForm.name.trim()) {
    ElMessage.warning('数据集名称不能为空')
    return
  }
  datasetSaving.value = true
  try {
    if (editingDatasetId.value == null) {
      await createDataset({ ...datasetForm })
      ElMessage.success('数据集已创建')
    } else {
      await updateDataset(editingDatasetId.value, { ...datasetForm })
      ElMessage.success('数据集已保存')
    }
    datasetDialog.value = false
    await refetchDatasets()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    datasetSaving.value = false
  }
}

async function removeDataset(row: DatasetView) {
  try {
    await ElMessageBox.confirm(`确认删除数据集「${row.name}」？其用例与报告将一并删除。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDataset(row.id)
    ElMessage.success('已删除')
    if (casesDatasetId.value === row.id) casesDatasetId.value = null
    await refetchAllQueries()
  } catch {
    ElMessage.error('删除失败')
  }
}

async function refetchAllQueries() {
  await refetchDatasets()
  await refetchReports()
}

/* ---------- 数据集状态开关 ---------- */
async function toggleDatasetStatus(row: DatasetView) {
  try {
    await updateDataset(row.id, {
      name: row.name,
      description: row.description ?? '',
      scope: row.scope,
      mode: row.mode,
      agentType: row.agentType,
      status: row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED',
    })
    ElMessage.success(row.status === 'ENABLED' ? '已停用' : '已启用')
    await refetchDatasets()
  } catch {
    ElMessage.error('状态更新失败')
  }
}

/* ---------- 用例管理 ---------- */
const casesDatasetId = ref<number | null>(null)
const casesDrawer = ref(false)
const casesLoading = ref(false)
const cases = ref<CaseView[]>([])

async function openCasesDrawer(row: DatasetView) {
  casesDatasetId.value = row.id
  cases.value = []
  casesDrawer.value = true
  casesLoading.value = true
  try {
    cases.value = await listCases(row.id)
  } catch {
    ElMessage.error('用例列表加载失败')
  } finally {
    casesLoading.value = false
  }
}

const caseDialog = ref(false)
const caseSaving = ref(false)
const editingCaseId = ref<number | null>(null)
const caseForm = reactive<CaseSaveRequest>({
  seq: undefined,
  question: '',
  systemPrompt: '',
  expectedOutput: undefined,
  toolSchema: undefined,
  expectedTool: undefined,
  expectedSteps: 1,
  expectedPolicy: undefined,
  providedResponse: '',
})
const expectedText = ref('')
const toolSchemaText = ref('')
const expectedToolText = ref('')
const expectedPolicyText = ref('')

function openCaseCreate() {
  editingCaseId.value = null
  caseForm.seq = undefined
  caseForm.question = ''
  caseForm.systemPrompt = ''
  caseForm.expectedSteps = 1
  caseForm.providedResponse = ''
  expectedText.value = ''
  toolSchemaText.value = ''
  expectedToolText.value = ''
  expectedPolicyText.value = ''
  caseDialog.value = true
}

function openCaseEdit(c: CaseView) {
  editingCaseId.value = c.id
  caseForm.seq = c.seq ?? undefined
  caseForm.question = c.question
  caseForm.systemPrompt = c.systemPrompt ?? ''
  caseForm.expectedSteps = c.expectedSteps ?? 1
  caseForm.providedResponse = c.providedResponse ?? ''
  expectedText.value = c.expectedOutput ? JSON.stringify(c.expectedOutput, null, 2) : ''
  toolSchemaText.value = c.toolSchema ? JSON.stringify(c.toolSchema, null, 2) : ''
  expectedToolText.value = c.expectedTool ? JSON.stringify(c.expectedTool, null, 2) : ''
  expectedPolicyText.value = c.expectedPolicy ? JSON.stringify(c.expectedPolicy, null, 2) : ''
  caseDialog.value = true
}

async function saveCase() {
  if (!caseForm.question.trim()) {
    ElMessage.warning('问题不能为空')
    return
  }
  const jsonOf = (text: string, field: string): Record<string, unknown> | undefined => {
    if (!text.trim()) return undefined
    try {
      return JSON.parse(text)
    } catch {
      throw new Error(`${field} 不是合法 JSON`)
    }
  }
  try {
    caseForm.expectedOutput = jsonOf(expectedText.value, '期望输出')
    caseForm.toolSchema = jsonOf(toolSchemaText.value, '工具 Schema')
    caseForm.expectedTool = jsonOf(expectedToolText.value, '期望工具')
    caseForm.expectedPolicy = jsonOf(expectedPolicyText.value, '期望策略')
  } catch (e) {
    ElMessage.warning((e as Error).message)
    return
  }
  caseSaving.value = true
  try {
    if (editingCaseId.value == null) {
      await addCase(casesDatasetId.value!, { ...caseForm })
    } else {
      await updateCase(editingCaseId.value, { ...caseForm })
    }
    ElMessage.success(editingCaseId.value == null ? '用例已添加' : '用例已保存')
    caseDialog.value = false
    cases.value = await listCases(casesDatasetId.value!)
  } catch {
    ElMessage.error('保存失败')
  } finally {
    caseSaving.value = false
  }
}

async function removeCase(c: CaseView) {
  try {
    await ElMessageBox.confirm(`确认删除用例 #${c.seq ?? c.id}？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteCase(c.id)
    ElMessage.success('已删除')
    cases.value = await listCases(casesDatasetId.value!)
  } catch {
    ElMessage.error('删除失败')
  }
}

/* ---------- 评测运行 ---------- */
const runDatasetId = ref<number | undefined>(undefined)
const runEvaluators = ref<string[]>(EVALUATOR_CATALOG.map((e) => e.metric))
const running = ref(false)
const lastReport = ref<ReportView | null>(null)

const runnableDatasets = computed(() =>
  (datasets.value ?? []).filter((d) => d.status === 'ENABLED' && d.caseCount > 0),
)

async function doRun() {
  if (runDatasetId.value == null) {
    ElMessage.warning('请选择数据集')
    return
  }
  running.value = true
  lastReport.value = null
  try {
    lastReport.value = await runEvaluation({
      datasetId: runDatasetId.value,
      evaluators: runEvaluators.value,
    })
    ElMessage.success('评测完成，报告已生成')
    await refetchReports()
  } catch {
    ElMessage.error('评测运行失败')
  } finally {
    running.value = false
  }
}

const verdictType = (v: string): 'success' | 'warning' | 'danger' => {
  if (v === 'PASS') return 'success'
  if (v === 'WARN') return 'warning'
  return 'danger'
}

/* ---------- 报告回看 ---------- */
const reportDetailVisible = ref(false)
const reportDetail = ref<ReportView | null>(null)

async function openReportDetail(row: ReportView) {
  reportDetail.value = row
  reportDetailVisible.value = true
}

async function removeReport(row: ReportView) {
  try {
    await ElMessageBox.confirm(`确认删除报告「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteReport(row.id)
    ElMessage.success('已删除')
    await refetchReports()
  } catch {
    ElMessage.error('删除失败')
  }
}

const fmtTime = (iso: string | null | undefined): string => {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const jsonText = (v: unknown): string => (v === undefined || v === null ? '-' : JSON.stringify(v))
</script>

<template>
  <div class="eval-page">
    <div class="page-head">
      <h3>评测中心</h3>
      <div class="controls">
        <el-button :loading="datasetsLoading" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <el-alert v-if="datasetsError" title="数据集加载失败" type="error" :closable="false" class="load-error" />

    <!-- ① 数据集 -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">数据集</span>
          <el-button size="small" type="primary" @click="openDatasetCreate">新建数据集</el-button>
        </div>
      </template>
      <el-table v-loading="datasetsLoading" :data="datasets ?? []" border stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column prop="scope" label="场景" width="90">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.scope }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模式" width="100">
          <template #default="{ row }">
            <el-tag :type="row.mode === 'execute' ? 'warning' : 'success'" size="small">{{ row.mode }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="被测智能体" width="120">
          <template #default="{ row }">
            <template v-if="row.mode === 'execute'">
              {{ row.agentType === 'workflow-dialogue' ? '工作流对话' : 'Assistant' }}
            </template>
            <template v-else>-</template>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 'ENABLED'"
              size="small"
              @change="toggleDatasetStatus(row)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="caseCount" label="用例数" width="80" />
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openCasesDrawer(row)">用例</el-button>
            <el-button size="small" link type="primary" @click="openDatasetEdit(row)">编辑</el-button>
            <el-button size="small" link type="danger" @click="removeDataset(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无数据集，先新建一个" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- ② 内置评测器目录 -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">内置评测器目录</span>
          <span class="panel-sub">15 个：规则 9 + LLM-Judge 6（参考业界 AI agent 评测基准；LLM 未启用时确定性近似）</span>
        </div>
      </template>
      <el-table :data="EVALUATOR_CATALOG" border stripe size="small">
        <el-table-column label="评测器" width="220">
          <template #default="{ row }">
            <code class="metric-code">{{ row.metric }}</code>
          </template>
        </el-table-column>
        <el-table-column label="类别" width="110">
          <template #default="{ row }">
            <el-tag :type="row.category === 'rule' ? 'success' : 'primary'" size="small">
              {{ row.category === 'rule' ? '规则' : 'LLM-Judge' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="label" label="名称" width="140" />
        <el-table-column prop="description" label="说明" min-width="240" show-overflow-tooltip />
        <el-table-column prop="origin" label="参考基准" min-width="180" show-overflow-tooltip>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ③ 批量运行 + 结果 -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">批量运行评测</span>
          <span class="panel-sub">openjudge 以预置响应判分；execute 真实运行被测智能体（assistant / workflow-dialogue）</span>
        </div>
      </template>
      <div class="run-form">
        <el-select
          v-model="runDatasetId"
          placeholder="选择数据集（已启用 / 有用例）"
          clearable
          filterable
          style="width: 320px"
        >
          <el-option v-for="d in runnableDatasets" :key="d.id" :label="`${d.name}（${d.caseCount} 例）`" :value="d.id" />
        </el-select>
        <el-select
          v-model="runEvaluators"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="评测器（缺省全量 15 个）"
          style="width: 420px"
        >
          <el-option v-for="e in EVALUATOR_CATALOG" :key="e.metric" :label="e.label" :value="e.metric" />
        </el-select>
        <el-button type="primary" :loading="running" @click="doRun">运行评测</el-button>
      </div>

      <template v-if="lastReport">
        <div class="result-head">
          <el-tag :type="verdictType(lastReport.summary.verdict)" size="small">总分 {{ lastReport.summary.score }}</el-tag>
          <span class="result-title">评测结果：{{ lastReport.summary.verdict }}</span>
          <span class="panel-sub">测试 {{ lastReport.testedCases }}/{{ lastReport.totalCases }} 例 · 模型 {{ lastReport.model }} · 置信度 {{ lastReport.confidence }}</span>
        </div>
        <el-table :data="lastReport.metrics" size="small" border class="mini-table">
          <el-table-column label="评测器" width="220">
            <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
          </el-table-column>
          <el-table-column label="类别" width="110">
            <template #default="{ row }">
              <el-tag :type="row.category === 'rule' ? 'success' : 'primary'" size="small">{{ row.category }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="均值" width="120">
            <template #default="{ row }">
              <el-progress :percentage="Math.round(row.avg_score * 100)" :stroke-width="10" />
            </template>
          </el-table-column>
          <el-table-column label="通过" width="120">
            <template #default="{ row }">{{ row.passed_count }} / {{ row.applicable_count }}</template>
          </el-table-column>
        </el-table>
        <div v-if="lastReport.findings.length" class="findings">
          <div v-for="(f, i) in lastReport.findings" :key="i" class="finding-row">
            <el-tag :type="verdictType(f.level === 'BLOCKED' ? 'FAIL' : f.level === 'WARNING' ? 'WARN' : 'PASS')" size="small">
              {{ f.level }}
            </el-tag>
            <span class="finding-detail">{{ f.detail }}</span>
            <span v-if="f.suggestion" class="finding-suggestion">（{{ f.suggestion }}）</span>
          </div>
        </div>
      </template>
      <el-empty v-else description="尚未运行评测" :image-size="60" />
    </el-card>

    <!-- ④ 报告列表 -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">评测报告</span>
          <span class="panel-sub">落库报告（审计驱动生成）</span>
        </div>
      </template>
      <el-table v-loading="reportsLoading" :data="reports ?? []" border stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="数据集" min-width="150" show-overflow-tooltip />
        <el-table-column label="结论" width="90">
          <template #default="{ row }">
            <el-tag :type="verdictType(row.summary.verdict)" size="small">{{ row.summary.verdict }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="总分" width="80">
          <template #default="{ row }">{{ row.summary.score }}</template>
        </el-table-column>
        <el-table-column label="用例" width="110">
          <template #default="{ row }">{{ row.testedCases }}/{{ row.totalCases }}</template>
        </el-table-column>
        <el-table-column prop="model" label="模型" min-width="130" show-overflow-tooltip />
        <el-table-column prop="mode" label="模式" width="100" />
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openReportDetail(row)">详情</el-button>
            <el-button size="small" link type="danger" @click="removeReport(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无评测报告" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 数据集对话框 -->
    <el-dialog
      v-model="datasetDialog"
      :title="editingDatasetId == null ? '新建数据集' : '编辑数据集'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="datasetForm.name" placeholder="如：客服话术质量集" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="datasetForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="场景">
          <el-select v-model="datasetForm.scope" :disabled="editingDatasetId != null" style="width: 100%">
            <el-option label="LLM 调用评测" value="llm_call" />
          </el-select>
        </el-form-item>
        <el-form-item label="模式">
          <el-select v-model="datasetForm.mode" style="width: 100%">
            <el-option label="openjudge（预置响应判分）" value="openjudge" />
            <el-option label="execute（真实运行被测智能体）" value="execute" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="datasetForm.mode === 'execute'" label="被测智能体">
          <el-select v-model="datasetForm.agentType" style="width: 100%">
            <el-option label="Assistant（智能助理）" value="assistant" />
            <el-option label="Workflow Dialogue（工作流对话）" value="workflow-dialogue" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="datasetForm.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="datasetDialog = false">取消</el-button>
        <el-button type="primary" :loading="datasetSaving" @click="saveDataset">保存</el-button>
      </template>
    </el-dialog>

    <!-- 用例抽屉 -->
    <el-drawer v-model="casesDrawer" :title="`用例管理`" size="720px">
      <div class="drawer-head">
        <span class="drawer-title">{{ casesDatasetId != null ? `数据集 #${casesDatasetId}` : '' }}</span>
        <el-button size="small" type="primary" @click="openCaseCreate">新增用例</el-button>
      </div>
      <div v-loading="casesLoading">
        <div v-if="cases.length">
          <div v-for="c in cases" :key="c.id" class="case-row">
            <div class="case-row-head">
              <el-tag size="small" type="info">#{{ c.seq ?? c.id }}</el-tag>
              <span class="case-question">{{ c.question }}</span>
              <span class="case-actions">
                <el-button size="small" link type="primary" @click="openCaseEdit(c)">编辑</el-button>
                <el-button size="small" link type="danger" @click="removeCase(c)">删除</el-button>
              </span>
            </div>
            <div v-if="c.providedResponse" class="case-meta">预置响应：{{ c.providedResponse }}</div>
            <div v-if="c.expectedOutput" class="case-meta">期望输出：{{ jsonText(c.expectedOutput) }}</div>
            <div v-if="c.expectedTool" class="case-meta">期望工具：{{ jsonText(c.expectedTool) }}</div>
            <div v-if="c.expectedSteps != null" class="case-meta">期望步数：{{ c.expectedSteps }}</div>
            <div v-if="c.expectedPolicy" class="case-meta">期望策略：{{ jsonText(c.expectedPolicy) }}</div>
          </div>
        </div>
        <el-empty v-else-if="!casesLoading" description="该数据集暂无用例" :image-size="60" />
      </div>
    </el-drawer>

    <!-- 用例对话框 -->
    <el-dialog
      v-model="caseDialog"
      :title="editingCaseId == null ? '新增用例' : '编辑用例'"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form label-width="90px">
        <el-form-item label="序号">
          <el-input-number v-model="caseForm.seq" :min="1" placeholder="缺省 = 追加到末尾" />
        </el-form-item>
        <el-form-item label="问题">
          <el-input v-model="caseForm.question" type="textarea" :rows="2" placeholder="用户问题（必填）" />
        </el-form-item>
        <el-form-item label="系统提示">
          <el-input v-model="caseForm.systemPrompt" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="期望输出">
          <el-input v-model="expectedText" type="textarea" :rows="4" placeholder='JSON（可空）：如 { "answer": "42" }' class="json-input" />
        </el-form-item>
        <el-form-item label="工具 Schema">
          <el-input v-model="toolSchemaText" type="textarea" :rows="3" placeholder="JSON（可空）" class="json-input" />
        </el-form-item>
        <el-form-item label="期望工具">
          <el-input v-model="expectedToolText" type="textarea" :rows="2" placeholder='JSON（可空）：如 { "name": "search_kb" }' class="json-input" />
        </el-form-item>
        <el-form-item label="期望步数">
          <el-input-number v-model="caseForm.expectedSteps" :min="1" style="width: 140px" />
          <span class="form-hint">step_efficiency 基准（工具调用步 + 最终回复步，缺省 1）</span>
        </el-form-item>
        <el-form-item label="期望策略">
          <el-input v-model="expectedPolicyText" type="textarea" :rows="3" placeholder='JSON（可空）：如 [{ "keyword": "确认后下发", "prohibit": false }]' class="json-input" />
        </el-form-item>
        <el-form-item label="预置响应">
          <el-input v-model="caseForm.providedResponse" type="textarea" :rows="3" placeholder="openjudge 判分对象（可空 = 不适用）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDialog = false">取消</el-button>
        <el-button type="primary" :loading="caseSaving" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>

    <!-- 报告详情抽屉 -->
    <el-drawer v-model="reportDetailVisible" :title="`报告详情`" size="640px">
      <template v-if="reportDetail">
        <div class="drawer-head">
          <el-tag :type="verdictType(reportDetail.summary.verdict)" size="small">{{ reportDetail.summary.verdict }}</el-tag>
          <span class="drawer-title">总分 {{ reportDetail.summary.score }} · 测试 {{ reportDetail.testedCases }}/{{ reportDetail.totalCases }} 例</span>
        </div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="数据集">#{{ reportDetail.datasetId }} {{ reportDetail.name }}</el-descriptions-item>
          <el-descriptions-item label="模式">{{ reportDetail.mode }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ reportDetail.model ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="置信度">{{ reportDetail.confidence }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ reportDetail.createdBy }}</el-descriptions-item>
          <el-descriptions-item label="时间">{{ fmtTime(reportDetail.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="drawer-title">指标均值</h4>
        <el-table :data="reportDetail.metrics" size="small" border>
          <el-table-column label="评测器" width="220">
            <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
          </el-table-column>
          <el-table-column label="均值" width="150">
            <template #default="{ row }">
              <el-progress :percentage="Math.round(row.avg_score * 100)" :stroke-width="10" />
            </template>
          </el-table-column>
          <el-table-column label="通过/适用">
            <template #default="{ row }">{{ row.passed_count }} / {{ row.applicable_count }}</template>
          </el-table-column>
        </el-table>
        <h4 class="drawer-title">发现</h4>
        <div v-if="reportDetail.findings.length" class="findings">
          <div v-for="(f, i) in reportDetail.findings" :key="i" class="finding-row">
            <el-tag :type="verdictType(f.level === 'BLOCKED' ? 'FAIL' : f.level === 'WARNING' ? 'WARN' : 'PASS')" size="small">
              {{ f.level }}
            </el-tag>
            <span class="finding-detail">{{ f.detail }}</span>
            <span v-if="f.suggestion" class="finding-suggestion">（{{ f.suggestion }}）</span>
          </div>
        </div>
        <el-empty v-else description="无发现（全部通过或无适用用例）" :image-size="60" />
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.eval-page {
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
.load-error {
  margin-bottom: 12px;
}
.panel {
  margin-bottom: 16px;
}
.panel-head {
  display: flex;
  align-items: center;
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
.run-form {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.result-title {
  font-weight: 600;
  font-size: 14px;
}
.mini-table {
  margin-bottom: 10px;
}
.findings {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.finding-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
}
.finding-detail {
  color: #303133;
}
.finding-suggestion {
  color: #909399;
  font-size: 12px;
}
.metric-code {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  background: #f0f2f5;
  padding: 1px 6px;
  border-radius: 3px;
}
.drawer-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.drawer-title {
  font-weight: 600;
  font-size: 14px;
}
.case-row {
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #f5f7fa;
  border-radius: 4px;
}
.case-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.case-question {
  flex: 1;
  font-size: 13px;
  color: #303133;
  word-break: break-all;
}
.case-actions {
  flex-shrink: 0;
}
.case-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}
.json-input :deep(textarea) {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.form-hint {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
</style>