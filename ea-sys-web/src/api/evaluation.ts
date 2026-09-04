import { http } from './http'
import type { AxiosRequestConfig } from 'axios'
import type {
  ApiResponse,
  CalibrationResult,
  CaseSaveRequest,
  CaseView,
  CustomEvaluatorView,
  CustomSaveRequest,
  DashboardView,
  DatasetSaveRequest,
  DatasetVersionView,
  DatasetView,
  EvaluationRunRequest,
  HumanReviewSaveRequest,
  HumanReviewView,
  ImportResultView,
  ReportCompareView,
  ReportView,
  TaskDetailView,
  TaskView,
  TranscriptTurnView,
} from './types'

/** 内置评测器元数据（与 EvaluationModel ALL_METRICS 逐字对齐，代码常量内置不落表）。 */
export interface EvaluatorMeta {
  /** 评测器唯一标识（判分入参 metric，缺省会静默丢弃） */
  metric: string
  /** rule = 确定性规则；llm_judge = LLM 判分（未启用时确定性近似） */
  category: 'rule' | 'llm_judge'
  /** 分组面板：rule = 规则判定 / llm = LLM-Judge（与自定义评测器三组并列） */
  group: 'rule' | 'llm'
  label: string
  description: string
  /** 参考的业界 AI agent 评测基准（τ-bench / GAIA / SafetyBench / MT-Bench 等） */
  origin: string
}

/** 评测器目录：规则 11 + LLM-Judge 6（参考网络上主流 AI agent 评测指标设计）。 */
export const EVALUATOR_CATALOG: EvaluatorMeta[] = [
  { metric: 'number_accuracy', category: 'rule', group: 'rule', label: '数字准确率', description: '期望数值命中率（期望含数字时适用）', origin: 'GSM8K 数值判分（数值全集命中）' },
  { metric: 'string_exact', category: 'rule', group: 'rule', label: '字符串精确匹配', description: '去除首尾空白后与期望完全一致', origin: 'SQuAD EM 精确匹配' },
  { metric: 'response_repetition', category: 'rule', group: 'rule', label: '回答重复度', description: '字符二元组重复率越低得分越高', origin: 'MT-Bench 质量维度（重复惩罚）' },
  { metric: 'text_similarity', category: 'rule', group: 'rule', label: '文本相似度', description: '字符二元组 Jaccard 相似度', origin: 'ROUGE/BLEU 文本相似度族' },
  { metric: 'observation_information_gain', category: 'rule', group: 'rule', label: '信息增益', description: '响应相对上下文的增量信息占比', origin: 'AgentBench 探索/信息增益' },
  { metric: 'tool_call_accuracy', category: 'rule', group: 'rule', label: '工具调用正确性', description: '期望工具名命中 + 参数逐键匹配（execute 轨迹）', origin: 'τ-bench 工具调用正确性' },
  { metric: 'task_success', category: 'rule', group: 'rule', label: '任务成功率', description: '端到端成功判定：数字全集命中或文本相似度≥0.8', origin: 'GAIA / SWE-bench 端到端任务成功' },
  { metric: 'step_efficiency', category: 'rule', group: 'rule', label: '步数效率', description: '期望步数 / 实际步数（工具调用步 + 回复步）', origin: 'WebArena / AgentBench 步数效率' },
  { metric: 'policy_compliance', category: 'rule', group: 'rule', label: '策略合规率', description: '期望政策条款（必备词/禁区词）逐条合规', origin: 'SafetyBench 安全与策略合规' },
  { metric: 'rag_hit_rate', category: 'rule', group: 'rule', label: '知识库命中率', description: '期望知识片段与 search_kb 检索命中文档的字符二元组重叠率（execute 专属）', origin: 'RAGAS context precision（检索命中率）' },
  { metric: 'decision_accuracy', category: 'rule', group: 'rule', label: '决策准确率', description: 'execute 专属：首意图决策准确率（第一条工具调用命中 0.5 / 参数全匹配 1.0 / 无调用 0）', origin: 'τ-bench 首意图决策' },
  { metric: 'llm_correctness', category: 'llm_judge', group: 'llm', label: '正确性', description: 'LLM 判分（未启用时确定性近似）', origin: 'MT-Bench / LLM-as-a-Judge' },
  { metric: 'llm_instruction_following', category: 'llm_judge', group: 'llm', label: '指令遵循', description: 'LLM 判分（未启用时确定性近似）', origin: 'MT-Bench / LLM-as-a-Judge' },
  { metric: 'llm_relevance', category: 'llm_judge', group: 'llm', label: '相关性', description: 'LLM 判分（未启用时确定性近似）', origin: 'MT-Bench / LLM-as-a-Judge' },
  { metric: 'llm_hallucination', category: 'llm_judge', group: 'llm', label: '幻觉检测', description: 'LLM 判分（未启用时确定性近似）', origin: 'MT-Bench / LLM-as-a-Judge' },
  { metric: 'llm_reasoning_groundedness', category: 'llm_judge', group: 'llm', label: '推理有据性', description: 'LLM 判分（未启用时确定性近似）', origin: 'MT-Bench / LLM-as-a-Judge' },
  { metric: 'llm_response_completeness', category: 'llm_judge', group: 'llm', label: '完整性', description: 'LLM 判分（未启用时确定性近似）', origin: 'MT-Bench / LLM-as-a-Judge' },
]

