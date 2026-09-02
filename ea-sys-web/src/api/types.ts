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

export interface WorkflowNodeSpec {
  id: string
  type: string
  x: number
  y: number
  config?: Record<string, unknown>
}

export interface WorkflowEdgeSpec {
  id: string
  source: string
  target: string
  /** 条件边表达式；缺省为恒 true。 */
  condition?: string
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