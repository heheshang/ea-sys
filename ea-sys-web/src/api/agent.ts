import { http } from './http'
import type {
  ApiResponse,
  ChurnScanRequest,
  ChurnScanView,
  RoutePreviewRequest,
  RoutePreviewView,
  StrategyRequest,
  StrategyView,
} from './types'

/** GET /api/agent/strategies —— 分层策略列表。 */
export async function listAgentStrategies(): Promise<StrategyView[]> {
  const { data } = await http.get<ApiResponse<StrategyView[]>>('/agent/strategies')
  return data.data
}

/** POST /api/agent/strategies —— 生成分层策略（确定性规划器）。 */
export async function generateStrategy(req: StrategyRequest): Promise<StrategyView> {
  const { data } = await http.post<ApiResponse<StrategyView>>('/agent/strategies', req)
  return data.data
}

/** GET /api/agent/strategies/active —— 当前生效策略。 */
export async function getActiveStrategy(): Promise<StrategyView | null> {
  const { data } = await http.get<ApiResponse<StrategyView | null>>('/agent/strategies/active')
  return data.data
}

/** POST /api/agent/strategies/{id}/publish —— 发布（人工闸门）。 */
export async function publishStrategy(id: number): Promise<StrategyView> {
  const { data } = await http.post<ApiResponse<StrategyView>>(`/agent/strategies/${id}/publish`)
  return data.data
}

/** DELETE /api/agent/strategies/{id} —— 删除策略。 */
export async function deleteStrategy(id: number): Promise<void> {
  await http.delete<ApiResponse<void>>(`/agent/strategies/${id}`)
}

/** POST /api/agent/route-preview —— 路由预览（触达史重排）。 */
export async function routePreview(req: RoutePreviewRequest): Promise<RoutePreviewView> {
  const { data } = await http.post<ApiResponse<RoutePreviewView>>('/agent/route-preview', req)
  return data.data
}

/** POST /api/agent/churn/scan —— 流失风险批量扫描。 */
export async function scanChurn(req: ChurnScanRequest): Promise<ChurnScanView> {
  const { data } = await http.post<ApiResponse<ChurnScanView>>('/agent/churn/scan', req)
  return data.data
}