/** GET /api/evaluations/datasets —— 数据集列表（含 caseCount）。 */
export async function listDatasets(): Promise<DatasetView[]> {
  const { data } = await http.get<ApiResponse<DatasetView[]>>('/evaluations/datasets')
  return data.data
}

/** POST /api/evaluations/datasets —— 新建数据集（scope=llm_call；mode=openjudge/execute）。 */
export async function createDataset(req: DatasetSaveRequest): Promise<DatasetView> {
  const { data } = await http.post<ApiResponse<DatasetView>>('/evaluations/datasets', req)
  return data.data
}

/** PUT /api/evaluations/datasets/{id} —— 编辑数据集（可改 mode/status）。 */
export async function updateDataset(id: number, req: DatasetSaveRequest): Promise<DatasetView> {
  const { data } = await http.put<ApiResponse<DatasetView>>(`/evaluations/datasets/${id}`, req)
  return data.data
}

/** DELETE /api/evaluations/datasets/{id} —— 删除数据集（级联软删用例 + 报告）。 */
export async function deleteDataset(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/evaluations/datasets/${id}`)
}

/** GET /api/evaluations/datasets/{id}/cases —— 数据集用例列表（seq 升序）。 */
export async function listCases(datasetId: number): Promise<CaseView[]> {
  const { data } = await http.get<ApiResponse<CaseView[]>>(`/evaluations/datasets/${datasetId}/cases`)
  return data.data
}

/** POST /api/evaluations/datasets/{id}/cases —— 新增用例（seq 缺省 = 现有 max+1）。 */
export async function addCase(datasetId: number, req: CaseSaveRequest): Promise<CaseView> {
  const { data } = await http.post<ApiResponse<CaseView>>(`/evaluations/datasets/${datasetId}/cases`, req)
  return data.data
}

/** PUT /api/evaluations/cases/{id} —— 编辑用例。 */
export async function updateCase(id: number, req: CaseSaveRequest): Promise<CaseView> {
  const { data } = await http.put<ApiResponse<CaseView>>(`/evaluations/cases/${id}`, req)
  return data.data
}

/** DELETE /api/evaluations/cases/{id} —— 删除用例。 */
export async function deleteCase(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/evaluations/cases/${id}`)
}

/** POST /api/evaluations/run —— 批量运行评测（AgentPolicy 确定性评测 + 审计），返回落库报告。 */
export async function runEvaluation(
  req: EvaluationRunRequest,
  config?: AxiosRequestConfig,
): Promise<ReportView> {
  const { data } = await http.post<ApiResponse<ReportView>>('/evaluations/run', req, config)
  return data.data
}

/** POST /api/evaluations/tasks —— 创建评测任务（202 立即返回 TaskView，异步执行，前端轮询进度）。 */
export async function createTask(req: EvaluationRunRequest): Promise<TaskView> {
  const { data } = await http.post<ApiResponse<TaskView>>('/evaluations/tasks', req)
  return data.data
}

