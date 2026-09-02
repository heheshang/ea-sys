import { http } from './http'
import type {
  ApiResponse,
  DryRunRequest,
  DryRunResponse,
  SaveWorkflowRequest,
  ValidationResponse,
  WorkflowSnapshotList,
  WorkflowSummary,
  WorkflowVersion,
  WorkflowView,
} from './types'

/** GET /api/workflows —— 工作流列表（每业务 id 族最新可用行）。 */
export async function listWorkflows(): Promise<WorkflowSummary[]> {
  const { data } = await http.get<ApiResponse<WorkflowSummary[]>>('/workflows')
  return data.data
}

/** POST /api/workflows —— 新建画布（v1 DRAFT）。 */
export async function createWorkflow(req: SaveWorkflowRequest): Promise<WorkflowView> {
  const { data } = await http.post<ApiResponse<WorkflowView>>('/workflows', req)
  return data.data
}

/** GET /api/workflows/{id} —— 当前可用版本画布。 */
export async function getWorkflow(id: number): Promise<WorkflowView> {
  const { data } = await http.get<ApiResponse<WorkflowView>>(`/workflows/${id}`)
  return data.data
}

/** PUT /api/workflows/{id} —— 更新画布（DRAFT 覆盖；已发布生成 version+1 新行）。 */
export async function updateWorkflow(id: number, req: SaveWorkflowRequest): Promise<WorkflowView> {
  const { data } = await http.put<ApiResponse<WorkflowView>>(`/workflows/${id}`, req)
  return data.data
}

/** POST /api/workflows/{id}/validate —— 校验当前版本画布结构。 */
export async function validateWorkflow(id: number): Promise<ValidationResponse> {
  const { data } = await http.post<ApiResponse<ValidationResponse>>(`/workflows/${id}/validate`)
  return data.data
}

/** GET /api/workflows/{id}/versions —— 发布记录（全部版本行，含发布人/发布时间）。 */
export async function listWorkflowVersions(id: number): Promise<WorkflowVersion[]> {
  const { data } = await http.get<ApiResponse<WorkflowVersion[]>>(`/workflows/${id}/versions`)
  return data.data
}

/** GET /api/workflows/{id}/snapshots —— 快照列表（发布快照 + 干跑快照）。 */
export async function listWorkflowSnapshots(id: number): Promise<WorkflowSnapshotList> {
  const { data } = await http.get<ApiResponse<WorkflowSnapshotList>>(`/workflows/${id}/snapshots`)
  return data.data
}

/** POST /api/workflows/{id}/publish —— 发布当前草稿版本。 */
export async function publishWorkflow(id: number): Promise<WorkflowView> {
  const { data } = await http.post<ApiResponse<WorkflowView>>(`/workflows/${id}/publish`)
  return data.data
}

/** POST /api/workflows/{id}/dry-run —— 干跑：对已发布版本 + 快照成员模拟执行。 */
export async function dryRunWorkflow(id: number, req: DryRunRequest): Promise<DryRunResponse> {
  const { data } = await http.post<ApiResponse<DryRunResponse>>(`/workflows/${id}/dry-run`, req)
  return data.data
}

/** POST /api/workflows/{id}/execute —— 真实触达执行。 */
export async function executeWorkflow(id: number, req: DryRunRequest): Promise<DryRunResponse> {
  const { data } = await http.post<ApiResponse<DryRunResponse>>(`/workflows/${id}/execute`, req)
  return data.data
}

/** GET /api/workflows/executions/{executionId}/report —— 按执行实例查询报告。 */
export async function getExecutionReport(executionId: number): Promise<DryRunResponse> {
  const { data } = await http.get<ApiResponse<DryRunResponse>>(`/workflows/executions/${executionId}/report`)
  return data.data
}