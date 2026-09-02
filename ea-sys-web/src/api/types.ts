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
  | 'TRIGGER' | 'CONDITION' | 'AGENT_SPLIT' | 'DELAY' | 'ACTION' | 'UPDATE' | 'END'

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

/** AI 创建工作流请求（AiGenerateRequest）。 */
export interface AiGenerateRequest {
  prompt: string
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

/** 干跑/执行请求（DryRunRequest）：对已发布版本 + 冻结快照成员模拟执行。 */
export interface DryRunRequest {
  audienceSnapshotId: number
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