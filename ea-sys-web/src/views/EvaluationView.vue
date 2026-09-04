<script setup lang="ts">
/**
 * 评测中心（M8）：数据集管理 + jsonl 导入/预览 + 用例管理 + 三组评测器面板（规则/LLM-Judge/自定义，
 * 每组全选/清空）+ 异步任务运行（POST /tasks 202 立即返回 → ~3s 轮询进度，状态徽章/进度条，
 * COMPLETED 自动并入结果区，FAILED 错误横幅）+ 运行任务列表（状态徽章/进度/查看结果/取消）+
 * 逐样本判分抽屉（score 百分比 + ✓/✗ + LLM reason）+ 报告基线回归对比（红绿 delta）+ 报告回看
 * （TraceID 联动驾驶舱）。execute 模式真实运行被测智能体（assistant / workflow-dialogue）。
 * 数据：GET/POST/PUT/DELETE /api/evaluations/{datasets,cases,import,custom-evaluators,tasks,reports}。
 * M8 重构（P0-P4）：分层分布列 + 数据集版本化（发布快照/版本用例/删除）+ 运行绑定版本 + 用例分层/
 * judge_rule/dialogue 编辑 + 样本抽屉执行轨迹（transcript + latencyMs）+ 报告详情（上线建议/分层统计/
 * 执行统计/Top 退化样例/复现）+ 人工复评/校准抽屉 + 对比分层与 Top 退化样例 + 数据集维度聚合看板。
 * 新端点：versions/transcript/reviews/calibration/rerun/dashboard；旧后端缺省字段一律 ?? '-' 兜底。
 */
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  EVALUATOR_CATALOG,
  addCase,
  cancelTask,
  compareReport,
  createCustomEvaluator,
  createDataset,
  createTask,
  deleteCase,
  deleteCustomEvaluator,
  deleteDataset,
  deleteDatasetVersion,
  deleteHumanReview,
  deleteReport,
  getCalibration,
  getDashboard,
  getDatasetVersionCases,
  getReport,
  getReportTranscript,
  getTask,
  getTaskTranscript,
  importCases,
  listCases,
  listCustomEvaluators,
  listDatasetVersions,
  listDatasets,
  listHumanReviews,
  listReports,
  listTasks,
  publishDatasetVersion,
  rerunReport,
  submitHumanReview,
  updateCase,
  updateCustomEvaluator,
  updateDataset,
} from '../api/evaluation'
import type {
  CalibrationView,
  CaseCategory,
  CaseSaveRequest,
  CaseView,
  CustomEvaluatorView,
  CustomSaveRequest,
  DashboardView,
  DatasetSaveRequest,
  DatasetVersionView,
  DatasetView,
  HumanReviewSaveRequest,
  HumanReviewView,
  ImportResultView,
  ReportCompareView,
  ReportExecutionView,
  ReportView,
  TaskView,
  TranscriptTurnView,
} from '../api/types'

const router = useRouter()

const { data: datasets, isLoading: datasetsLoading, isError: datasetsError, refetch: refetchDatasets } =
  useQuery<DatasetView[]>({
    queryKey: ['eval-datasets'],
    queryFn: listDatasets,
  })
const { data: reports, isLoading: reportsLoading, refetch: refetchReports } = useQuery<ReportView[]>({
  queryKey: ['eval-reports'],
  queryFn: listReports,
})
const { data: tasks, isLoading: tasksLoading, refetch: refetchTasks } = useQuery<TaskView[]>({
  queryKey: ['eval-tasks'],
  queryFn: listTasks,
  // 存在未终态任务时每 3s 自动刷新任务列表，全部终态后停止轮询
  refetchInterval: (query) => {
    const rows = query.state.data as TaskView[] | undefined
    return rows && rows.some((t) => t.status === 'PENDING' || t.status === 'RUNNING' || t.status === 'CANCELING')
      ? 3000
      : false
  },
})

async function refreshAll() {
  try {
    await refetchDatasets()
    await refetchReports()
    await refetchTasks()
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
  await refetchTasks()
}

/* ---------- 分层（40/30/30 目标） ---------- */
const CATEGORY_LABEL: Record<CaseCategory, string> = { basic: '基础', edge: '边界', real: '真实' }
const CATEGORY_COLOR: Record<CaseCategory, string> = { basic: '#67c23a', edge: '#e6a23c', real: '#409eff' }
const CATEGORY_TARGET: Record<CaseCategory, number> = { basic: 0.4, edge: 0.3, real: 0.3 }
const CATEGORY_ORDER: CaseCategory[] = ['basic', 'edge', 'real']

/** 数据集分层分布（caseCountByCategory 缺省 → 空数组，UI 显示 '-'） */
function categoryTiers(d: DatasetView | null | undefined): Array<{ key: CaseCategory; label: string; color: string; count: number; pct: number; hit: boolean }> {
  const c = d?.caseCountByCategory ?? null
  const total = c ? c.basic + c.edge + c.real : 0
  return CATEGORY_ORDER.map((k) => {
    const count = c ? c[k] ?? 0 : 0
    return {
      key: k,
      label: CATEGORY_LABEL[k],
      color: CATEGORY_COLOR[k],
      count,
      pct: total > 0 ? count / total : 0,
      hit: total > 0 && count / total >= CATEGORY_TARGET[k],
    }
  })
}

/* ---------- 数据集版本化（发布 / 快照用例 / 删除） ---------- */
const versionDialog = ref(false)
const versionDatasetId = ref<number | null>(null)
const versionRows = ref<DatasetVersionView[]>([])
const versionsLoading = ref(false)
const publishing = ref(false)

async function refetchVersions() {
  if (versionDatasetId.value == null) return
  versionsLoading.value = true
  try {
    versionRows.value = await listDatasetVersions(versionDatasetId.value)
  } catch {
    ElMessage.error('版本列表加载失败')
  } finally {
    versionsLoading.value = false
  }
}

function openVersionDialog(row: DatasetView) {
  versionDatasetId.value = row.id
  versionRows.value = []
  versionDialog.value = true
  void refetchVersions()
}

async function publishVersion() {
  if (versionDatasetId.value == null) return
  publishing.value = true
  try {
    const v = await publishDatasetVersion(versionDatasetId.value)
    ElMessage.success(`已发布 v${v.versionNo}（${v.caseCount} 例 · 不可变快照）`)
    await refetchVersions()
    await refetchDatasets()
  } catch (e) {
    ElMessage.error((e as Error).message || '发布失败（用例为空或存在未保存改动时不可发布）')
  } finally {
    publishing.value = false
  }
}

async function removeVersion(v: DatasetVersionView) {
  if (versionDatasetId.value == null) return
  try {
    await ElMessageBox.confirm(`确认删除版本 v${v.versionNo}？被报告/任务引用的版本不可删除。`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDatasetVersion(versionDatasetId.value, v.id)
    ElMessage.success('已删除')
    await refetchVersions()
    await refetchDatasets()
  } catch (e) {
    ElMessage.error((e as Error).message || '删除失败')
  }
}

/* 版本快照用例（只读抽屉） */
const versionCasesDrawer = ref(false)
const versionCasesLoading = ref(false)
const versionCases = ref<CaseView[]>([])
const versionCasesMeta = ref('')

async function viewVersionCases(v: DatasetVersionView) {
  if (versionDatasetId.value == null) return
  versionCases.value = []
  versionCasesMeta.value = `数据集 #${versionDatasetId.value} · v${v.versionNo} · ${v.caseCount} 例（不可变快照）`
  versionCasesDrawer.value = true
  versionCasesLoading.value = true
  try {
    versionCases.value = await getDatasetVersionCases(versionDatasetId.value, v.id)
  } catch {
    ElMessage.error('版本用例加载失败')
  } finally {
    versionCasesLoading.value = false
  }
}

/* ---------- 评测看板（数据集维度） ---------- */
const dashboardDatasetId = ref<number | undefined>(undefined)
const { data: dashboard, isLoading: dashboardLoading, refetch: refetchDashboard } = useQuery<DashboardView>({
  queryKey: ['eval-dashboard', dashboardDatasetId],
  queryFn: () => getDashboard(dashboardDatasetId.value!, 12),
  enabled: () => dashboardDatasetId.value != null,
})
watch(datasets, (rows) => {
  if (dashboardDatasetId.value == null && rows && rows.length) dashboardDatasetId.value = rows[0].id
}, { immediate: true })

/** 看板分层分布（结构与数据集列一致，含通过率） */
const dashLayerTiers = computed(() => {
  const l = dashboard.value?.layering ?? null
  const total = l ? (l.basic?.count ?? 0) + (l.edge?.count ?? 0) + (l.real?.count ?? 0) : 0
  return CATEGORY_ORDER.map((k) => {
    const s = l ? l[k] : null
    const count = s?.count ?? 0
    return {
      key: k,
      label: CATEGORY_LABEL[k],
      color: CATEGORY_COLOR[k],
      count,
      passRate: s?.pass_rate ?? null,
      pct: total > 0 ? count / total : 0,
      hit: total > 0 && count / total >= CATEGORY_TARGET[k],
    }
  })
})
/** 看板指标均值表行：由后端 {series, latest, delta} 对象派生（series 顺序即展示顺序） */
const dashMetricRows = computed(() => {
  const m = dashboard.value?.metrics
  if (!m) return []
  return (m.series ?? []).map((s) => ({
    metric: s.metric,
    latest: m.latest?.[s.metric] ?? null,
    delta: m.delta?.[s.metric] ?? null,
  }))
})
const dashMs = (v: number | null | undefined): string => (v == null ? '—' : `${v} ms`)

async function openDashboardReport(row: { id: number }) {
  try {
    const r = await getReport(row.id)
    reportDetail.value = r
    reportDetailVisible.value = true
  } catch {
    ElMessage.error('报告加载失败')
  }
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

/** 用例抽屉当前数据集（execute 专属字段随其显隐/可用） */
const casesDataset = computed(() => (datasets.value ?? []).find((d) => d.id === casesDatasetId.value) ?? null)
const isExecuteCasesDataset = computed(() => casesDataset.value?.mode === 'execute')

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
  expectedKbHits: undefined,
  providedResponse: '',
  category: 'basic',
})
const expectedText = ref('')
const toolSchemaText = ref('')
const expectedToolText = ref('')
const expectedPolicyText = ref('')
/** rag_hit_rate 期望知识命中片段：textarea 每行一条，保存转数组、回显按行展开 */
const expectedKbHitsText = ref('')
/** 逐用例判分规则 JSON（可空；对象或数组） */
const judgeRuleText = ref('')
/** 多轮对话编辑行（单轮 = 空数组；question 即首轮） */
const dialogueRows = ref<Array<{ role: 'user' | 'assistant'; content: string; toolUseText: string; toolResultText: string }>>([])

/** 当前用例数据集三档分布（实时按抽屉已加载用例统计；百分比文本供表单提示） */
const caseCategoryDist = computed<Array<{ key: CaseCategory; label: string; count: number; pct: number }>>(() => {
  const rows = cases.value
  const total = rows.length
  if (!total) return []
  const counts: Record<CaseCategory, number> = { basic: 0, edge: 0, real: 0 }
  for (const c of rows) {
    const cat: CaseCategory = c.category ?? 'basic'
    counts[cat] += 1
  }
  return CATEGORY_ORDER.map((k) => ({ key: k, label: CATEGORY_LABEL[k], count: counts[k], pct: Math.round((counts[k] / total) * 100) }))
})
const caseCategoryType = (c?: CaseCategory | null): 'success' | 'warning' | 'primary' => {
  if (c === 'edge') return 'warning'
  if (c === 'real') return 'primary'
  return 'success'
}

function addDialogueRow() {
  dialogueRows.value.push({ role: 'user', content: '', toolUseText: '', toolResultText: '' })
}
function removeDialogueRow(i: number) {
  dialogueRows.value.splice(i, 1)
}

function openCaseCreate() {
  editingCaseId.value = null
  caseForm.seq = undefined
  caseForm.question = ''
  caseForm.systemPrompt = ''
  caseForm.expectedSteps = 1
  caseForm.providedResponse = ''
  caseForm.category = 'basic'
  expectedText.value = ''
  toolSchemaText.value = ''
  expectedToolText.value = ''
  expectedPolicyText.value = ''
  expectedKbHitsText.value = ''
  judgeRuleText.value = ''
  dialogueRows.value = []
  caseDialog.value = true
}

function openCaseEdit(c: CaseView) {
  editingCaseId.value = c.id
  caseForm.seq = c.seq ?? undefined
  caseForm.question = c.question
  caseForm.systemPrompt = c.systemPrompt ?? ''
  caseForm.expectedSteps = c.expectedSteps ?? 1
  caseForm.providedResponse = c.providedResponse ?? ''
  caseForm.category = c.category ?? 'basic'
  expectedText.value = c.expectedOutput ? JSON.stringify(c.expectedOutput, null, 2) : ''
  toolSchemaText.value = c.toolSchema ? JSON.stringify(c.toolSchema, null, 2) : ''
  expectedToolText.value = c.expectedTool ? JSON.stringify(c.expectedTool, null, 2) : ''
  expectedPolicyText.value = c.expectedPolicy ? JSON.stringify(c.expectedPolicy, null, 2) : ''
  expectedKbHitsText.value = (c.expectedKbHits ?? []).join('\n')
  judgeRuleText.value = c.judgeRule ? JSON.stringify(c.judgeRule, null, 2) : ''
  dialogueRows.value = (c.dialogue ?? []).map((t) => ({
    role: t.role,
    content: t.content,
    toolUseText: t.toolUse ? JSON.stringify(t.toolUse, null, 2) : '',
    toolResultText: t.toolResult ? JSON.stringify(t.toolResult, null, 2) : '',
  }))
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
  const jsonAny = (text: string, field: string): unknown | undefined => {
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
    caseForm.judgeRule = jsonAny(judgeRuleText.value, '判分规则') as CaseSaveRequest['judgeRule']
  } catch (e) {
    ElMessage.warning((e as Error).message)
    return
  }
  // 多轮对话：content/tool 全空的轮次跳过；active 轮 content 必填；toolUse/toolResult 非空才解析
  const dialogue: NonNullable<CaseSaveRequest['dialogue']> = []
  for (let i = 0; i < dialogueRows.value.length; i += 1) {
    const t = dialogueRows.value[i]
    const toolUseJson = t.toolUseText.trim() ? jsonAny(t.toolUseText, `第 ${i + 1} 轮工具调用`) : undefined
    const toolResultJson = t.toolResultText.trim() ? jsonAny(t.toolResultText, `第 ${i + 1} 轮工具返回`) : undefined
    if (!t.content.trim() && toolUseJson === undefined && toolResultJson === undefined) continue
    if (!t.content.trim()) {
      ElMessage.warning(`第 ${i + 1} 轮对话缺少 content`)
      return
    }
    dialogue.push({
      role: t.role,
      content: t.content.trim(),
      toolUse: toolUseJson as NonNullable<CaseSaveRequest['dialogue']>[number]['toolUse'],
      toolResult: toolResultJson as NonNullable<CaseSaveRequest['dialogue']>[number]['toolResult'],
    })
  }
  caseForm.dialogue = dialogue.length ? dialogue : undefined
  // rag_hit_rate 期望命中片段：按行转数组（空 = 不配置该指标期望）；openjudge 数据集不随请求体携带
  caseForm.expectedKbHits = isExecuteCasesDataset.value
    ? expectedKbHitsText.value.split('\n').map((l) => l.trim()).filter(Boolean)
    : undefined
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
    await refetchDatasets()
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
    await refetchDatasets()
  } catch {
    ElMessage.error('删除失败')
  }
}

