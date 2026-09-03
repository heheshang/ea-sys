import { http } from './http'
import type {
  AgentGraphEntrySaveRequest,
  AgentGraphEntryView,
  ApiResponse,
  CockpitInsightView,
  CockpitOverviewView,
  LlmTraceView,
} from './types'

/** 八类知识领域（AgentCatalogRegistry.MODULES 对齐，前端展示/过滤顺序）。 */
export const GRAPH_MODULES = [
  'ONTOLOGY', 'SKILL', 'TOOL', 'MCP', 'SUBAGENT', 'MEMORY', 'KNOWLEDGE', 'EVALUATION',
] as const

export type GraphModule = (typeof GRAPH_MODULES)[number]

/** 模块中文名（图谱 tab / 统计表展示）。 */
export const GRAPH_MODULE_LABELS: Record<string, string> = {
  ONTOLOGY: '本体知识',
  SKILL: '技能目录',
  TOOL: '工具集',
  MCP: 'MCP 服务',
  SUBAGENT: '子智能体',
  MEMORY: '记忆存储',
  KNOWLEDGE: '知识库',
  EVALUATION: '评测中心',
}

/** GET /api/cockpit/overview —— 监控总览（LLM 聚合 + 图谱 + 知识库 + 记忆 + Agent 目录）。 */
export async function getCockpitOverview(): Promise<CockpitOverviewView> {
  const { data } = await http.get<ApiResponse<CockpitOverviewView>>('/cockpit/overview')
  return data.data
}

/** GET /api/cockpit/graph?module= —— 图谱清单（内置 ∪ 用户，同 key 用户行覆盖内置）。 */
export async function listGraphEntries(module?: string): Promise<AgentGraphEntryView[]> {
  const { data } = await http.get<ApiResponse<AgentGraphEntryView[]>>('/cockpit/graph', {
    params: module ? { module } : undefined,
  })
  return data.data
}

/** POST /api/cockpit/graph —— 新建图谱登记。 */
export async function createGraphEntry(req: AgentGraphEntrySaveRequest): Promise<AgentGraphEntryView> {
  const { data } = await http.post<ApiResponse<AgentGraphEntryView>>('/cockpit/graph', req)
  return data.data
}

/** PUT /api/cockpit/graph/{id} —— 编辑图谱登记。 */
export async function updateGraphEntry(id: number, req: AgentGraphEntrySaveRequest): Promise<AgentGraphEntryView> {
  const { data } = await http.put<ApiResponse<AgentGraphEntryView>>(`/cockpit/graph/${id}`, req)
  return data.data
}

/** PATCH /api/cockpit/graph/{id}/status?status= —— 状态开关。 */
export async function setGraphEntryStatus(id: number, status: 'ENABLED' | 'DISABLED'): Promise<AgentGraphEntryView> {
  const { data } = await http.patch<ApiResponse<AgentGraphEntryView>>(`/cockpit/graph/${id}/status`, null, {
    params: { status },
  })
  return data.data
}

/** DELETE /api/cockpit/graph/{id} —— 删除图谱登记（软删）。 */
export async function deleteGraphEntry(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/cockpit/graph/${id}`)
}

/** GET /api/cockpit/insights?force= —— 洞察（缓存 300s；force 绕过缓存重新生成）。 */
export async function getCockpitInsights(force = false): Promise<CockpitInsightView> {
  const { data } = await http.get<ApiResponse<CockpitInsightView>>('/cockpit/insights', {
    params: force ? { force: true } : undefined,
  })
  return data.data
}

/** GET /api/cockpit/llm-traces?limit=&trace= —— LLM 调用追踪（默认 20，上限 100；trace 按评测追踪 ID 过滤）。 */
export async function listLlmTraces(limit = 20, trace?: string): Promise<LlmTraceView[]> {
  const { data } = await http.get<ApiResponse<LlmTraceView[]>>('/cockpit/llm-traces', {
    params: trace ? { limit, trace } : { limit },
  })
  return data.data
}