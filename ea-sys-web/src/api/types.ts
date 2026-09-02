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