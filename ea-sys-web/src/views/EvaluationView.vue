<script setup lang="ts">
/**
 * 评测中心（M8）：数据集管理 + jsonl 导入/预览 + 用例管理 + 三组评测器面板（规则/LLM-Judge/自定义，
 * 每组全选/清空）+ 异步任务运行（POST /tasks 202 立即返回 → ~3s 轮询进度，状态徽章/进度条，
 * COMPLETED 自动并入结果区，FAILED 错误横幅）+ 运行任务列表（状态徽章/进度/查看结果/取消）+
 * 逐样本判分抽屉（score 百分比 + ✓/✗ + LLM reason）+ 报告基线回归对比（红绿 delta）+ 报告回看
 * （TraceID 联动驾驶舱）。execute 模式真实运行被测智能体（assistant / workflow-dialogue）。
 * 数据：GET/POST/PUT/DELETE /api/evaluations/{datasets,cases,import,custom-evaluators,tasks,reports}。
 */
import { computed, onUnmounted, reactive, ref } from 'vue'
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
  deleteReport,
  getReport,
  getTask,
  importCases,
  listCases,
  listCustomEvaluators,
  listDatasets,
  listReports,
  listTasks,
  updateCase,
  updateCustomEvaluator,
  updateDataset,
} from '../api/evaluation'
import type {
  CaseSaveRequest,
  CaseView,
  CustomEvaluatorView,
  CustomSaveRequest,
  DatasetSaveRequest,
  DatasetView,
  ImportResultView,
  ReportCompareView,
  ReportView,
  TaskView,
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
    return rows && rows.some((t) => t.status === 'PENDING' || t.status === 'RUNNING') ? 3000 : false
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

/* ---------- 评测运行（异步任务流） ---------- */
const runDatasetId = ref<number | undefined>(undefined)
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

const runnableDatasets = computed(() =>
  (datasets.value ?? []).filter((d) => d.status === 'ENABLED' && d.caseCount > 0),
)

const taskStatusType: Record<TaskView['status'], 'info' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'info',
  RUNNING: 'warning',
  COMPLETED: 'success',
  FAILED: 'danger',
  CANCELED: 'info',
}
const taskStatusLabel: Record<TaskView['status'], string> = {
  PENDING: '排队中',
  RUNNING: '运行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消',
}
const isTaskActive = (t: TaskView | null | undefined): boolean =>
  !!t && (t.status === 'PENDING' || t.status === 'RUNNING')
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

/* ---------- 逐样本判分抽屉 ---------- */
const sampleDrawerVisible = ref(false)
const sampleTask = ref<TaskView | null>(null)

function openSamples(t: TaskView) {
  sampleTask.value = t
  sampleDrawerVisible.value = true
}

/* ---------- 基线回归对比 ---------- */
const compareDialogVisible = ref(false)
const compareTarget = ref<ReportView | null>(null)
const compareBaselineId = ref<number | null>(null)
const compareLoading = ref(false)
const compareResult = ref<ReportCompareView | null>(null)

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
  compareDialogVisible.value = true
}

async function doCompare() {
  if (compareTarget.value == null || compareBaselineId.value == null) return
  compareLoading.value = true
  try {
    compareResult.value = await compareReport(compareTarget.value.id, compareBaselineId.value)
  } catch (e) {
    ElMessage.error((e as Error).message || '对比失败')
  } finally {
    compareLoading.value = false
  }
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
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openPreview(row)">预览</el-button>
            <el-button size="small" link type="primary" @click="openImport(row)">导入 jsonl</el-button>
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

    <!-- ② 三组评测器面板：规则判定 / LLM-Judge / 自定义（勾选 = 参与运行，每组一键全选/清空） -->
    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-head">
          <span class="panel-title">评测器（勾选参与运行）</span>
          <span class="panel-sub">规则判定 9 + LLM-Judge 6 + 自定义；LLM 未启用时 LLM-Judge 走确定性近似</span>
        </div>
      </template>

      <div class="group-block">
        <div class="group-head">
          <span class="group-title">规则判定（9）</span>
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

    <!-- 基线回归对比对话框 -->
    <el-dialog v-model="compareDialogVisible" title="基线回归对比" width="780px" :close-on-click-modal="false">
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
              <el-table-column label="Delta" width="140" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.delta != null" :type="row.delta > 0 ? 'success' : row.delta < 0 ? 'danger' : 'info'" size="small">
                    {{ row.delta > 0 ? '↑ 改进' : row.delta < 0 ? '↓ 回归' : '→ 持平' }} {{ fmtDelta(row.delta) }}
                  </el-tag>
                  <span v-else class="muted-text">-</span>
                </template>
              </el-table-column>
            </el-table>
          </template>
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
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="数据集">#{{ reportDetail.datasetId }} {{ reportDetail.name }}</el-descriptions-item>
          <el-descriptions-item label="模式">{{ reportDetail.mode }}</el-descriptions-item>
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

    <!-- 逐样本判分抽屉 -->
    <el-drawer v-model="sampleDrawerVisible" title="逐样本判分" size="820px">
      <template v-if="sampleTask">
        <div class="drawer-head">
          <el-tag :type="taskStatusType[sampleTask.status]" size="small">{{ taskStatusLabel[sampleTask.status] }}</el-tag>
          <span class="drawer-title">{{ sampleTask.name }} · 已测 {{ sampleTask.testedCases }}/{{ sampleTask.totalCases }} 例</span>
        </div>
        <div v-if="sampleTask.sampleResults && sampleTask.sampleResults.length" class="sample-list">
          <div v-for="s in sampleTask.sampleResults" :key="s.seq" class="sample-block">
            <div class="sample-head">
              <el-tag size="small" type="info">#{{ s.seq }}</el-tag>
              <span class="case-question">{{ s.question }}</span>
              <el-tag :type="s.mode === 'execute' ? 'warning' : 'success'" size="small">{{ s.mode }}</el-tag>
            </div>
            <div class="case-meta">回复：{{ s.actualResponse || '（空，不适用）' }}</div>
            <el-table :data="s.metrics" size="small" border class="mini-table">
              <el-table-column label="评测器" width="230">
                <template #default="{ row }"><code class="metric-code">{{ row.metric }}</code></template>
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
        <el-empty v-else description="该任务暂无样本明细" :image-size="60" />
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
</style>