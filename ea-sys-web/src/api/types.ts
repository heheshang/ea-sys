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