/** GET /api/evaluations/tasks —— 评测任务列表（created_at DESC）。 */
export async function listTasks(): Promise<TaskView[]> {
  const { data } = await http.get<ApiResponse<TaskView[]>>('/evaluations/tasks')
  return data.data
}

/** GET /api/evaluations/tasks/{id} —— 任务详情（后端返回 {task, metrics}，解包取 task）。 */
export async function getTask(id: number): Promise<TaskView> {
  const { data } = await http.get<ApiResponse<TaskDetailView>>(`/evaluations/tasks/${id}`)
  return data.data.task
}

/** POST /api/evaluations/tasks/{id}/cancel —— 协同取消任务（已终态返回 400）。 */
export async function cancelTask(id: number): Promise<void> {
  await http.post<ApiResponse<null>>(`/evaluations/tasks/${id}/cancel`)
}

/** GET /api/evaluations/reports/{id}/compare?baseline={reportId} —— 报告基线回归对比（逐指标 delta；layer 可选按分层过滤，响应含 topDegradedSamples）。 */
export async function compareReport(id: number, baselineId: number, layer?: 'basic' | 'edge' | 'real'): Promise<ReportCompareView> {
  const { data } = await http.get<ApiResponse<ReportCompareView>>(`/evaluations/reports/${id}/compare`, {
    params: { baseline: baselineId, ...(layer ? { layer } : {}) },
  })
  return data.data
}

/** GET /api/evaluations/reports —— 报告列表。 */
export async function listReports(): Promise<ReportView[]> {
  const { data } = await http.get<ApiResponse<ReportView[]>>('/evaluations/reports')
  return data.data
}

/** GET /api/evaluations/reports/{id} —— 报告详情。 */
export async function getReport(id: number): Promise<ReportView> {
  const { data } = await http.get<ApiResponse<ReportView>>(`/evaluations/reports/${id}`)
  return data.data
}

/** DELETE /api/evaluations/reports/{id} —— 删除报告。 */
export async function deleteReport(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/evaluations/reports/${id}`)
}

/** POST /api/evaluations/datasets/{id}/import —— jsonl 批量导入（逐行 JSON 或整体 JSON 数组，坏行跳过）。 */
export async function importCases(datasetId: number, content: string): Promise<ImportResultView> {
  const res = await http.post<ApiResponse<ImportResultView>>(
    `/evaluations/datasets/${datasetId}/import`,
    content,
    { headers: { 'Content-Type': 'text/plain' } },
  )
  return res.data.data
}

/** GET /api/evaluations/custom-evaluators —— 自定义评测器列表（rule 参数化规则 / llm_judge 提示词判分）。 */
export async function listCustomEvaluators(): Promise<CustomEvaluatorView[]> {
  const res = await http.get<ApiResponse<CustomEvaluatorView[]>>('/evaluations/custom-evaluators')
  return res.data.data
}

/** POST /api/evaluations/custom-evaluators —— 新建自定义评测器。 */
export async function createCustomEvaluator(req: CustomSaveRequest): Promise<CustomEvaluatorView> {
  const res = await http.post<ApiResponse<CustomEvaluatorView>>('/evaluations/custom-evaluators', req)
  return res.data.data
}

/** PUT /api/evaluations/custom-evaluators/{id} —— 全量覆盖更新自定义评测器。 */
export async function updateCustomEvaluator(id: number, req: CustomSaveRequest): Promise<CustomEvaluatorView> {
  const res = await http.put<ApiResponse<CustomEvaluatorView>>(`/evaluations/custom-evaluators/${id}`, req)
  return res.data.data
}

/** DELETE /api/evaluations/custom-evaluators/{id} —— 删除自定义评测器。 */
export async function deleteCustomEvaluator(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/evaluations/custom-evaluators/${id}`)
}

/* ---------- P1 数据集版本化 ---------- */

/** POST /api/evaluations/datasets/{id}/versions —— 发布新版本（不可变快照）。 */
export async function publishDatasetVersion(id: number): Promise<DatasetVersionView> {
  const { data } = await http.post<ApiResponse<DatasetVersionView>>(`/evaluations/datasets/${id}/versions`)
  return data.data
}

