/** 与后端 ApiResponse 对齐的统一响应体。code=0 成功，非 0 业务/系统错误。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

/** 登录响应（AuthController.login）。 */
export interface LoginResult {
  token: string
  username: string
  role: string
  tenantId: number
}

/** /api/whoami 受保护接口。 */
export interface WhoamiResult {
  tenantId: number
  userId: number
  username: string
  role: string
}

/** 画布节点类型（后端 NodeType）。 */
export type WorkflowNodeType =
  | 'TRIGGER' | 'AUDIENCE' | 'CONDITION' | 'AGENT_SPLIT' | 'DELAY' | 'ACTION' | 'UPDATE' | 'END'

/** 画布节点（WorkflowNodeSpec）。key 画布内唯一；config 为节点配置 JSON 对象。 */
export interface WorkflowNodeSpec {
  key: string
  type: WorkflowNodeType
  name?: string
  config?: Record<string, unknown> | null
  position?: { x: number; y: number } | null
}

/** 边条件 DSL 操作符白名单（ConditionCompiler.OPS，比较用符号形式）。 */
export type ConditionOp =
  | '>' | '>=' | '<' | '<='
  | 'equals' | 'not_equals' | 'in' | 'not_in' | 'contains'
  | 'exists' | 'not_exists'
  | 'percentage'

/** 边条件项：字段（event./contact./history. 前缀）+ 操作符 + 值。 */
export interface ConditionItem {
  field: string
  op: ConditionOp
  value?: string | number | boolean | Array<string | number | boolean>
}

/** 边条件 DSL（与 AudienceRule 同构的 AND/OR 分组树，字段前缀替换为 event./contact./history.）。 */
export interface ConditionRule {
  op: 'AND' | 'OR'
  items: Array<ConditionItem | ConditionRule>
}

/** 画布边（WorkflowEdgeSpec）。condition 为条件 DSL；null/undefined = 兜底分支（else）。 */
export interface WorkflowEdgeSpec {
  source: string
  target: string
  condition?: ConditionRule | null
}

/** 保存画布请求（SaveWorkflowRequest）。 */
export interface SaveWorkflowRequest {
  name: string
  description?: string
  nodes: WorkflowNodeSpec[]
  edges: WorkflowEdgeSpec[]
}

/** AI 对话确认（HITL：生成草稿前必须用户确认）。 */
export interface AiChatConfirm {
  confirmed: boolean
}

/** AI 流式对话请求（ai-chat SSE）：confirm 为空时若后端有挂起确认则 400。 */
export interface AiChatRequest {
  message: string
  sessionId: string
  confirm?: AiChatConfirm
}

/** AI 工具调用记录（AiToolCallView：真实执行过的租户查询/生成步骤）。 */
export interface AiToolCallView {
  name: string
  arguments: Record<string, unknown> | null
  result: unknown
  status: 'SUCCESS' | 'FAILED'
  durationMs: number
}

/** 人群匹配提示（audienceHint）：matched=false 时引导去人群管理人工圈选。 */
export interface AiAudienceHint {
  matched: boolean
  audienceId?: number
  audienceName?: string
  suggestedName?: string
  note?: string
}

/** AI 创建响应：DAG 草稿（不落库）+ 工具时间线 + 计划摘要 + 人群提示。 */
export interface AiGenerateResponse {
  workflowDraft: SaveWorkflowRequest
  toolCalls: AiToolCallView[]
  planSummary: string
  audienceHint: AiAudienceHint
}

/** 工作流视图（WorkflowView：画布 + 节点 + 边）。 */
export interface WorkflowView {
  id: number
  name: string
  description: string
  status: 'draft' | 'published' | 'archived'
  version: number
  publishedAt: string | null
  createdBy: string
  createdAt: string
  nodes: WorkflowNodeSpec[]
  edges: WorkflowEdgeSpec[]
}