/* ---------- 评测运行（异步任务流） ---------- */
const runDatasetId = ref<number | undefined>(undefined)
/** 运行绑定数据集版本（缺省 = 最新已发布；无已发布版本回退实时工作区） */
const runVersionId = ref<number | undefined>(undefined)
const runVersions = ref<DatasetVersionView[]>([])
const runVersionsLoading = ref(false)
const runEvaluators = ref<string[]>(EVALUATOR_CATALOG.map((e) => e.metric))
/** LLM 判分次数（多次取均值；1-5，缺省 1） */
const runJudgeRounds = ref(1)
const running = ref(false)
const lastReport = ref<ReportView | null>(null)
/** 当前运行/展示中的任务（运行区状态徽章 + 进度条） */
const activeTask = ref<TaskView | null>(null)
/** 任务失败错误横幅文案（FAILED 后 errorMessage） */
const taskError = ref<string | null>(null)
/** 任务轮询定时器（约 3s 一次；卸载/终态清理） */
let taskPollTimer: number | null = null

function stopTaskPolling() {
  if (taskPollTimer != null) {
    window.clearInterval(taskPollTimer)
    taskPollTimer = null
  }
}
onUnmounted(stopTaskPolling)

/** 结果区版本标注：后端仅暴露 datasetVersionId/datasetVersionNo（versionNo null = 实时工作区）；旧后端全空不显示 */
const usedVersionNote = computed(() => {
  const r = lastReport.value
  if (!r) return null
  if (r.datasetVersionId == null && r.datasetVersionNo == null) return null
  return r.datasetVersionNo != null ? `本次运行基于 v${r.datasetVersionNo}（版本 #${r.datasetVersionId ?? '-'}）` : '本次运行基于实时工作区'
})

const runnableDatasets = computed(() =>
  (datasets.value ?? []).filter((d) => d.status === 'ENABLED' && d.caseCount > 0),
)

/** 运行选中数据集（rag_hit_rate 前置条件提示依其模式展示） */
const runDataset = computed(() => (datasets.value ?? []).find((d) => d.id === runDatasetId.value) ?? null)
const ragHitPrereqNotice = computed(() =>
  runDataset.value?.mode === 'execute' && runEvaluators.value.includes('rag_hit_rate'),
)

/** 数据集切换 → 拉版本列表并默认选中最新已发布版本（无版本端点/旧后端 → 静默回退实时工作区） */
async function reloadRunVersions() {
  const id = runDatasetId.value
  if (id == null) {
    runVersions.value = []
    runVersionId.value = undefined
    return
  }
  runVersionsLoading.value = true
  try {
    const rows = await listDatasetVersions(id)
    runVersions.value = rows
    const latest = rows.filter((v) => v.status === 'PUBLISHED').sort((a, b) => b.versionNo - a.versionNo)[0]
    runVersionId.value = latest?.id ?? undefined
  } catch {
    // 旧后端无版本端点：回退实时工作区（与后端缺省语义一致）
    runVersions.value = []
    runVersionId.value = undefined
  } finally {
    runVersionsLoading.value = false
  }
}
watch(runDatasetId, () => { void reloadRunVersions() })

const taskStatusType: Record<TaskView['status'], 'info' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'info',
  RUNNING: 'warning',
  CANCELING: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  CANCELED: 'info',
}
const taskStatusLabel: Record<TaskView['status'], string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  CANCELING: '取消中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消',
}
const isTaskActive = (t: TaskView | null | undefined): boolean =>
  !!t && (t.status === 'PENDING' || t.status === 'RUNNING' || t.status === 'CANCELING')
const progressStatus = (s: TaskView['status'] | undefined): 'success' | 'exception' | undefined => {
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'exception'
  return undefined
}

/** 运行任务表格 slot row 为 any，经字符串入参安全映射状态样式/文案 */
const taskTypeFor = (s: string): 'info' | 'warning' | 'success' | 'danger' =>
  taskStatusType[s as TaskView['status']] ?? 'info'
const taskLabelFor = (s: string): string => taskStatusLabel[s as TaskView['status']] ?? s

/** 数据集 id → 名称（运行任务列表列；row.datasetId 为 any，函数入参收窄） */
const datasetNameMap = computed(() => {
  const m: Record<number, string> = {}
  for (const d of datasets.value ?? []) m[d.id] = d.name
  return m
})
const datasetNameOf = (datasetId: number): string => datasetNameMap.value[datasetId] ?? `#${datasetId}`

async function pollTask(taskId: number) {
  let t: TaskView
  try {
    t = await getTask(taskId)
  } catch {
    return // 单次轮询失败不中断，等下一轮
  }
  activeTask.value = t
  if (t.status === 'COMPLETED') {
    stopTaskPolling()
    running.value = false
    if (t.reportId != null) {
      try {
        lastReport.value = await getReport(t.reportId)
        ElMessage.success('评测完成，报告已生成')
      } catch {
        ElMessage.error('报告加载失败')
      }
    }
    await refetchTasks()
    await refetchReports()
  } else if (t.status === 'FAILED') {
    stopTaskPolling()
    running.value = false
    taskError.value = t.errorMessage
    ElMessage.error(t.errorMessage || '评测任务失败')
    await refetchTasks()
  } else if (t.status === 'CANCELED') {
    stopTaskPolling()
    running.value = false
    ElMessage.info('评测任务已取消')
    await refetchTasks()
  }
}

async function doRun() {
  if (runDatasetId.value == null) {
    ElMessage.warning('请选择数据集')
    return
  }
  if (running.value) return
  running.value = true
  taskError.value = null
  activeTask.value = null
  lastReport.value = null
  try {
    const t = await createTask({
      datasetId: runDatasetId.value,
      datasetVersionId: runVersionId.value,
      evaluators: runEvaluators.value,
      judgeRounds: runJudgeRounds.value,
    })
    activeTask.value = t
    ElMessage.success('评测任务已创建，正在异步执行')
    await refetchTasks()
    taskPollTimer = window.setInterval(() => { void pollTask(t.id) }, 3000)
    void pollTask(t.id)
  } catch {
    running.value = false
    ElMessage.error('创建评测任务失败')
  }
}