/** GET /api/evaluations/datasets/{id}/versions —— 版本列表（versionNo DESC）。 */
export async function listDatasetVersions(id: number): Promise<DatasetVersionView[]> {
  const { data } = await http.get<ApiResponse<DatasetVersionView[]>>(`/evaluations/datasets/${id}/versions`)
  return data.data
}

/** GET /api/evaluations/datasets/{id}/versions/{versionId}/cases —— 版本快照用例（只读）。 */
export async function getDatasetVersionCases(id: number, versionId: number): Promise<CaseView[]> {
  const { data } = await http.get<ApiResponse<CaseView[]>>(`/evaluations/datasets/${id}/versions/${versionId}/cases`)
  return data.data
}

/** DELETE /api/evaluations/datasets/{id}/versions/{versionId} —— 删除版本（被报告/任务引用时 400）。 */
export async function deleteDatasetVersion(id: number, versionId: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/evaluations/datasets/${id}/versions/${versionId}`)
}

/* ---------- P2 会话 Transcript ---------- */

/** GET /api/evaluations/reports/{id}/transcript?caseSeq= —— 报告对应用例的逐轮执行转录。 */
export async function getReportTranscript(reportId: number, caseSeq: number): Promise<TranscriptTurnView[]> {
  const { data } = await http.get<ApiResponse<TranscriptTurnView[]>>(`/evaluations/reports/${reportId}/transcript`, {
    params: { caseSeq },
  })
  return data.data
}

/** GET /api/evaluations/tasks/{id}/transcript?caseSeq= —— 任务（运行中/已完成）对应用例的逐轮执行转录。 */
export async function getTaskTranscript(taskId: number, caseSeq: number): Promise<TranscriptTurnView[]> {
  const { data } = await http.get<ApiResponse<TranscriptTurnView[]>>(`/evaluations/tasks/${taskId}/transcript`, {
    params: { caseSeq },
  })
  return data.data
}

/* ---------- P3 人工复评 + 校准 ---------- */

/** POST /api/evaluations/reports/{id}/reviews —— 提交/改判人工复评（upsert：软删旧行 + 插新行）。 */
export async function submitHumanReview(reportId: number, req: HumanReviewSaveRequest): Promise<HumanReviewView> {
  const { data } = await http.post<ApiResponse<HumanReviewView>>(`/evaluations/reports/${reportId}/reviews`, req)
  return data.data
}

/** GET /api/evaluations/reports/{id}/reviews —— 报告人工复评列表（caseSeq + metric 键）。 */
export async function listHumanReviews(reportId: number): Promise<HumanReviewView[]> {
  const { data } = await http.get<ApiResponse<HumanReviewView[]>>(`/evaluations/reports/${reportId}/reviews`)
  return data.data
}

/** DELETE /api/evaluations/reviews/{id} —— 删除复评（软删置空）。 */
export async function deleteHumanReview(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/evaluations/reviews/${id}`)
}

/** GET /api/evaluations/reports/{id}/reviews/calibration —— 校准对比（返回 {overall, metrics} 对象，非数组）。 */
export async function getCalibration(reportId: number): Promise<CalibrationResult> {
  const { data } = await http.get<ApiResponse<CalibrationResult>>(`/evaluations/reports/${reportId}/reviews/calibration`)
  return data.data
}

/* ---------- P4 复现 + 聚合并行看板 ---------- */

/** POST /api/evaluations/reports/{id}/rerun —— 复现报告（复用原数据集版本 + 评测器 + 判分配置，生成新报告）。 */
export async function rerunReport(id: number): Promise<ReportView> {
  const { data } = await http.post<ApiResponse<ReportView>>(`/evaluations/reports/${id}/rerun`)
  return data.data
}

/** GET /api/evaluations/dashboard?datasetId=&limit= —— 数据集维度聚合看板（分层/趋势/指标/成本延迟/退化样例）。 */
export async function getDashboard(datasetId: number, limit = 12): Promise<DashboardView> {
  const { data } = await http.get<ApiResponse<DashboardView>>('/evaluations/dashboard', {
    params: { datasetId, limit },
  })
  return data.data
}