import { http } from './http'
import type {
  AiChatRequest,
  ApiResponse,
  DryRunRequest,
  DryRunResponse,
  ExecutionSummary,
  PlanValidationView,
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

/** SSE 事件帧（与后端 SseEmitter 下发字段一致）。 */
export interface AiChatEvent {
  type: string
  [k: string]: unknown
}

/**
 * POST /api/workflows/ai-chat —— 流式对话创建：SSE 逐帧回调 onEvent。
 * 事件流为 `data: {json}\n\n` 帧；文本增量/工具状态/确认卡片/草稿卡都从帧还原。
 */
export async function aiChat(
  req: AiChatRequest,
  onEvent: (ev: AiChatEvent) => void,
): Promise<void> {
  let cursor = 0
  await http.post('/workflows/ai-chat', req, {
    responseType: 'text',
    timeout: 0,
    onDownloadProgress: (e) => {
      const current: unknown = e.event?.currentTarget
      const text =
        current != null &&
        typeof current === 'object' &&
        'responseText' in current &&
        typeof current.responseText === 'string'
          ? current.responseText
          : ''
      let idx: number
      while ((idx = text.indexOf('\n\n', cursor)) !== -1) {
        const frame = text.slice(cursor, idx)
        cursor = idx + 2
        const line = frame.split('\n').find((l) => l.startsWith('data:'))
        if (line) {
          try {
            onEvent(JSON.parse(line.slice(5).trim()) as AiChatEvent)
          } catch {
            // 损坏/心跳帧，忽略
          }
        }
      }
    },
  })
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

/** GET /api/workflows/executions —— 执行历史列表（dryRun=true 干跑 / false 真实触达）。 */
export async function listWorkflowExecutions(opts?: {
  workflowId?: number
  dryRun?: boolean
  limit?: number
}): Promise<ExecutionSummary[]> {
  const { data } = await http.get<ApiResponse<ExecutionSummary[]>>('/workflows/executions', { params: opts })
  return data.data
}

/** POST /api/plan-validation/{workflowId}/import —— 导入计划文件并校验（multipart）。 */
export async function importPlanValidation(id: number, file: File): Promise<PlanValidationView> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<ApiResponse<PlanValidationView>>(`/plan-validation/${id}/import`, form)
  return data.data
}

/** GET /api/plan-validation/{workflowId} —— 最近一次校验报告回看。 */
export async function getPlanValidation(id: number): Promise<PlanValidationView | null> {
  const { data } = await http.get<ApiResponse<PlanValidationView | null>>(`/plan-validation/${id}`)
  return data.data
}

/** GET /api/plan-validation/template?type=xlsx|csv —— 下载空白计划模板。 */
export async function downloadPlanValidationTemplate(type: 'xlsx' | 'csv'): Promise<Blob> {
  const res = await http.get(`/plan-validation/template`, {
    params: { type },
    responseType: 'blob',
  })
  return res.data
}