/* ---------- 运行任务列表：取消 / 查看结果 ---------- */
async function cancelTaskFromList(t: TaskView) {
  try {
    await ElMessageBox.confirm(`确认取消评测任务「${t.name}」（#${t.id}）？`, '取消确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await cancelTask(t.id)
    ElMessage.success('已发起取消')
    await refetchTasks()
    // 正在轮询的任务：立即拉一次确认状态
    if (t.id === activeTask.value?.id) void pollTask(t.id)
  } catch (e) {
    ElMessage.error((e as Error).message || '取消失败')
  }
}

async function viewTaskResult(t: TaskView) {
  if (t.reportId == null) {
    ElMessage.warning('该任务无关联报告')
    return
  }
  try {
    const r = await getReport(t.reportId)
    if (t.id === activeTask.value?.id) {
      // 当前运行区任务：直接并入结果区
      lastReport.value = r
    } else {
      openReportDetail(r)
    }
  } catch {
    ElMessage.error('报告加载失败')
  }
}

/* ---------- 逐样本判分抽屉（任务模式 / 报告模式 + 执行轨迹） ---------- */
const sampleDrawerVisible = ref(false)
const sampleTask = ref<TaskView | null>(null)
/** 报告模式：从报告 Top 退化样例 / 校准 Top 偏差样本跳转（只展示该样例执行轨迹） */
const sampleReport = ref<ReportView | null>(null)
const sampleSeq = ref<number | null>(null)
/** caseSeq → 逐轮转录（懒加载缓存） */
const transcripts = ref<Record<number, TranscriptTurnView[]>>({})
/** caseSeq → 加载失败标记（旧后端无 transcript 端点时优雅降级） */
const transcriptFailed = ref<Record<number, boolean>>({})
const transcriptLoading = ref(false)

const turnRoleLabel = (role: string): string => {
  const u = (role ?? '').toUpperCase()
  if (u === 'USER') return '用户'
  if (u === 'ASSISTANT') return '助手'
  if (u === 'TOOL') return '工具'
  if (u === 'SYSTEM') return '系统'
  return role || '-'
}
const turnRoleType = (role: string): 'success' | 'warning' | 'info' | 'primary' => {
  const u = (role ?? '').toUpperCase()
  if (u === 'USER') return 'primary'
  if (u === 'ASSISTANT') return 'success'
  if (u === 'TOOL') return 'warning'
  return 'info'
}

/** 报告模式：按 seq 拉取报告侧转录 */
async function loadReportTranscript(reportId: number, seq: number) {
  if (transcripts.value[seq]) return
  transcriptLoading.value = true
  try {
    transcripts.value = { ...transcripts.value, [seq]: await getReportTranscript(reportId, seq) }
  } catch {
    transcriptFailed.value = { ...transcriptFailed.value, [seq]: true }
  } finally {
    transcriptLoading.value = false
  }
}

/** 任务模式：按 seq 拉取任务侧转录（任务有关联报告时优先报告侧，保证样本一致） */
async function loadTranscriptFor(seq: number) {
  const task = sampleTask.value
  if (!task || transcripts.value[seq]) return
  transcriptLoading.value = true
  try {
    if (task.reportId != null) {
      transcripts.value = { ...transcripts.value, [seq]: await getReportTranscript(task.reportId, seq) }
    } else {
      transcripts.value = { ...transcripts.value, [seq]: await getTaskTranscript(task.id, seq) }
    }
  } catch {
    transcriptFailed.value = { ...transcriptFailed.value, [seq]: true }
  } finally {
    transcriptLoading.value = false
  }
}

function openSamples(t: TaskView) {
  sampleTask.value = t
  sampleReport.value = null
  sampleSeq.value = null
  transcripts.value = {}
  transcriptFailed.value = {}
  sampleDrawerVisible.value = true
}

/** 从报告详情（Top 退化样例 / 校准跳转）进入：报告模式只展示指定样例轨迹 */
async function openReportSample(seq: number) {
  const r = reportDetail.value
  if (!r) return
  sampleReport.value = r
  sampleTask.value = null
  sampleSeq.value = seq
  transcripts.value = {}
  transcriptFailed.value = {}
  sampleDrawerVisible.value = true
  await loadReportTranscript(r.id, seq)
}

/* ---------- 基线回归对比 ---------- */
const compareDialogVisible = ref(false)
const compareTarget = ref<ReportView | null>(null)
const compareBaselineId = ref<number | null>(null)
const compareLoading = ref(false)
const compareResult = ref<ReportCompareView | null>(null)
/** 对比分层过滤（undefined = 全量）；切 Tab 或修改即重跑 */
const compareLayer = ref<'basic' | 'edge' | 'real' | undefined>(undefined)
const compareTab = ref<'metrics' | 'degraded' | 'layering'>('metrics')
const compareLayerLabel = computed(() => (compareLayer.value ? CATEGORY_LABEL[compareLayer.value] : '全部'))

/** 同数据集历史报告（排除自身，最新在前；缺省建议最近一次） */
const baselineCandidates = computed(() => {
  const target = compareTarget.value
  if (!target) return []
  return (reports.value ?? [])
    .filter((r) => r.datasetId === target.datasetId && r.id !== target.id)
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
})

function openCompare(row: ReportView) {
  compareTarget.value = row
  compareBaselineId.value = baselineCandidates.value[0]?.id ?? null
  compareResult.value = null
  compareLayer.value = undefined
  compareLayerPicker.value = 'all'
  compareTab.value = 'metrics'
  compareDialogVisible.value = true
}

async function doCompare() {
  if (compareTarget.value == null || compareBaselineId.value == null) return
  compareLoading.value = true
  try {
    compareResult.value = await compareReport(compareTarget.value.id, compareBaselineId.value, compareLayer.value)
  } catch (e) {
    ElMessage.error((e as Error).message || '对比失败')
  } finally {
    compareLoading.value = false
  }
}

/** 分层对比 Tab 单选：'all' → undefined（全量口径），其余直传 */
const compareLayerPicker = ref<'all' | 'basic' | 'edge' | 'real'>('all')
function onCompareLayerChange() {
  compareLayer.value = compareLayerPicker.value === 'all' ? undefined : compareLayerPicker.value
  ElMessage.info(`已按「${compareLayerLabel.value}」分层口径重跑对比（旧后端将忽略分层过滤）`)
  void doCompare()
}

const fmtPct = (v: number | null | undefined): string =>
  v == null ? '-' : `${(v * 100).toFixed(1)}%`
const fmtDelta = (v: number | null | undefined): string => {
  if (v == null) return '-'
  const pct = v * 100
  return `${pct > 0 ? '+' : ''}${pct.toFixed(1)}%`
}

/* ---------- 三组评测器面板：全选 / 清空 ---------- */
const RULE_METRICS = EVALUATOR_CATALOG.filter((e) => e.group === 'rule').map((e) => e.metric)
const LLM_METRICS = EVALUATOR_CATALOG.filter((e) => e.group === 'llm').map((e) => e.metric)

const ruleAllSelected = computed(() =>
  RULE_METRICS.length > 0 && RULE_METRICS.every((m) => runEvaluators.value.includes(m)),
)
const llmAllSelected = computed(() =>
  LLM_METRICS.length > 0 && LLM_METRICS.every((m) => runEvaluators.value.includes(m)),
)
const customAllSelected = computed(() =>
  enabledCustomMetrics.value.length > 0
  && enabledCustomMetrics.value.every((m) => runEvaluators.value.includes(m)),
)

function toggleGroupAll(groupMetrics: string[], allSelected: boolean) {
  if (allSelected) {
    runEvaluators.value = runEvaluators.value.filter((m) => !groupMetrics.includes(m))
  } else {
    runEvaluators.value = [...new Set([...runEvaluators.value, ...groupMetrics])]
  }
}

function toggleMetric(metric: string, checked: boolean) {
  runEvaluators.value = checked
    ? [...new Set([...runEvaluators.value, metric])]
    : runEvaluators.value.filter((m) => m !== metric)
}

/* ---------- 自定义评测器 ---------- */
const { data: customEvaluators, refetch: refetchCustom } = useQuery<CustomEvaluatorView[]>({
  queryKey: ['eval-custom'],
  queryFn: listCustomEvaluators,
})
/** 启用的自定义评测器 metric（custom_{id}），入选运行勾选 */
const enabledCustomMetrics = computed(() =>
  (customEvaluators.value ?? []).filter((c) => c.status === 'ENABLED').map((c) => c.metric),
)

const customDrawer = ref(false)
const customDialog = ref(false)
const customSaving = ref(false)
const editingCustomId = ref<number | null>(null)
const customForm = reactive<CustomSaveRequest>({
  name: '',
  category: 'rule',
  description: '',
  ruleType: 'keyword_contains',
  params: undefined,
  judgePrompt: '',
  status: 'ENABLED',
})
const customParamsText = ref('')

function openCustomCreate() {
  editingCustomId.value = null
  customForm.name = ''
  customForm.category = 'rule'
  customForm.description = ''
  customForm.ruleType = 'keyword_contains'
  customForm.params = undefined
  customForm.judgePrompt = ''
  customForm.status = 'ENABLED'
  customParamsText.value = ''
  customDialog.value = true
}

function openCustomEdit(row: CustomEvaluatorView) {
  editingCustomId.value = row.id
  customForm.name = row.name
  customForm.category = row.category
  customForm.description = row.description ?? ''
  customForm.ruleType = row.ruleType ?? 'keyword_contains'
  customForm.judgePrompt = row.judgePrompt ?? ''
  customForm.status = row.status
  customParamsText.value = row.params ? JSON.stringify(row.params, null, 2) : ''
  customDialog.value = true
}

async function saveCustom() {
  if (!customForm.name.trim()) {
    ElMessage.warning('评测器名称不能为空')
    return
  }
  if (customForm.category === 'rule') {
    if (!customForm.ruleType || !['keyword_contains', 'regex_match', 'length_between'].includes(customForm.ruleType)) {
      ElMessage.warning('请选择合法规则类型')
      return
    }
    if (!customParamsText.value.trim()) {
      ElMessage.warning('规则参数不能为空')
      return
    }
    try {
      customForm.params = JSON.parse(customParamsText.value)
    } catch {
      ElMessage.warning('规则参数不是合法 JSON')
      return
    }
  } else {
    if (!customForm.judgePrompt!.trim()) {
      ElMessage.warning('LLM-Judge 提示词不能为空')
      return
    }
    customForm.params = undefined
  }
  customSaving.value = true
  try {
    if (editingCustomId.value == null) {
      await createCustomEvaluator({ ...customForm })
      ElMessage.success('自定义评测器已创建')
    } else {
      await updateCustomEvaluator(editingCustomId.value, { ...customForm })
      ElMessage.success('自定义评测器已保存')
    }
    customDialog.value = false
    await refetchCustom()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    customSaving.value = false
  }
}

async function removeCustom(row: CustomEvaluatorView) {
  try {
    await ElMessageBox.confirm(`确认删除自定义评测器「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteCustomEvaluator(row.id)
    ElMessage.success('已删除')
    runEvaluators.value = runEvaluators.value.filter((m) => m !== row.metric)
    await refetchCustom()
  } catch {
    ElMessage.error('删除失败')
  }
}

/* ---------- jsonl 导入 ---------- */
const importDialog = ref(false)
const importing = ref(false)
const importingDatasetId = ref<number | null>(null)
const importContent = ref('')
const importResult = ref<ImportResultView | null>(null)

function openImport(row: DatasetView) {
  importingDatasetId.value = row.id
  importContent.value = ''
  importResult.value = null
  importDialog.value = true
}

async function doImport() {
  if (!importContent.value.trim()) {
    ElMessage.warning('请粘贴 jsonl 内容')
    return
  }
  importing.value = true
  importResult.value = null
  try {
    const result = await importCases(importingDatasetId.value!, importContent.value)
    importResult.value = result
    if (result.imported > 0) {
      ElMessage.success(`导入 ${result.imported} 条，跳过 ${result.skipped} 条`)
      await refetchDatasets()
    }
  } catch {
    ElMessage.error('导入失败')
  } finally {
    importing.value = false
  }
}

/* ---------- 预览前 2 样本 ---------- */
const previewDialog = ref(false)
const previewDataset = ref<DatasetView | null>(null)
const previewCases = ref<CaseView[]>([])
const previewLoading = ref(false)

async function openPreview(row: DatasetView) {
  previewDataset.value = row
  previewCases.value = []
  previewDialog.value = true
  previewLoading.value = true
  try {
    const all = await listCases(row.id)
    previewCases.value = all.slice(0, 2)
  } catch {
    ElMessage.error('预览失败')
  } finally {
    previewLoading.value = false
  }
}

/* ---------- 报告回看（TraceID 联动驾驶舱） ---------- */
function gotoCockpitTraces(row: ReportView) {
  if (!row.traceId) {
    ElMessage.warning('该报告无追踪 ID')
    return
  }
  router.push({ name: 'cockpit', query: { trace: row.traceId } })
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

/* ---------- 报告详情：复现 / 上线建议 / 分层 / 执行统计 / Top 退化 ---------- */
const rerunning = ref(false)

/** 复现报告：复用原数据集版本 + 评测器 + 判分配置，生成新报告 */
async function doRerun() {
  const r = reportDetail.value
  if (!r) return
  try {
    await ElMessageBox.confirm(`确认复现报告「${r.name}」（#${r.id}）？将复用原数据集版本与评测配置重跑并生成新报告。`, '复现确认', { type: 'info' })
  } catch {
    return
  }
  rerunning.value = true
  try {
    const nr = await rerunReport(r.id)
    ElMessage.success(`复现完成，新报告 #${nr.id} 已生成`)
    await refetchReports()
  } catch (e) {
    ElMessage.error((e as Error).message || '复现失败')
  } finally {
    rerunning.value = false
  }
}

const recommendation = computed(() => reportDetail.value?.summary?.recommendation ?? null)
const recommendationType = (v?: string | null): 'success' | 'warning' | 'danger' => (v === 'GO' ? 'success' : v === 'NO_GO' ? 'danger' : 'warning')
const recommendationLabel = (v?: string | null): string => (v === 'GO' ? '建议上线' : v === 'NO_GO' ? '不建议上线' : '观察后上线')

/** 报告分层统计（ReportView.layering 与 summary.layering 同源，取其一；旧后端全空） */
const layerStats = computed(() => {
  const l = reportDetail.value?.layering ?? reportDetail.value?.summary?.layering ?? null
  return CATEGORY_ORDER.map((k) => {
    const s = l ? l[k] : null
    return {
      key: k,
      label: CATEGORY_LABEL[k],
      color: CATEGORY_COLOR[k],
      count: s?.count ?? 0,
      tested: s?.tested ?? 0,
      passRate: s?.pass_rate ?? null,
    }
  })
})
const hasLayerStats = computed(() => layerStats.value.some((s) => s.count > 0))

/** 报告执行统计行（延迟/步数/Token/成本；null → '—'） */
const executionRows = computed(() => {
  const e: ReportExecutionView | null = reportDetail.value?.execution ?? null
  if (!e) return []
  const tokens = e.input_tokens != null || e.output_tokens != null ? (e.input_tokens ?? 0) + (e.output_tokens ?? 0) : null
  const ms = (v: number | null | undefined): string | null => (v == null ? null : `${v} ms`)
  const rows: Array<{ label: string; value: string }> = []
  const push = (label: string, v: string | null) => rows.push({ label, value: v ?? '—' })
  push('平均延迟', ms(e.avg_latency_ms))
  push('P50 延迟', ms(e.p50_latency_ms))
  push('P95 延迟', ms(e.p95_latency_ms))
  push('平均步数', e.avg_steps == null ? null : String(e.avg_steps))
  push('总 Token', tokens == null ? null : String(tokens))
  push('预估成本（元）', e.estimated_cost_cny == null ? null : e.estimated_cost_cny.toFixed(4))
  return rows
})

/** Top 退化指标（summary.top_regressions.metrics；无基线 → 空表） */
const topRegressions = computed(() => reportDetail.value?.summary?.top_regressions?.metrics ?? [])
/** Top 退化样例（summary.top_regressions.samples；跨指标扁平 top-N，独立于指标分组） */
const topRegressionSamples = computed(() => reportDetail.value?.summary?.top_regressions?.samples ?? [])

/* ---------- 人工复评（报告详情驱动；score 0-100 输入 → /100 提交 0-1） ---------- */
const reviewDrawer = ref(false)
const reviewReportId = ref<number | null>(null)
const reviews = ref<HumanReviewView[]>([])
const reviewsLoading = ref(false)
/** 复评编辑行：新建行 caseSeq 待填；已有行回显（再次提交 = upsert 改判） */
const reviewRows = reactive<Array<{ key: number; caseSeq: number | undefined; metric: string; score100: number; verdict: string; note: string; id: number | null }>>([])
let reviewRowKey = 0
const reviewSaving = ref(false)
/** 校准 topDeltas 提供的 `${metric}:${seq}` → 自动分（摘要展示） */
const autoScoreBySeq = ref<Record<string, number>>({})

const reportMetricOptions = computed(() => {
  const r = reportDetail.value
  const names = new Set<string>((r?.metrics ?? []).map((m) => m.metric))
  return ['*', ...Array.from(names)]
})
const autoScoreHint = (row: { metric: string; caseSeq: number | undefined }): string => {
  if (row.caseSeq == null) return ''
  const v = autoScoreBySeq.value[`${row.metric}:${row.caseSeq}`]
  return v == null ? '' : `自动 ${fmtPct(v)}`
}

async function openReviews() {
  const r = reportDetail.value
  if (!r) return
  reviewReportId.value = r.id
  reviewDrawer.value = true
  reviewsLoading.value = true
  reviewRows.splice(0, reviewRows.length)
  autoScoreBySeq.value = {}
  try {
    const [list, cal] = await Promise.all([
      listHumanReviews(r.id),
      getCalibration(r.id).catch(() => ({ metrics: [] as CalibrationView[] })),
    ])
    reviews.value = list
    const autoMap: Record<string, number> = {}
    for (const c of cal.metrics) {
      for (const d of c.topDeltas ?? []) {
        if (d.auto != null) autoMap[`${c.metric}:${d.caseSeq}`] = d.auto
      }
    }
    autoScoreBySeq.value = autoMap
    reviewRows.push(...list.map((h) => ({
      key: reviewRowKey++,
      caseSeq: h.caseSeq,
      metric: h.metric,
      score100: Math.round(h.score * 100),
      verdict: h.verdict ?? '',
      note: h.note ?? '',
      id: h.id,
    })))
  } catch {
    ElMessage.error('复评数据加载失败')
  } finally {
    reviewsLoading.value = false
  }
}
function addReviewRow() {
  reviewRows.push({ key: reviewRowKey++, caseSeq: undefined, metric: '*', score100: 80, verdict: '', note: '', id: null })
}
async function saveReviewRow(row: typeof reviewRows[number]) {
  if (row.caseSeq == null) {
    ElMessage.warning('请填写用例序号')
    return
  }
  if (reviewReportId.value == null) return
  reviewSaving.value = true
  try {
    const payload: HumanReviewSaveRequest = {
      caseSeq: row.caseSeq,
      metric: row.metric || '*',
      score: Math.max(0, Math.min(1, row.score100 / 100)),
      verdict: row.verdict || null,
      note: row.note || null,
    }
    await submitHumanReview(reviewReportId.value, payload)
    ElMessage.success(`样例 #${row.caseSeq} 复评已保存${row.metric === '*' ? '（全指标整分）' : ''}`)
    if (reviewReportId.value != null) reviews.value = await listHumanReviews(reviewReportId.value).catch(() => reviews.value)
  } catch (e) {
    ElMessage.error((e as Error).message || '保存失败')
  } finally {
    reviewSaving.value = false
  }
}
async function removeReviewRow(row: typeof reviewRows[number]) {
  if (row.id == null) {
    const idx = reviewRows.indexOf(row)
    if (idx >= 0) reviewRows.splice(idx, 1)
    return
  }
  try {
    await deleteHumanReview(row.id)
  } catch (e) {
    ElMessage.error((e as Error).message || '删除失败')
    return
  }
  ElMessage.success('已删除')
  const idx = reviewRows.indexOf(row)
  if (idx >= 0) reviewRows.splice(idx, 1)
  if (reviewReportId.value != null) reviews.value = await listHumanReviews(reviewReportId.value).catch(() => reviews.value)
}

/* ---------- 校准对比（人工 vs 自动；mean 均按 0-1 归一展示） ---------- */
const calibrationDrawer = ref(false)
const calibration = ref<CalibrationView[]>([])
const calibrationLoading = ref(false)
/** 校准弹窗展示前 N 条偏差样本（避免超长） */
const calibrationMetrics = computed(() =>
  calibration.value.map((c) => ({ ...c, topDeltas: (c.topDeltas ?? []).slice(0, 5) })),
)
async function openCalibration() {
  const r = reportDetail.value
  if (!r) return
  calibrationDrawer.value = true
  calibrationLoading.value = true
  try {
    const cal = await getCalibration(r.id)
    calibration.value = cal.metrics
  } catch (e) {
    ElMessage.error((e as Error).message || '校准数据加载失败')
  } finally {
    calibrationLoading.value = false
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
        <el-table-column prop="caseCount" label="用例数" width="70" />
        <el-table-column label="分层分布" width="150">
          <template #default="{ row }">
            <template v-if="row.caseCountByCategory">
              <div class="layer-bar" :title="`基础 ${row.caseCountByCategory.basic} / 边界 ${row.caseCountByCategory.edge} / 真实 ${row.caseCountByCategory.real}`">
                <div
                  v-for="t in categoryTiers(row)"
                  :key="t.key"
                  class="layer-seg"
                  :style="{ width: `${Math.max(t.pct * 100, t.count > 0 ? 2 : 0)}%`, background: t.color }"
                />
              </div>
              <div class="layer-nums">
                <span v-for="(t, i) in categoryTiers(row)" :key="t.key" :class="{ 'layer-hit': t.hit }">
                  <template v-if="i > 0"><span class="layer-sep">/</span></template>
                  {{ t.label }}{{ t.count }}
                </span>
              </div>
            </template>
            <span v-else class="muted-text">-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openPreview(row)">预览</el-button>
            <el-button size="small" link type="primary" @click="openImport(row)">导入 jsonl</el-button>
            <el-button size="small" link type="primary" @click="openCasesDrawer(row)">用例</el-button>
            <el-button size="small" link type="primary" @click="openVersionDialog(row)">版本</el-button>
            <el-button size="small" link type="primary" @click="openDatasetEdit(row)">编辑</el-button>
            <el-button size="small" link type="danger" @click="removeDataset(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无数据集，先新建一个" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- ①.5 评测看板（数据集维度聚合：分层 / 趋势 / 指标 / 成本延迟 / 退化） -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">评测看板</span>
          <el-select
            v-model="dashboardDatasetId"
            size="small"
            clearable
            filterable
            placeholder="选择数据集"
            style="width: 240px"
          >
            <el-option v-for="d in datasets ?? []" :key="d.id" :label="`${d.name}（${d.caseCount} 例）`" :value="d.id" />
          </el-select>
          <el-button size="small" :loading="dashboardLoading" @click="refetchDashboard">刷新</el-button>
        </div>
      </template>
      <div v-if="dashboardDatasetId != null" v-loading="dashboardLoading" class="dash-grid">
        <div class="dash-card">
          <div class="dash-card-title">分层分布（目标 40/30/30）</div>
          <template v-if="dashLayerTiers.some((t) => t.count > 0)">
            <div class="layer-bar">
              <div
                v-for="t in dashLayerTiers"
                :key="t.key"
                class="layer-seg"
                :style="{ width: `${Math.max(t.pct * 100, t.count > 0 ? 2 : 0)}%`, background: t.color }"
              />
            </div>
            <div class="layer-nums">
              <span v-for="(t, i) in dashLayerTiers" :key="t.key" :class="{ 'layer-hit': t.hit }">
                <template v-if="i > 0"><span class="layer-sep">/</span></template>
                {{ t.label }}{{ t.count }}
              </span>
            </div>
            <div class="layer-sub">
              基础 {{ fmtPct(dashLayerTiers[0].passRate) }} 通过 · 边界 {{ fmtPct(dashLayerTiers[1].passRate) }} 通过 · 真实 {{ fmtPct(dashLayerTiers[2].passRate) }} 通过
            </div>
          </template>
          <div v-else class="dash-empty">后端未返回分层分布</div>
        </div>
        <div class="dash-card dash-trend">
          <div class="dash-card-title">最近报告趋势</div>
          <el-table :data="dashboard?.trend ?? []" size="small" border class="mini-table">
            <el-table-column label="报告" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="openDashboardReport(row)">{{ row.name }}</el-button>
              </template>
            </el-table-column>
            <el-table-column label="总分" width="60" align="center">
              <template #default="{ row }">{{ row.summary?.score == null ? '-' : row.summary.score }}</template>
            </el-table-column>
            <el-table-column label="结论" width="70" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.summary?.verdict" :type="verdictType(row.summary.verdict)" size="small">{{ row.summary.verdict }}</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="dash-card">
          <div class="dash-card-title">指标均值</div>
          <el-table :data="dashMetricRows" size="small" border class="mini-table">
            <el-table-column label="指标" min-width="120">
              <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
            </el-table-column>
            <el-table-column label="均值" width="80" align="center">
              <template #default="{ row }">{{ fmtPct(row.latest) }}</template>
            </el-table-column>
            <el-table-column label="Δ" width="80" align="center">
              <template #default="{ row }">
                <el-tag
                  v-if="row.delta != null"
                  :type="row.delta > 0 ? 'success' : row.delta < 0 ? 'danger' : 'info'"
                  size="small"
                >{{ fmtDelta(row.delta) }}</el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="dash-card dash-cost">
          <div class="dash-card-title">成本与延迟</div>
          <div class="cost-row"><span class="cost-k">平均延迟</span><span class="cost-v">{{ dashMs(dashboard?.costLatency?.avg_latency_ms) }}</span></div>
          <div class="cost-row"><span class="cost-k">P95 延迟</span><span class="cost-v">{{ dashMs(dashboard?.costLatency?.p95_latency_ms) }}</span></div>
          <div class="cost-row"><span class="cost-k">平均步数</span><span class="cost-v">{{ dashboard?.costLatency?.avg_steps == null ? '—' : dashboard.costLatency.avg_steps }}</span></div>
          <div class="cost-row"><span class="cost-k">总 Token</span><span class="cost-v">{{ dashboard?.costLatency?.total_tokens == null ? '—' : dashboard.costLatency.total_tokens }}</span></div>
          <div class="cost-row"><span class="cost-k">预估成本</span><span class="cost-v">{{ dashboard?.costLatency?.cost_cny == null ? '—' : `¥${dashboard.costLatency.cost_cny.toFixed(4)}` }}</span></div>
        </div>
        <div class="dash-card dash-regressions">
          <div class="dash-card-title">退化指标（vs 基线）</div>
          <template v-if="dashboard?.regressions && dashboard.regressions.length">
            <div v-for="r in dashboard.regressions" :key="r.metric" class="dash-reg-row">
              <code class="metric-code">{{ r.metric }}</code>
              <span class="dash-reg-val">{{ fmtPct(r.current) }}</span>
              <el-tag
                v-if="r.delta != null"
                :type="r.delta > 0 ? 'success' : r.delta < 0 ? 'danger' : 'info'"
                size="small"
              >{{ fmtDelta(r.delta) }}</el-tag>
            </div>
          </template>
          <div v-else class="dash-empty">无退化指标</div>
        </div>
      </div>
      <el-empty v-else description="选择数据集查看看板" :image-size="60" />
    </el-card>

    <!-- ② 三组评测器面板：规则判定 / LLM-Judge / 自定义（勾选 = 参与运行，每组一键全选/清空） -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">评测器（勾选参与运行）</span>
          <span class="panel-sub">规则判定 {{ RULE_METRICS.length }} + LLM-Judge {{ LLM_METRICS.length }} + 自定义；LLM 未启用时 LLM-Judge 走确定性近似</span>
        </div>
      </template>

      <div class="group-block">
        <div class="group-head">
          <span class="group-title">规则判定（{{ RULE_METRICS.length }}）</span>
          <el-button size="small" link type="primary" @click="toggleGroupAll(RULE_METRICS, ruleAllSelected)">
            {{ ruleAllSelected ? '清空' : '全选' }}
          </el-button>
        </div>
        <el-table :data="EVALUATOR_CATALOG.filter((e) => e.group === 'rule')" border stripe size="small">
          <el-table-column width="50">
            <template #default="{ row }">
              <el-checkbox :model-value="runEvaluators.includes(row.metric)" @change="(v: boolean | string | number) => toggleMetric(row.metric, !!v)" />
            </template>
          </el-table-column>
          <el-table-column label="评测器" width="220">
            <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
          </el-table-column>
          <el-table-column prop="label" label="名称" width="130" />
          <el-table-column prop="description" label="说明" min-width="230" show-overflow-tooltip />
          <el-table-column prop="origin" label="参考基准" min-width="170" show-overflow-tooltip />
        </el-table>
      </div>

      <div class="group-block">
        <div class="group-head">
          <span class="group-title">LLM-Judge（6）</span>
          <el-button size="small" link type="primary" @click="toggleGroupAll(LLM_METRICS, llmAllSelected)">
            {{ llmAllSelected ? '清空' : '全选' }}
          </el-button>
        </div>
        <el-table :data="EVALUATOR_CATALOG.filter((e) => e.group === 'llm')" border stripe size="small">
          <el-table-column width="50">
            <template #default="{ row }">
              <el-checkbox :model-value="runEvaluators.includes(row.metric)" @change="(v: boolean | string | number) => toggleMetric(row.metric, !!v)" />
            </template>
          </el-table-column>
          <el-table-column label="评测器" width="220">
            <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
          </el-table-column>
          <el-table-column prop="label" label="名称" width="130" />
          <el-table-column prop="description" label="说明" min-width="230" show-overflow-tooltip />
          <el-table-column prop="origin" label="参考基准" min-width="170" show-overflow-tooltip />
        </el-table>
      </div>

      <div class="group-block">
        <div class="group-head">
          <span class="group-title">自定义（{{ (customEvaluators ?? []).filter((c) => c.status === 'ENABLED').length }}）</span>
          <span class="group-actions">
            <el-button size="small" link type="primary" @click="toggleGroupAll(enabledCustomMetrics, customAllSelected)">
              {{ customAllSelected ? '清空' : '全选' }}
            </el-button>
            <el-button size="small" link type="primary" @click="customDrawer = true">管理</el-button>
          </span>
        </div>
        <el-table v-if="customEvaluators && customEvaluators.length" :data="customEvaluators" border stripe size="small">
          <el-table-column width="50">
            <template #default="{ row }">
              <el-checkbox
                :model-value="row.status === 'ENABLED' && runEvaluators.includes(row.metric)"
                :disabled="row.status !== 'ENABLED'"
                @change="(v: boolean | string | number) => toggleMetric(row.metric, !!v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="评测器" width="220">
            <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
          </el-table-column>
          <el-table-column label="类别" width="110">
            <template #default="{ row }">
              <el-tag :type="row.category === 'rule' ? 'success' : 'warning'" size="small">
                {{ row.category === 'rule' ? '规则' : 'LLM-Judge' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" width="130" />
          <el-table-column label="规则/提示词" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.category === 'rule' ? `${row.ruleType} ${JSON.stringify(row.params ?? {})}` : (row.judgePrompt ?? '').slice(0, 40) }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无自定义评测器，点「管理」创建" :image-size="50" />
      </div>
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
          v-model="runVersionId"
          placeholder="数据集版本（缺省最新已发布）"
          clearable
          filterable
          :loading="runVersionsLoading"
          style="width: 240px"
        >
          <el-option
            v-for="v in runVersions"
            :key="v.id"
            :label="`v${v.versionNo}（${v.caseCount} 例${v.status === 'PUBLISHED' ? ' · 已发布' : ' · 草稿'}）`"
            :value="v.id"
          />
          <template #empty>
            <span class="muted-text">暂无版本，将运行实时工作区</span>
          </template>
        </el-select>
        <el-select
          v-model="runEvaluators"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="评测器（缺省全量内置）"
          style="width: 420px"
        >
          <el-option-group v-for="g in ['rule', 'llm']" :key="g" :label="g === 'rule' ? '规则判定' : 'LLM-Judge'">
            <el-option v-for="e in EVALUATOR_CATALOG.filter((x) => x.group === g)" :key="e.metric" :label="e.label" :value="e.metric" />
          </el-option-group>
          <el-option-group label="自定义">
            <el-option v-for="c in (customEvaluators ?? []).filter((x) => x.status === 'ENABLED')" :key="c.metric" :label="c.name" :value="c.metric" />
          </el-option-group>
        </el-select>
        <span class="rounds-label">LLM 判分次数</span>
        <el-input-number v-model="runJudgeRounds" :min="1" :max="5" size="default" style="width: 110px" />
        <el-button type="primary" :loading="running" @click="doRun">运行评测</el-button>
      </div>

      <!-- rag_hit_rate 前置条件提示（execute 模式选中该指标时展示） -->
      <el-alert
        v-if="ragHitPrereqNotice"
        type="warning"
        :closable="false"
        show-icon
        title="已选中 RAG 命中率（rag_hit_rate）：需知识库已有文档（智能客服-知识库上传）且用例已配置期望命中片段，否则该指标判为不适用"
        class="rag-hint"
      />

      <!-- 运行状态：状态徽章 + 进度条 + 失败/取消提示；COMPLETED 后报告自动并入下方结果区 -->
      <template v-if="activeTask || taskError">
        <div class="run-status">
          <div class="result-head">
            <el-tag :type="taskStatusType[activeTask?.status ?? 'FAILED']" size="small">
              {{ taskStatusLabel[activeTask?.status ?? 'FAILED'] }}
            </el-tag>
            <span class="result-title">{{ activeTask ? `任务 #${activeTask.id} ${activeTask.name}` : '评测任务' }}</span>
            <span v-if="activeTask" class="panel-sub">已测 {{ activeTask.testedCases }}/{{ activeTask.totalCases }} 例 · 创建 {{ fmtTime(activeTask.createdAt) }}</span>
            <el-button v-if="activeTask && isTaskActive(activeTask)" size="small" type="danger" plain @click="cancelTaskFromList(activeTask)">取消任务</el-button>
          </div>
          <el-progress
            v-if="activeTask"
            :percentage="activeTask.progressPct"
            :status="progressStatus(activeTask.status)"
            :stroke-width="12"
            class="run-progress"
          />
          <el-alert v-if="taskError" :title="taskError || '评测任务失败'" type="error" :closable="false" show-icon class="task-error" />
          <el-alert v-if="activeTask?.status === 'CANCELED'" title="评测任务已取消" type="info" :closable="false" class="task-error" />
        </div>
      </template>

      <template v-if="lastReport">
        <div class="result-head">
          <el-tag :type="verdictType(lastReport.summary.verdict)" size="small">总分 {{ lastReport.summary.score }}</el-tag>
          <span class="result-title">评测结果：{{ lastReport.summary.verdict }}</span>
          <span class="panel-sub">测试 {{ lastReport.testedCases }}/{{ lastReport.totalCases }} 例 · 模型 {{ lastReport.model }} · 置信度 {{ lastReport.confidence }}</span>
        </div>
        <div v-if="usedVersionNote" class="muted-text run-version-note">{{ usedVersionNote }}</div>
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
      <el-empty v-else-if="!activeTask && !taskError" description="尚未运行评测" :image-size="60" />
    </el-card>

    <!-- ④ 运行任务列表 -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">运行任务</span>
          <span class="panel-sub">异步执行（created_at 倒序）；存在未终态任务时自动刷新</span>
        </div>
      </template>
      <el-table v-loading="tasksLoading" :data="tasks ?? []" border stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="任务名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="数据集" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ datasetNameOf(row.datasetId) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tooltip v-if="row.errorMessage" :content="row.errorMessage" placement="top">
              <el-tag :type="taskTypeFor(row.status)" size="small">{{ taskLabelFor(row.status) }}</el-tag>
            </el-tooltip>
            <el-tag v-else :type="taskTypeFor(row.status)" size="small">{{ taskLabelFor(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="230">
          <template #default="{ row }">
            <el-progress :percentage="row.progressPct" :status="progressStatus(row.status)" :stroke-width="10" />
            <span class="task-progress-text">已测 {{ row.testedCases }}/{{ row.totalCases }} 例</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'COMPLETED' && row.reportId != null" size="small" link type="primary" @click="viewTaskResult(row)">查看结果</el-button>
            <el-button v-if="row.status === 'COMPLETED'" size="small" link type="primary" @click="openSamples(row)">样本</el-button>
            <el-button v-if="isTaskActive(row)" size="small" link type="danger" @click="cancelTaskFromList(row)">取消</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无运行任务，点上方「运行评测」发起" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- ⑤ 报告列表 -->
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
        <el-table-column label="判分轮次" width="90">
          <template #default="{ row }">{{ row.judgeRounds ?? 1 }}</template>
        </el-table-column>
        <el-table-column label="追踪 ID" min-width="150">
          <template #default="{ row }">
            <code v-if="row.traceId" class="metric-code">{{ row.traceId }}</code>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openReportDetail(row)">详情</el-button>
            <el-button size="small" link type="primary" @click="openCompare(row)">对比</el-button>
            <el-button size="small" link type="danger" @click="removeReport(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无评测报告" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- jsonl 导入对话框 -->
    <el-dialog v-model="importDialog" :title="`导入 jsonl 用例${importingDatasetId != null ? `（数据集 #${importingDatasetId}）` : ''}`" width="680px" :close-on-click-modal="false">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="import-tip"
        title="每行一个 JSON 对象（或整体 JSON 数组）；question 必填，reference→期望输出、response→预置响应、system_prompt→系统提示；坏行自动跳过并记录行号"
      />
      <el-input v-model="importContent" type="textarea" :rows="10" placeholder='{"question":"...","reference":"...","response":"...","system_prompt":"..."}' class="json-input" />
      <template v-if="importResult">
        <el-result
          :icon="importResult.imported > 0 ? 'success' : 'warning'"
          :title="`导入 ${importResult.imported} 条，跳过 ${importResult.skipped} 条`"
          :sub-title="importResult.errors.length ? `坏行：${importResult.errors.map((e) => `第 ${e.line} 行（${e.message}）`).join('；')}` : '全部行导入成功'"
        />
      </template>
      <template #footer>
        <el-button @click="importDialog = false">关闭</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">开始导入</el-button>
      </template>
    </el-dialog>

    <!-- 预览前 2 样本对话框 -->
    <el-dialog v-model="previewDialog" :title="`预览前 2 样本${previewDataset != null ? `：${previewDataset.name}` : ''}`" width="640px">
      <div v-loading="previewLoading">
        <div v-if="previewCases.length">
          <div v-for="c in previewCases" :key="c.id" class="case-row">
            <div class="case-row-head">
              <el-tag size="small" type="info">#{{ c.seq ?? c.id }}</el-tag>
              <span class="case-question">{{ c.question }}</span>
            </div>
            <div v-if="c.systemPrompt" class="case-meta">系统提示：{{ c.systemPrompt }}</div>
            <div v-if="c.providedResponse" class="case-meta">预置响应：{{ c.providedResponse }}</div>
            <div v-if="c.expectedOutput" class="case-meta">期望输出：{{ jsonText(c.expectedOutput) }}</div>
          </div>
        </div>
        <el-empty v-else-if="!previewLoading" description="该数据集暂无样本" :image-size="60" />
      </div>
    </el-dialog>

    <!-- 自定义评测器管理抽屉 -->
    <el-drawer v-model="customDrawer" title="自定义评测器管理" size="760px">
      <div class="drawer-head">
        <span class="drawer-title">rule = Java 参数化规则（keyword_contains / regex_match / length_between）；llm_judge = 可配提示词判分</span>
        <el-button size="small" type="primary" @click="openCustomCreate">新建评测器</el-button>
      </div>
      <el-table v-if="customEvaluators && customEvaluators.length" :data="customEvaluators" border stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="指标" width="150">
          <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="130" />
        <el-table-column label="类别" width="110">
          <template #default="{ row }">
            <el-tag :type="row.category === 'rule' ? 'success' : 'warning'" size="small">
              {{ row.category === 'rule' ? '规则' : 'LLM-Judge' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="规则/提示词" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.category === 'rule' ? `${row.ruleType} ${JSON.stringify(row.params ?? {})}` : (row.judgePrompt ?? '') }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openCustomEdit(row)">编辑</el-button>
            <el-button size="small" link type="danger" @click="removeCustom(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无自定义评测器" :image-size="70" />
    </el-drawer>

    <!-- 自定义评测器表单对话框 -->
    <el-dialog
      v-model="customDialog"
      :title="editingCustomId == null ? '新建自定义评测器' : '编辑自定义评测器'"
      width="620px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px">
        <el-form-item label="名称">
          <el-input v-model="customForm.name" placeholder="如：禁词检测" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="customForm.category" style="width: 100%">
            <el-option label="规则（Java 确定性判分）" value="rule" />
            <el-option label="LLM-Judge（提示词判分）" value="llm_judge" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="customForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <template v-if="customForm.category === 'rule'">
          <el-form-item label="规则类型">
            <el-select v-model="customForm.ruleType" style="width: 100%">
              <el-option label="关键词包含 keyword_contains" value="keyword_contains" />
              <el-option label="正则匹配 regex_match" value="regex_match" />
              <el-option label="长度区间 length_between" value="length_between" />
            </el-select>
          </el-form-item>
          <el-form-item label="规则参数">
            <el-input v-model="customParamsText" type="textarea" :rows="5" placeholder='keyword_contains: { "keywords": ["词1"], "all": false, "prohibit": false }\nregex_match: { "pattern": "..." }\nlength_between: { "min": 2, "max": 6 }' class="json-input" />
          </el-form-item>
        </template>
        <el-form-item v-else label="判分提示词">
          <el-input v-model="customForm.judgePrompt" type="textarea" :rows="6" placeholder="含 {question}/{response}/{reference} 占位，要求输出 0-100" class="json-input" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="customForm.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="customDialog = false">取消</el-button>
        <el-button type="primary" :loading="customSaving" @click="saveCustom">保存</el-button>
      </template>
    </el-dialog>

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
              <el-tag size="small" :type="caseCategoryType(c.category)" class="case-category-tag">{{ CATEGORY_LABEL[c.category ?? 'basic'] }}</el-tag>
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
            <div v-if="c.expectedKbHits && c.expectedKbHits.length" class="case-meta">期望知识命中（RAG）：{{ c.expectedKbHits.join('；') }}</div>
            <div v-if="c.judgeRule != null" class="case-meta">判分规则：{{ jsonText(c.judgeRule) }}</div>
            <div v-if="c.dialogue && c.dialogue.length" class="case-meta">多轮对话：{{ c.dialogue.length }} 轮（{{ c.dialogue.map((t) => t.role === 'user' ? '用户' : '助手').join(' → ') }}）</div>
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
        <el-form-item label="分层">
          <el-select v-model="caseForm.category" style="width: 160px">
            <el-option label="基础（目标 ≥40%）" value="basic" />
            <el-option label="边界（目标 ≥30%）" value="edge" />
            <el-option label="真实（目标 ≥30%）" value="real" />
          </el-select>
          <span class="form-hint">当前数据集：{{ caseCategoryDist.length ? caseCategoryDist.map((d) => `${d.label}${d.count}（${d.pct}%）`).join(' / ') : '暂无用例' }}</span>
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
        <el-form-item label="期望知识命中（RAG）">
          <el-input
            v-model="expectedKbHitsText"
            type="textarea"
            :rows="4"
            :disabled="!isExecuteCasesDataset"
            placeholder="每行一条期望命中片段（可空）"
          />
          <span class="form-hint">仅 execute 模式与 rag_hit_rate 评测器使用：填知识库中应被检索到的要点，由 search_kb 实际检索命中判定{{ isExecuteCasesDataset ? '' : '（当前数据集为 openjudge，不适用）' }}</span>
        </el-form-item>
        <el-form-item label="预置响应">
          <el-input v-model="caseForm.providedResponse" type="textarea" :rows="3" placeholder="openjudge 判分对象（可空 = 不适用）" />
        </el-form-item>
        <el-form-item label="判分规则">
          <el-input v-model="judgeRuleText" type="textarea" :rows="3" placeholder='JSON（可空）：该用例判分附加约束，如 { "min": 0.5 } 或约束数组' class="json-input" />
          <span class="form-hint">judge_rule：覆盖/追加该用例的评测规则；留空 = 不配置</span>
        </el-form-item>
        <el-form-item label="多轮对话">
          <div class="dialogue-editor">
            <div v-for="(d, i) in dialogueRows" :key="i" class="dialogue-row">
              <div class="dialogue-row-head">
                <span class="muted-text">第 {{ i + 1 }} 轮</span>
                <el-select v-model="d.role" style="width: 110px" size="small">
                  <el-option label="用户" value="user" />
                  <el-option label="助手" value="assistant" />
                </el-select>
                <el-button size="small" link type="danger" @click="removeDialogueRow(i)">删除</el-button>
              </div>
              <el-input v-model="d.content" type="textarea" :rows="2" placeholder="对话内容（question 为首轮用户消息，各轮为后续消息；纯空白轮自动跳过）" />
              <el-collapse class="dialogue-collapse">
                <el-collapse-item :title="`工具调用（tool_use${d.toolUseText.trim() ? '' : ' · 空' }）`" :name="`use-${i}`">
                  <el-input v-model="d.toolUseText" type="textarea" :rows="2" placeholder="JSON（可空）" class="json-input" />
                </el-collapse-item>
                <el-collapse-item :title="`工具返回（tool_result${d.toolResultText.trim() ? '' : ' · 空' }）`" :name="`result-${i}`">
                  <el-input v-model="d.toolResultText" type="textarea" :rows="2" placeholder="JSON（可空）" class="json-input" />
                </el-collapse-item>
              </el-collapse>
            </div>
            <el-button size="small" @click="addDialogueRow">+ 添加轮次</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDialog = false">取消</el-button>
        <el-button type="primary" :loading="caseSaving" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>

    <!-- 基线回归对比对话框（指标对比 / 分层对比 / Top 退化样例） -->
    <el-dialog v-model="compareDialogVisible" title="基线回归对比" width="820px" :close-on-click-modal="false">
      <template v-if="compareTarget">
        <div class="compare-head">
          <span class="drawer-title">当前报告：#{{ compareTarget.id }} {{ compareTarget.name }}（{{ fmtTime(compareTarget.createdAt) }}）</span>
        </div>
        <el-alert
          v-if="!baselineCandidates.length"
          type="info"
          :closable="false"
          show-icon
          title="该数据集暂无其他历史报告，无法进行基线对比"
          class="compare-tip"
        />
        <template v-else>
          <el-tabs v-model="compareTab">
            <el-tab-pane label="指标对比" name="metrics">
              <div class="run-form compare-form">
                <span class="rounds-label">基线报告</span>
                <el-select v-model="compareBaselineId" placeholder="选择基线报告" filterable style="width: 420px">
                  <el-option
                    v-for="b in baselineCandidates"
                    :key="b.id"
                    :label="`#${b.id} ${b.name}（${fmtTime(b.createdAt)} · ${b.summary.verdict} ${b.summary.score}）`"
                    :value="b.id"
                  />
                </el-select>
                <el-button type="primary" :loading="compareLoading" @click="doCompare">开始对比</el-button>
              </div>
              <template v-if="compareResult">
                <div class="compare-meta">
                  <span class="compare-meta-item">基线：{{ compareResult.baseline.name }}（{{ fmtTime(compareResult.baseline.createdAt) }}）</span>
                  <span class="compare-arrow">→</span>
                  <span class="compare-meta-item">当前：{{ compareResult.current.name }}（{{ fmtTime(compareResult.current.createdAt) }}）</span>
                </div>
                <el-table :data="compareResult.metrics" size="small" border class="mini-table">
                  <el-table-column label="评测器" width="230">
                    <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
                  </el-table-column>
                  <el-table-column label="类别" width="100">
                    <template #default="{ row }">
                      <el-tag :type="row.category === 'rule' ? 'success' : 'warning'" size="small">{{ row.category === 'rule' ? '规则' : 'LLM 判分' }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="基线" width="110" align="center">
                    <template #default="{ row }">
                      <span :class="row.baseline == null ? 'muted-text' : ''">{{ fmtPct(row.baseline) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="当前" width="110" align="center">
                    <template #default="{ row }">
                      <span :class="row.current == null ? 'muted-text' : ''">{{ fmtPct(row.current) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="Delta" width="150" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.delta != null" :type="row.delta > 0 ? 'success' : row.delta < 0 ? 'danger' : 'info'" size="small">
                        {{ row.delta > 0 ? '↑ 改进' : row.delta < 0 ? '↓ 回归' : '→ 持平' }} {{ fmtDelta(row.delta) }}
                      </el-tag>
                      <span v-else class="muted-text">-</span>
                    </template>
                  </el-table-column>
                </el-table>
              </template>
              <el-empty v-else description="点击「开始对比」查看指标差异" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane label="分层对比" name="layering">
              <div class="run-form compare-form">
                <span class="rounds-label">基线报告</span>
                <el-select v-model="compareBaselineId" placeholder="选择基线报告" filterable style="width: 360px">
                  <el-option
                    v-for="b in baselineCandidates"
                    :key="b.id"
                    :label="`#${b.id} ${b.name}（${fmtTime(b.createdAt)} · ${b.summary.verdict} ${b.summary.score}）`"
                    :value="b.id"
                  />
                </el-select>
                <el-button type="primary" :loading="compareLoading" @click="doCompare">开始对比</el-button>
              </div>
              <div class="run-form compare-form compare-layer-form">
                <span class="rounds-label">分层过滤</span>
                <el-radio-group v-model="compareLayerPicker" @change="onCompareLayerChange">
                  <el-radio-button label="all">全部</el-radio-button>
                  <el-radio-button label="basic">基础</el-radio-button>
                  <el-radio-button label="edge">边界</el-radio-button>
                  <el-radio-button label="real">真实</el-radio-button>
                </el-radio-group>
                <span class="muted-text">当前口径：{{ compareLayerLabel }}（切换自动重跑）</span>
              </div>
              <template v-if="compareResult">
                <div class="compare-meta">
                  <span class="compare-meta-item">基线：{{ compareResult.baseline.name }}（{{ fmtTime(compareResult.baseline.createdAt) }}）· 口径 {{ compareLayerLabel }}</span>
                  <span class="compare-arrow">→</span>
                  <span class="compare-meta-item">当前：{{ compareResult.current.name }}（{{ fmtTime(compareResult.current.createdAt) }}）</span>
                </div>
                <el-table :data="compareResult.metrics" size="small" border class="mini-table">
                  <el-table-column label="评测器" width="230">
                    <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
                  </el-table-column>
                  <el-table-column label="类别" width="100">
                    <template #default="{ row }">
                      <el-tag :type="row.category === 'rule' ? 'success' : 'warning'" size="small">{{ row.category === 'rule' ? '规则' : 'LLM 判分' }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="基线" width="110" align="center">
                    <template #default="{ row }">
                      <span :class="row.baseline == null ? 'muted-text' : ''">{{ fmtPct(row.baseline) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="当前" width="110" align="center">
                    <template #default="{ row }">
                      <span :class="row.current == null ? 'muted-text' : ''">{{ fmtPct(row.current) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="Delta" width="150" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.delta != null" :type="row.delta > 0 ? 'success' : row.delta < 0 ? 'danger' : 'info'" size="small">
                        {{ row.delta > 0 ? '↑ 改进' : row.delta < 0 ? '↓ 回归' : '→ 持平' }} {{ fmtDelta(row.delta) }}
                      </el-tag>
                      <span v-else class="muted-text">-</span>
                    </template>
                  </el-table-column>
                </el-table>
              </template>
              <el-empty v-else description="点击「开始对比」查看分层口径差异" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane label="Top 退化样例" name="degraded">
              <div class="run-form compare-form">
                <span class="rounds-label">基线报告</span>
                <el-select v-model="compareBaselineId" placeholder="选择基线报告" filterable style="width: 420px">
                  <el-option
                    v-for="b in baselineCandidates"
                    :key="b.id"
                    :label="`#${b.id} ${b.name}（${fmtTime(b.createdAt)} · ${b.summary.verdict} ${b.summary.score}）`"
                    :value="b.id"
                  />
                </el-select>
                <el-button type="primary" :loading="compareLoading" @click="doCompare">开始对比</el-button>
              </div>
              <template v-if="compareResult">
                <div class="compare-meta">
                  <span class="compare-meta-item">基线：{{ compareResult.baseline.name }}（{{ fmtTime(compareResult.baseline.createdAt) }}）</span>
                  <span class="compare-arrow">→</span>
                  <span class="compare-meta-item">当前：{{ compareResult.current.name }}（{{ fmtTime(compareResult.current.createdAt) }}）</span>
                </div>
                <el-table v-if="compareResult.topDegradedSamples && compareResult.topDegradedSamples.length" :data="compareResult.topDegradedSamples" size="small" border class="mini-table">
                  <el-table-column label="样例" width="90">
                    <template #default="{ row }"><el-tag size="small" type="info">#{{ row.caseSeq }}</el-tag></template>
                  </el-table-column>
                  <el-table-column label="当前" width="100" align="center">
                    <template #default="{ row }">{{ fmtPct(row.auto) }}</template>
                  </el-table-column>
                  <el-table-column label="基线" width="100" align="center">
                    <template #default="{ row }">{{ fmtPct(row.baseline) }}</template>
                  </el-table-column>
                  <el-table-column label="Delta" width="110" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.delta != null" :type="row.delta > 0 ? 'success' : row.delta < 0 ? 'danger' : 'info'" size="small">{{ fmtDelta(row.delta) }}</el-tag>
                      <span v-else class="muted-text">-</span>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-else description="无退化样例（缺基线或无显著退化）" :image-size="60" />
              </template>
              <el-empty v-else description="点击「开始对比」查看退化样例" :image-size="60" />
            </el-tab-pane>
          </el-tabs>
        </template>
      </template>
      <template #footer>
        <el-button @click="compareDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 报告详情抽屉 -->
    <el-drawer v-model="reportDetailVisible" :title="`报告详情`" size="640px">
      <template v-if="reportDetail">
        <div class="drawer-head">
          <el-tag :type="verdictType(reportDetail.summary.verdict)" size="small">{{ reportDetail.summary.verdict }}</el-tag>
          <span class="drawer-title">总分 {{ reportDetail.summary.score }} · 测试 {{ reportDetail.testedCases }}/{{ reportDetail.totalCases }} 例</span>
        </div>
        <div class="report-actions">
          <el-button size="small" type="primary" plain :loading="rerunning" @click="doRerun">复现评测</el-button>
          <el-button size="small" type="warning" plain @click="openReviews">人工复评</el-button>
          <el-button size="small" type="info" plain @click="openCalibration">校准对比</el-button>
        </div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="数据集">#{{ reportDetail.datasetId }} {{ reportDetail.name }}</el-descriptions-item>
          <el-descriptions-item label="模式">{{ reportDetail.mode }}</el-descriptions-item>
          <el-descriptions-item label="数据集版本">
            {{ reportDetail.datasetVersionNo != null ? `v${reportDetail.datasetVersionNo}` : (reportDetail.datasetVersionId != null ? '实时工作区' : '-') }}
          </el-descriptions-item>
          <el-descriptions-item label="模型">{{ reportDetail.model ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="置信度">{{ reportDetail.confidence }}</el-descriptions-item>
          <el-descriptions-item label="LLM 判分次数">{{ reportDetail.judgeRounds ?? 1 }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ reportDetail.createdBy }}</el-descriptions-item>
          <el-descriptions-item label="追踪 ID" :span="2">
            <span v-if="reportDetail.traceId" class="trace-row">
              <code class="metric-code">{{ reportDetail.traceId }}</code>
              <el-button size="small" link type="primary" @click="gotoCockpitTraces(reportDetail)">查看会话追踪</el-button>
            </span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="时间">{{ fmtTime(reportDetail.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="recommendation">
          <h4 class="drawer-title">上线建议</h4>
          <div class="recommendation-row">
            <el-tag :type="recommendationType(recommendation.verdict)" size="small">{{ recommendationLabel(recommendation.verdict) }}</el-tag>
            <span class="recommendation-reason">{{ recommendation.reason }}</span>
          </div>
        </template>
        <template v-if="hasLayerStats">
          <h4 class="drawer-title">分层统计（目标 基础 ≥40% / 边界 ≥30% / 真实 ≥30%）</h4>
          <div class="layer-stats-grid">
            <div v-for="l in layerStats" :key="l.key" class="layer-stat-card" :style="{ borderTopColor: l.color }">
              <div class="layer-stat-label" :style="{ color: l.color }">{{ l.label }}</div>
              <div class="layer-stat-num">{{ l.tested }}/{{ l.count }} 通过</div>
              <div class="layer-stat-pct">{{ fmtPct(l.passRate) }}</div>
            </div>
          </div>
        </template>
        <template v-if="executionRows.length">
          <h4 class="drawer-title">执行统计</h4>
          <el-table :data="executionRows" size="small" border class="mini-table">
            <el-table-column prop="label" label="指标" width="150" />
            <el-table-column prop="value" label="数值" min-width="120" />
          </el-table>
        </template>
        <template v-if="topRegressions.length">
          <h4 class="drawer-title">Top 退化指标（vs 基线）</h4>
          <div v-for="r in topRegressions" :key="r.metric" class="regression-block">
            <div class="regression-head">
              <code class="metric-code">{{ r.metric }}</code>
              <span>当前 {{ fmtPct(r.current) }}</span>
              <span>基线 {{ fmtPct(r.baseline) }}</span>
              <el-tag :type="(r.delta ?? 0) >= 0 ? 'success' : 'danger'" size="small">{{ fmtDelta(r.delta) }}</el-tag>
            </div>
          </div>
        </template>
        <template v-if="topRegressionSamples.length">
          <h4 class="drawer-title">Top 退化样例（vs 基线）</h4>
          <div class="regression-samples">
            <el-tag
              v-for="s in topRegressionSamples"
              :key="s.seq"
              size="small"
              class="sample-jump-tag"
              @click="openReportSample(s.seq)"
            >#{{ s.seq }} {{ fmtPct(s.auto) }}</el-tag>
          </div>
        </template>
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

    <!-- 逐样本判分抽屉（任务模式 / 报告模式 + 执行轨迹 transcript） -->
    <el-drawer v-model="sampleDrawerVisible" title="逐样本判分" size="820px">
      <template v-if="sampleTask">
        <div class="drawer-head">
          <el-tag :type="taskStatusType[sampleTask.status]" size="small">{{ taskStatusLabel[sampleTask.status] }}</el-tag>
          <span class="drawer-title">{{ sampleTask.name }} · 已测 {{ sampleTask.testedCases }}/{{ sampleTask.totalCases }} 例</span>
        </div>
        <div v-loading="transcriptLoading" v-if="sampleTask.sampleResults && sampleTask.sampleResults.length" class="sample-list">
          <div v-for="s in sampleTask.sampleResults" :key="s.seq" class="sample-block">
            <div class="sample-head">
              <el-tag size="small" type="info">#{{ s.seq }}</el-tag>
              <span class="case-question">{{ s.question }}</span>
              <el-tag :type="s.mode === 'execute' ? 'warning' : 'success'" size="small">{{ s.mode }}</el-tag>
              <span v-if="s.latency_ms != null" class="muted-text">延迟 {{ s.latency_ms }} ms</span>
              <el-button v-if="s.mode === 'execute'" size="small" link type="primary" @click="loadTranscriptFor(s.seq)">执行轨迹</el-button>
            </div>
            <div class="case-meta">回复：{{ s.actual_response || '（空，不适用）' }}</div>
            <template v-if="s.mode === 'execute'">
              <div v-if="transcripts[s.seq]" class="transcript-block">
                <div class="transcript-title">执行轨迹（{{ transcripts[s.seq].length }} 轮）</div>
                <div v-for="t in transcripts[s.seq]" :key="t.turnNo" class="transcript-turn">
                  <div class="transcript-turn-head">
                    <el-tag size="small" :type="turnRoleType(t.role)">#{{ t.turnNo }} {{ turnRoleLabel(t.role) }}</el-tag>
                  </div>
                  <div v-if="t.text" class="transcript-text">{{ t.text }}</div>
                  <el-collapse class="transcript-collapse">
                    <el-collapse-item v-if="t.thinking" :title="'思考'" :name="`think-${s.seq}-${t.turnNo}`">
                      <pre class="transcript-pre">{{ t.thinking }}</pre>
                    </el-collapse-item>
                    <el-collapse-item v-if="t.toolUse != null" :title="'工具调用'" :name="`use-${s.seq}-${t.turnNo}`">
                      <pre class="transcript-pre">{{ jsonText(t.toolUse) }}</pre>
                    </el-collapse-item>
                    <el-collapse-item v-if="t.toolResult != null" :title="'工具返回'" :name="`result-${s.seq}-${t.turnNo}`">
                      <pre class="transcript-pre">{{ jsonText(t.toolResult) }}</pre>
                    </el-collapse-item>
                  </el-collapse>
                </div>
              </div>
              <el-alert v-else-if="transcriptFailed[s.seq]" type="info" :closable="false" show-icon title="执行轨迹不可用（旧后端无 transcript 端点）" class="transcript-fail" />
            </template>
            <el-table :data="s.metrics" size="small" border class="mini-table">
              <el-table-column label="评测器" width="230">
                <template #default="{ row }">
                  <code class="metric-code">{{ row.metric }}</code>
                  <span v-if="row.round_scores && row.round_scores.length" class="round-scores">轮次 {{ row.round_scores.join('/') }}</span>
                </template>
              </el-table-column>
              <el-table-column label="类别" width="100">
                <template #default="{ row }">
                  <el-tag :type="row.category === 'rule' ? 'success' : 'warning'" size="small">{{ row.category === 'rule' ? '规则' : 'LLM 判分' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="得分" width="150">
                <template #default="{ row }">
                  <el-progress :percentage="Math.round(row.score * 100)" :stroke-width="10" />
                </template>
              </el-table-column>
              <el-table-column label="通过" width="80" align="center">
                <template #default="{ row }">
                  <span :class="row.passed ? 'pass-mark' : 'fail-mark'">{{ row.passed ? '✓' : '✗' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="理由" min-width="200">
                <template #default="{ row }">
                  <span v-if="row.reason" class="reason-text">{{ row.reason }}</span>
                  <span v-else class="muted-text">-</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        <el-empty v-else-if="!transcriptLoading" description="该任务暂无样本明细" :image-size="60" />
      </template>
      <template v-else-if="sampleReport && sampleSeq != null">
        <div class="drawer-head">
          <el-tag :type="verdictType(sampleReport.summary.verdict)" size="small">{{ sampleReport.summary.verdict }}</el-tag>
          <span class="drawer-title">报告 #{{ sampleReport.id }} · 样例 #{{ sampleSeq }}（{{ sampleReport.name }}）</span>
        </div>
        <div v-loading="transcriptLoading">
          <div v-if="transcripts[sampleSeq]" class="transcript-block">
            <div class="transcript-title">执行轨迹（{{ transcripts[sampleSeq].length }} 轮）</div>
            <div v-for="t in transcripts[sampleSeq]" :key="t.turnNo" class="transcript-turn">
              <div class="transcript-turn-head">
                <el-tag size="small" :type="turnRoleType(t.role)">#{{ t.turnNo }} {{ turnRoleLabel(t.role) }}</el-tag>
              </div>
              <div v-if="t.text" class="transcript-text">{{ t.text }}</div>
              <el-collapse class="transcript-collapse">
                <el-collapse-item v-if="t.thinking" :title="'思考'" :name="`rthink-${sampleSeq}-${t.turnNo}`">
                  <pre class="transcript-pre">{{ t.thinking }}</pre>
                </el-collapse-item>
                <el-collapse-item v-if="t.toolUse != null" :title="'工具调用'" :name="`ruse-${sampleSeq}-${t.turnNo}`">
                  <pre class="transcript-pre">{{ jsonText(t.toolUse) }}</pre>
                </el-collapse-item>
                <el-collapse-item v-if="t.toolResult != null" :title="'工具返回'" :name="`rresult-${sampleSeq}-${t.turnNo}`">
                  <pre class="transcript-pre">{{ jsonText(t.toolResult) }}</pre>
                </el-collapse-item>
              </el-collapse>
            </div>
          </div>
          <el-alert v-else-if="transcriptFailed[sampleSeq]" type="info" :closable="false" show-icon title="执行轨迹不可用（旧后端无 transcript 端点）" class="transcript-fail" />
          <el-empty v-else description="正在加载执行轨迹…" :image-size="60" />
        </div>
      </template>
    </el-drawer>

    <!-- 数据集版本管理对话框 -->
    <el-dialog v-model="versionDialog" title="数据集版本管理" width="760px" :close-on-click-modal="false">
      <div class="drawer-head">
        <span class="drawer-title">版本 = 发布时用例的不可变快照（发布后增改用例不进旧版本）；运行评测可绑定版本，报告记录所用版本</span>
        <el-button size="small" type="primary" :loading="publishing" @click="publishVersion">发布新版本</el-button>
      </div>
      <el-table v-loading="versionsLoading" :data="versionRows" border stripe size="small">
        <el-table-column label="版本号" width="90">
          <template #default="{ row }"><code class="metric-code">v{{ row.versionNo }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" size="small">{{ row.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="caseCount" label="用例数" width="80" />
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column prop="createdBy" label="创建人" width="110" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="viewVersionCases(row)">查看快照</el-button>
            <el-button size="small" link type="danger" @click="removeVersion(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="尚未发布版本：用例为空或有未发布改动时不可发布" :image-size="70" />
        </template>
      </el-table>
    </el-dialog>

    <!-- 版本快照用例（只读）抽屉 -->
    <el-drawer v-model="versionCasesDrawer" size="720px">
      <template #header>
        <span class="el-drawer__title">版本快照用例</span>
        <span class="panel-sub">{{ versionCasesMeta }}</span>
      </template>
      <div v-loading="versionCasesLoading">
        <div v-if="versionCases.length">
          <div v-for="c in versionCases" :key="c.id" class="case-row">
            <div class="case-row-head">
              <el-tag size="small" type="info">#{{ c.seq ?? c.id }}</el-tag>
              <el-tag size="small" :type="caseCategoryType(c.category)" class="case-category-tag">{{ CATEGORY_LABEL[c.category ?? 'basic'] }}</el-tag>
              <span class="case-question">{{ c.question }}</span>
            </div>
            <div v-if="c.systemPrompt" class="case-meta">系统提示：{{ c.systemPrompt }}</div>
            <div v-if="c.providedResponse" class="case-meta">预置响应：{{ c.providedResponse }}</div>
            <div v-if="c.expectedOutput" class="case-meta">期望输出：{{ jsonText(c.expectedOutput) }}</div>
            <div v-if="c.expectedKbHits && c.expectedKbHits.length" class="case-meta">期望知识命中（RAG）：{{ c.expectedKbHits.join('；') }}</div>
            <div v-if="c.judgeRule != null" class="case-meta">判分规则：{{ jsonText(c.judgeRule) }}</div>
            <div v-if="c.dialogue && c.dialogue.length" class="case-meta">多轮对话：{{ c.dialogue.length }} 轮（{{ c.dialogue.map((t) => t.role === 'user' ? '用户' : '助手').join(' → ') }}）</div>
          </div>
        </div>
        <el-empty v-else-if="!versionCasesLoading" description="该版本快照暂无用例" :image-size="60" />
      </div>
    </el-drawer>

    <!-- 人工复评抽屉（报告详情驱动；0-100 输入 → 提交归一 0-1） -->
    <el-drawer v-model="reviewDrawer" title="人工复评" size="860px">
      <div v-loading="reviewsLoading">
        <div class="drawer-head">
          <span class="drawer-title">分数按 0-100 输入、提交时归一；选「全指标」= 对该样例整分；再次保存同一样例 = 改判；自动分摘要来自校准 topDeltas</span>
          <el-button size="small" type="primary" @click="addReviewRow">添加复评</el-button>
        </div>
        <div v-for="row in reviewRows" :key="row.key" class="review-row">
          <el-input-number v-model="row.caseSeq" :min="1" placeholder="样例序号" style="width: 110px" />
          <el-select v-model="row.metric" style="width: 150px" placeholder="指标">
            <el-option v-for="m in reportMetricOptions" :key="m" :label="m === '*' ? '全指标' : m" :value="m" />
          </el-select>
          <el-input-number v-model="row.score100" :min="0" :max="100" :step="1" style="width: 110px" />
          <el-select v-model="row.verdict" style="width: 100px" placeholder="结论">
            <el-option label="（不填）" value="" />
            <el-option label="PASS" value="PASS" />
            <el-option label="WARN" value="WARN" />
            <el-option label="FAIL" value="FAIL" />
          </el-select>
          <el-input v-model="row.note" placeholder="备注（可空）" style="width: 190px" />
          <el-button size="small" type="primary" :loading="reviewSaving" @click="saveReviewRow(row)">保存</el-button>
          <el-button size="small" link type="danger" @click="removeReviewRow(row)">删除</el-button>
          <span v-if="autoScoreHint(row)" class="form-hint">{{ autoScoreHint(row) }}</span>
        </div>
        <el-button v-if="!reviewRows.length" size="small" @click="addReviewRow" class="review-add-empty">+ 添加第一条复评</el-button>
      </div>
    </el-drawer>

    <!-- 校准对比抽屉（人工 vs 自动） -->
    <el-drawer v-model="calibrationDrawer" title="校准对比（人工 vs 自动）" size="820px">
      <div v-loading="calibrationLoading">
        <template v-if="calibration.length">
          <div v-for="c in calibrationMetrics" :key="c.metric" class="calib-block">
            <div class="calib-head">
              <code class="metric-code">{{ c.metric }}</code>
              <span class="panel-sub">n = {{ c.n }}</span>
              <span class="calib-col">自动均值 {{ fmtPct(c.meanAuto) }}</span>
              <span class="calib-col">人工均值 {{ fmtPct(c.meanHuman) }}</span>
              <span class="calib-col">平均偏差 {{ fmtPct(c.meanAbsDiff) }}</span>
              <span class="calib-col">一致率 {{ fmtPct(c.agreementRate) }}</span>
            </div>
            <el-collapse v-if="c.topDeltas && c.topDeltas.length">
              <el-collapse-item :title="`Top 偏差样本（${c.topDeltas.length}）`" :name="`calib-${c.metric}`">
                <el-table :data="c.topDeltas" size="small" border class="mini-table">
                  <el-table-column label="样例" width="90">
                    <template #default="{ row }">
                      <el-tag size="small" type="info" class="sample-jump-tag" @click="openReportSample(row.caseSeq)">#{{ row.caseSeq }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="自动" width="100" align="center">
                    <template #default="{ row }">{{ fmtPct(row.auto) }}</template>
                  </el-table-column>
                  <el-table-column label="人工" width="100" align="center">
                    <template #default="{ row }">{{ fmtPct(row.human) }}</template>
                  </el-table-column>
                  <el-table-column label="偏差" width="110" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.delta != null" :type="row.delta > 0 ? 'danger' : row.delta < 0 ? 'success' : 'info'" size="small">{{ fmtDelta(row.delta) }}</el-tag>
                      <span v-else class="muted-text">-</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="操作" width="100">
                    <template #default="{ row }">
                      <el-button size="small" link type="primary" @click="openReportSample(row.caseSeq)">查看轨迹</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-collapse-item>
            </el-collapse>
          </div>
        </template>
        <el-empty v-else-if="!calibrationLoading" description="暂无人工复评数据，先添加复评后再校准" :image-size="60" />
      </div>
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
.group-block {
  margin-bottom: 14px;
}
.group-block:last-child {
  margin-bottom: 0;
}
.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.group-title {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
}
.group-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.rounds-label {
  color: #606266;
  font-size: 13px;
  white-space: nowrap;
}
.import-tip {
  margin-bottom: 10px;
}
.json-input :deep(textarea) {
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
.trace-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.trace-code {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  background: #fdf6ec;
  border-radius: 3px;
  padding: 1px 4px;
}
.case-row {
  padding: 10px 0;
  border-bottom: 1px dashed #ebeef5;
}
.case-row:last-child {
  border-bottom: none;
}
.case-row-head {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.case-question {
  font-size: 13px;
  color: #303133;
  line-height: 20px;
}
.case-meta {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 18px;
}
.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.drawer-title {
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
.run-status {
  margin-bottom: 14px;
}
.rag-hint {
  margin: 0 0 14px;
}
.run-progress {
  margin: 6px 0;
}
.task-error {
  margin-top: 8px;
}
.task-progress-text {
  color: #909399;
  font-size: 12px;
  margin-left: 8px;
}
.compare-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 4px;
}
.round-scores {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}
.compare-tip {
  margin: 10px 0 0;
}
.compare-form {
  margin: 12px 0;
}
.compare-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 10px;
  font-size: 13px;
  color: #303133;
}
.compare-meta-item {
  background: #f5f7fa;
  border-radius: 4px;
  padding: 6px 10px;
}
.compare-arrow {
  color: #909399;
}
.sample-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sample-block {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
}
.sample-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.pass-mark {
  color: #67c23a;
  font-weight: 600;
}
.fail-mark {
  color: #f56c6c;
  font-weight: 600;
}
.reason-text {
  color: #606266;
  font-size: 12px;
  line-height: 18px;
  white-space: pre-wrap;
  word-break: break-all;
}
.muted-text {
  color: #909399;
}

/* ---------- 分层分布（三段色堆叠条 + 40/30/30 达标高亮） ---------- */
.layer-bar {
  display: flex;
  height: 8px;
  border-radius: 4px;
  overflow: hidden;
  background: #ebeef5;
}
.layer-seg {
  height: 100%;
  transition: width 0.2s;
}
.layer-nums {
  display: flex;
  gap: 2px;
  margin-top: 2px;
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
}
.layer-sep {
  margin: 0 4px;
  color: #c0c4cc;
}
.layer-hit {
  color: #409eff;
  font-weight: 600;
}
.layer-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

/* ---------- 评测看板 ---------- */
.dash-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.dash-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
}
.dash-card-title {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
  margin-bottom: 8px;
}
.dash-empty {
  color: #909399;
  font-size: 12px;
  padding: 8px 0;
}
.dash-cost .cost-row {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 12px;
  border-bottom: 1px dashed #f0f2f5;
}
.dash-cost .cost-row:last-child {
  border-bottom: none;
}
.cost-k {
  color: #909399;
}
.cost-v {
  color: #303133;
  font-weight: 500;
}
.dash-reg-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 12px;
  border-bottom: 1px dashed #f0f2f5;
}
.dash-reg-row:last-child {
  border-bottom: none;
}
.dash-reg-val {
  margin-left: auto;
  color: #303133;
}

/* ---------- 运行区/报告详情 ---------- */
.run-version-note {
  margin: 0 0 8px;
  font-size: 12px;
}
.report-actions {
  display: flex;
  gap: 8px;
  margin: 0 0 12px;
}
.recommendation-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 13px;
}
.recommendation-reason {
  color: #303133;
  line-height: 20px;
}
.layer-stats-grid {
  display: flex;
  gap: 10px;
}
.layer-stat-card {
  flex: 1;
  border: 1px solid #ebeef5;
  border-top: 3px solid #67c23a;
  border-radius: 6px;
  padding: 8px 10px;
  text-align: center;
}
.layer-stat-label {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 4px;
}
.layer-stat-num {
  font-size: 12px;
  color: #606266;
}
.layer-stat-pct {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-top: 2px;
}
.regression-block {
  margin-bottom: 10px;
  padding: 8px 10px;
  background: #fdf6ec;
  border-radius: 4px;
}
.regression-head {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: #606266;
}
.regression-samples {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.sample-jump-tag {
  cursor: pointer;
}

/* ---------- 用例表单：多轮对话 ---------- */
.dialogue-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dialogue-row {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
}
.dialogue-row-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.dialogue-collapse {
  margin-top: 6px;
}
.case-category-tag {
  flex-shrink: 0;
}

/* ---------- 执行轨迹 transcript ---------- */
.transcript-block {
  margin: 6px 0 10px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
  background: #fafafa;
}
.transcript-title {
  font-weight: 600;
  font-size: 12px;
  color: #303133;
  margin-bottom: 6px;
}
.transcript-turn {
  margin-bottom: 6px;
}
.transcript-turn-head {
  margin-bottom: 2px;
}
.transcript-text {
  font-size: 13px;
  color: #303133;
  line-height: 20px;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 2px 0 4px;
}
.transcript-collapse {
  margin-top: 2px;
}
.transcript-pre {
  margin: 0;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  line-height: 18px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow: auto;
}
.transcript-fail {
  margin: 6px 0 10px;
}

/* ---------- 人工复评 / 校准 ---------- */
.review-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px dashed #ebeef5;
  flex-wrap: wrap;
}
.review-add-empty {
  margin-top: 8px;
}
.calib-block {
  margin-bottom: 14px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
}
.calib-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
.calib-col {
  font-size: 12px;
  color: #606266;
  background: #f5f7fa;
  border-radius: 3px;
  padding: 3px 8px;
}
.compare-layer-form {
  margin-top: 0;
}
</style>