/** 工作流列表行（每业务 id 族最新可用行，不含画布）。 */
export interface WorkflowSummary {
  id: number
  name: string
  description: string
  status: 'draft' | 'published' | 'archived'
  version: number
  publishedAt: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

/** 画布校验结果（ValidationResponse）。valid=true 即可发布。 */
export interface ValidationResponse {
  valid: boolean
  errors: string[]
}

/** 工作流版本/发布记录行（GET /api/workflows/{id}/versions）。 */
export interface WorkflowVersion {
  version: number
  refId: number
  name: string
  status: 'draft' | 'published' | 'archived'
  publishedBy: string | null
  publishedAt: string | null
  createdBy: string
  createdAt: string
}

/** 干跑快照行（execution dry_run=true，冻结画布版本 + 人群快照）。 */
export interface WorkflowDryRun {
  executionId: number
  workflowVersion: number
  audienceSnapshotId: number | null
  audienceName: string | null
  memberCount: number | null
  status: string
  startedAt: string
  finishedAt: string | null
}

/** 快照列表（GET /api/workflows/{id}/snapshots）：发布快照 + 干跑快照。 */
export interface WorkflowSnapshotList {
  publishSnapshots: WorkflowVersion[]
  dryRunSnapshots: WorkflowDryRun[]
}

/** 批量随机创建联系人请求（POST /api/contacts/batch）。 */
export interface BatchContactCreateRequest {
  count: number
}

/** 批量创建结果。 */
export interface BatchContactCreateResult {
  created: number
  skipped: number
}

/** 单节点执行结果（DryRunResponse.NodeOutcome）。 */
export interface NodeOutcome {
  key: string
  nodeType: string
  nodeName: string
  status: string
  contacts: number
  output: Record<string, unknown> | null
}

/** 执行历史行（GET /api/workflows/executions）。 */
export interface ExecutionSummary {
  executionId: number
  workflowId: number
  workflowName: string
  workflowVersion: number
  triggerType: string
  dryRun: boolean
  status: string
  audienceSnapshotId: number | null
  audienceName: string | null
  memberCount: number | null
  startedAt: string
  finishedAt: string | null
}

/** 通道触达日志行（delivery_record）：真实下发的通道级记录。 */
export interface DeliveryLog {
  id: number
  executionId: number
  contactId: number
  contactName: string | null
  channel: string
  templateId: number | null
  content: string | null
  channelMsgId: string | null
  status: string
  error: string | null
  createdAt: string
}

/** 执行/干跑报告（DryRunResponse）。 */
export interface DryRunResponse {
  executionId: number
  workflowId: number
  workflowVersion: number
  status: string
  totalMembers: number
  dryRun: boolean
  durationMs: number
  error: string | null
  nodes: NodeOutcome[]
  deliveries: DeliveryLog[]
}

/** 计划校验单维度比对结果（PlanValidationView.Dimension）。 */
export interface PlanValidationDimension {
  name: string
  level: 'PASSED' | 'WARNINGS' | 'BLOCKED'
  plan: string | null
  workflow: string | null
  detail: string | null
  suggestion: string | null
}

/** 计划校验汇总计数：conflicts=BLOCKED 数，warnings=WARNINGS 数，passed=其余。 */
export interface PlanValidationSummary {
  conflicts: number
  warnings: number
  passed: number
}

/** 计划校验报告（GET /api/plan-validation/{workflowId}）。 */
export interface PlanValidationView {
  id: number | null
  workflowId: number
  planName: string | null
  fileType: string | null
  fileName: string | null
  decision: 'PASSED' | 'WARNINGS' | 'BLOCKED'
  planSummary: string | null
  dimensions: PlanValidationDimension[]
  summary: PlanValidationSummary
  createdAt: string | null
  createdBy: string | null
}

/** 触达模板（TemplateView）。 */
export interface Template {
  id: number
  channel: string
  name: string
  content: string
  status: string
  createdAt: string
}

/** 分页响应体（对齐 PageResponse<T>）。 */
export interface PageResponse<T> {
  records: T[]
  total: number
  page: number
  size: number
}

/** 转化漏斗（GET /api/retention/funnel）：圈选 → 执行 → 触达。 */
export interface FunnelView {
  workflowId: number | null
  seeded: number
  executed: number
  reached: number
  seededToExecutedRate: number
  executedToReachedRate: number
}

/** 区间留存（GET /api/retention/interval）：N 天双窗口。 */
export interface IntervalRetentionView {
  days: number
  cohort: number
  retained: number
  rate: number
  priorWindowStart: string
  priorWindowEnd: string
  currentWindowStart: string
  currentWindowEnd: string
}

/** 渠道效果行（GET /api/retention/channel-effect）。 */
export interface ChannelEffectItem {
  channel: string
  total: number
  sent: number
  failed: number
  distinctContacts: number
  deliveryRate: number
}

/** 渠道效果（GET /api/retention/channel-effect）。 */
export interface ChannelEffectView {
  channels: ChannelEffectItem[]
}

/** 工作流效果行（GET /api/retention/workflows）。 */
export interface WorkflowEffectItem {
  workflowId: number
  workflowName: string
  reached: number
  retained: number
  retentionRate: number
}

/** 工作流效果（GET /api/retention/workflows）。 */
export interface WorkflowEffectView {
  workflows: WorkflowEffectItem[]
}

/* ---------- M4：Agent 智能体 ---------- */

/** 分层策略视图（GET /api/agent/strategies）。strategy = 完整分层文档（JsonNode）。 */
export interface StrategyView {
  id: number
  name: string
  dimensions: unknown
  routeOrder: string[] | unknown
  strategy: unknown
  source: string
  status: string
  strategyVersion: string
  confidence: number
  createdBy: string
  createdAt: string
  publishedAt: string | null
}

/** 生成分层策略请求（POST /api/agent/strategies）。 */
export interface StrategyRequest {
  name: string
  strategyVersion?: string
  routeOrder?: string[]
}

/** 分层编辑项：单层规则（对齐后端 StrategyUpdateRequest.LayerEdit）。 */
export interface StrategyLayerEdit {
  id: string
  name: string
  /** sms_only | email_only | multi | none */
  channelAvailability: string
  routeOrder: string[]
  priority: number
}

/** 编辑分层策略请求（PUT /api/agent/strategies/{id}，仅 draft）。 */
export interface StrategyUpdateRequest {
  name: string
  layers: StrategyLayerEdit[]
}

/** 路由预览请求（POST /api/agent/route-preview）。 */
export interface RoutePreviewRequest {
  contactId: number
  routeOrder?: string[]
}

/** 路由预览结果：近 24h 触达渠道 + 重排后顺序。 */
export interface RoutePreviewView {
  contactId: number
  touched: string[]
  reordered: string[]
  unchanged: boolean
}

/** 流失扫描请求（POST /api/agent/churn/scan）。 */
export interface ChurnScanRequest {
  audienceSnapshotId: number
  inactiveDays: number
}

/** 流失扫描结果。 */
export interface ChurnScanView {
  audienceSnapshotId: number
  thresholdDays: number
  scanned: number
  high: number
  medium: number
  low: number
  updatedAttributes: number
}

/* ---------- M1：Contact / Audience ---------- */

/** 规则 DSL 操作符白名单（后端 RuleCompiler.OPS）。 */
export type RuleOp =
  | 'equals' | 'not_equals' | 'in' | 'not_in' | 'contains'
  | 'gt' | 'gte' | 'lt' | 'lte' | 'exists' | 'not_exists'

/** 条件项：字段 + 操作符 + 值。 */
export interface RuleCondition {
  field: string
  op: RuleOp
  /** exists/not_exists 无值；in/not_in 为数组；gt/gte/lt/lte 为数值；其余标量。 */
  value?: string | number | boolean | Array<string | number | boolean>
}

/** 规则 DSL：AND/OR 分组，items 可含条件或嵌套分组。 */
export interface AudienceRule {
  op: 'AND' | 'OR'
  items: Array<RuleCondition | AudienceRule>
}

/** 联系人（ContactResponse）。 */
export interface Contact {
  id: number
  externalId: string
  phone: string
  email: string
  pushToken: string
  wechatOpenid: string
  status: 'active' | 'silent' | 'unsubscribed'
  createdAt: string
  updatedAt: string
  attributes: Record<string, unknown>
  tags: string[]
}

/** 联系人创建/更新请求（ContactRequest；属性与标签为全量替换语义）。 */
export interface ContactRequest {
  externalId?: string
  phone?: string
  email?: string
  pushToken?: string
  status?: 'active' | 'silent' | 'unsubscribed'
  attributes?: Record<string, unknown>
  tags?: string[]
}

/** 人群（AudienceResponse）。rule 为 JSON 字符串。 */
export interface Audience {
  id: number
  name: string
  rule: string
  version: number
  status: 'draft' | 'published' | 'archived'
  createdBy: string
  createdAt: string
  updatedAt: string
  latestSnapshot: AudienceLatestSnapshot | null
}

export interface AudienceLatestSnapshot {
  id: number
  status: string
  memberCount: number
  executedAt: string
}

/** 人群创建/更新请求（AudienceRequest）。 */
export interface AudienceRequest {
  name: string
  rule: AudienceRule
}

/** 圈选快照（SnapshotResponse）。 */
export interface AudienceSnapshot {
  id: number
  audienceId: number
  executedAt: string
  memberCount: number
  status: 'building' | 'ready' | 'failed'
  filterVersion: number
}

/** 快照成员预览行（MemberView）。 */
export interface AudienceMember {
  contactId: number
  externalId: string
  phone: string
  email: string
  status: string
}

/* ---------- M8：驾驶舱 / 评测中心 ---------- */

/** Agent 图谱登记项（内置目录 source=builtin id=null；用户行 source=user）。 */
export interface AgentGraphEntryView {
  id: number | null
  module: string
  entryKey: string
  name: string
  description: string | null
  payload: unknown
  status: 'ENABLED' | 'DISABLED'
  version: string | null
  source: 'builtin' | 'user'
  createdBy: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** 图谱登记新建/编辑请求。 */
export interface AgentGraphEntrySaveRequest {
  module: string
  entryKey: string
  name: string
  description?: string | null
  payload?: unknown
  status?: 'ENABLED' | 'DISABLED'
  version?: string | null
}

/** 按 agent_type / model 分组的 LLM 调用聚合行。 */
export interface LlmSeries {
  name: string
  calls: number
  success: number
  fallback: number
  error: number
  avgDurationMs: number
  sumTokens: number
  sumCost: number
}

/** 按天聚合（近 7 天 trend）。 */
export interface LlmTrend {
  day: string
  calls: number
  success: number
  sumTokens: number
  sumCost: number
}

/** 单模块图谱统计。 */
export interface ModuleStat {
  module: string
  total: number
  enabled: number
}

/** 单 Agent 类型登记状态。 */
export interface AgentStat {
  type: string
  name: string
  llmEnabled: boolean
  modelId: string
}

/** 监控总览（GET /api/cockpit/overview）。 */
export interface CockpitOverviewView {
  llm: {
    enabled: boolean
    modelId: string | null
    calls: number
    success: number
    fallback: number
    error: number
    avgDurationMs: number
    sumTokens: number
    sumCost: number
    schemaValidRate: number
    errorRate: number
    fallbackRate: number
    rounds: number
    sumInputTokens: number
    sumOutputTokens: number
    sumCachedTokens: number
    context: {
      entries: number
      tokens: number
      categories: Array<{
        key: string
        entries: number
        tokens: number
      }>
    } | null
    byAgent: LlmSeries[]
    byModel: LlmSeries[]
    trend: LlmTrend[]
  }
  graph: {
    total: number
    enabled: number
    modules: ModuleStat[]
  }
  knowledge: { docs: number; chunks: number }
  memory: { keys: number }
  agents: { byType: AgentStat[] }
}

/** 洞察视图（GET /api/cockpit/insights，缓存 300s，force 绕过）。 */
export interface CockpitInsightView {
  generatedAt: string
  overallHealth: number
  insights: CockpitInsight[]
}

/** 单条洞察发现。 */
export interface CockpitInsight {
  level: 'critical' | 'warning' | 'info'
  dimension: string
  detail: string
  suggestion: string
}

/** LLM 调用追踪行（audit_log 最近 N 条）。 */
export interface LlmTraceView {
  id: number
  agentType: string
  action: string
  status: string
  reason: string | null
  model: string | null
  tokens: number | null
  durationMs: number | null
  cost: number | null
  confidence: number | null
  schemaValid: boolean | null
  operator: string | null
  createdAt: string
}

/* ---------- M8：评测中心 ---------- */

/** 评测数据集（GET /api/evaluations/datasets）。 */
export interface DatasetView {
  id: number
  name: string
  description: string | null
  scope: string
  mode: 'openjudge' | 'execute'
  /** execute 模式被测智能体：assistant / workflow-dialogue（openjudge 忽略） */
  agentType: 'assistant' | 'workflow-dialogue'
  status: 'ENABLED' | 'DISABLED'
  caseCount: number
  createdBy: string
  createdAt: string
  updatedAt: string
}

/** 数据集新建/编辑请求。 */
export interface DatasetSaveRequest {
  name: string
  description?: string | null
  scope?: string
  mode?: 'openjudge' | 'execute'
  agentType?: 'assistant' | 'workflow-dialogue'
  status?: 'ENABLED' | 'DISABLED'
}

/** 评测用例（GET /api/evaluations/datasets/{id}/cases）。 */
export interface CaseView {
  id: number
  datasetId: number
  seq: number | null
  question: string
  systemPrompt: string | null
  expectedOutput: unknown
  toolSchema: unknown
  expectedTool: unknown
  /** step_efficiency 期望步数基准（工具调用步 + 最终回复步，缺省 1） */
  expectedSteps: number | null
  /** policy_compliance 期望策略条款 [{keyword, prohibit}] */
  expectedPolicy: unknown
  providedResponse: string | null
  createdAt: string
}

/** 用例新建/编辑请求。 */
export interface CaseSaveRequest {
  seq?: number | null
  question: string
  systemPrompt?: string | null
  expectedOutput?: unknown
  toolSchema?: unknown
  expectedTool?: unknown
  expectedSteps?: number | null
  expectedPolicy?: unknown
  providedResponse?: string | null
}

/** 评测指标均值行（report.metrics[]）。 */
export interface ReportMetric {
  metric: string
  category: 'rule' | 'llm_judge'
  avg_score: number
  passed_count: number
  applicable_count: number
}

/** 分级发现行（report.findings[]）。 */
export interface ReportFinding {
  level: 'BLOCKED' | 'WARNING' | 'INFO'
  dimension: string
  detail: string
  suggestion?: string | null
}

/** 评测报告（GET /api/evaluations/reports；metrics/findings/summary 为 JSON 原文）。 */
export interface ReportView {
  id: number
  datasetId: number
  name: string
  totalCases: number
  testedCases: number
  metrics: ReportMetric[]
  findings: ReportFinding[]
  summary: { score: number; verdict: 'PASS' | 'WARN' | 'FAIL' }
  confidence: number
  model: string | null
  mode: string
  /** LLM 判分轮数（多次取均值；缺省 1） */
  judgeRounds: number
  /** 运行追踪 ID（驾驶舱 LLM 追踪按此联动过滤） */
  traceId: string | null
  createdBy: string
  createdAt: string
}

/** 评测运行请求（evaluators 缺省 = 全量 15 个内置评测器；judgeRounds 缺省 1，上限 5）。 */
export interface EvaluationRunRequest {
  datasetId: number
  evaluators?: string[]
  judgeRounds?: number
}

/** 自定义评测器视图（GET/POST/PUT /api/evaluations/custom-evaluators）。 */
export interface CustomEvaluatorView {
  id: number
  /** 评测指标名 = custom_{id} */
  metric: string
  name: string
  category: 'rule' | 'llm_judge'
  description: string | null
  /** rule 类规则类型：keyword_contains / regex_match / length_between */
  ruleType: string | null
  /** rule 类参数对象（关键词/正则/长度区间） */
  params: Record<string, unknown> | null
  /** llm_judge 类判分提示词（{question}/{response}/{reference} 占位） */
  judgePrompt: string | null
  status: 'ENABLED' | 'DISABLED'
  createdBy: string
  createdAt: string
}

/** 自定义评测器保存请求（POST 新建 / PUT 全量覆盖）。 */
export interface CustomSaveRequest {
  name: string
  category: 'rule' | 'llm_judge'
  description?: string | null
  ruleType?: string | null
  params?: Record<string, unknown> | null
  judgePrompt?: string | null
  status?: 'ENABLED' | 'DISABLED'
}

/** jsonl 导入结果（POST /api/evaluations/datasets/{id}/import）。 */
export interface ImportResultView {
  imported: number
  skipped: number
  /** 坏行明细（行号从 1 计：整体 JSON 数组时 = 元素下标+1） */
  errors: Array<{ line: number; message: string }>
}