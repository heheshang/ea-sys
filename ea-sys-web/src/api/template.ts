import { http } from './http'
import type { ApiResponse, Template } from './types'

/** GET /api/templates —— 启用中的触达模板（channel + templateId 供 ACTION 节点配置）。 */
export async function listTemplates(): Promise<Template[]> {
  const { data } = await http.get<ApiResponse<Template[]>>('/templates')
  return data.data
}