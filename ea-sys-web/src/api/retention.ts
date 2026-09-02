import { http } from './http'
import type {
  ApiResponse,
  ChannelEffectView,
  FunnelView,
  IntervalRetentionView,
  WorkflowEffectView,
} from './types'

/** GET /api/retention/funnel —— 转化漏斗（workflowId 空 = 租户全量）。 */
export async function getRetentionFunnel(workflowId?: number): Promise<FunnelView> {
  const { data } = await http.get<ApiResponse<FunnelView>>('/retention/funnel', {
    params: workflowId ? { workflowId } : undefined,
  })
  return data.data
}

/** GET /api/retention/interval —— N 天区间留存（days ∈ {7,30,90}）。 */
export async function getIntervalRetention(days = 30): Promise<IntervalRetentionView> {
  const { data } = await http.get<ApiResponse<IntervalRetentionView>>('/retention/interval', {
    params: { days },
  })
  return data.data
}

/** GET /api/retention/channel-effect —— 近 N 天渠道效果。 */
export async function getChannelEffect(days = 7): Promise<ChannelEffectView> {
  const { data } = await http.get<ApiResponse<ChannelEffectView>>('/retention/channel-effect', {
    params: { days },
  })
  return data.data
}

/** GET /api/retention/workflows —— 各工作流最近执行触达 + 留存。 */
export async function getWorkflowEffect(days = 30): Promise<WorkflowEffectView> {
  const { data } = await http.get<ApiResponse<WorkflowEffectView>>('/retention/workflows', {
    params: { days },
  })
  return data.data
}