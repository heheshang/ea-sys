import { http } from './http'
import type { ApiResponse, Template } from './types'

/** 模板保存请求（对齐 TemplateRequest：channel/name/content，FreeMarker 内容）。 */
export interface TemplateRequest {
  channel: string
  name: string
  content: string
}

/** GET /api/templates —— 启用中的触达模板（channel + templateId 供 ACTION 节点配置）。 */
export async function listTemplates(): Promise<Template[]> {
  const { data } = await http.get<ApiResponse<Template[]>>('/templates')
  return data.data
}

/** POST /api/templates —— 新建模板（服务端 FreeMarker 语法校验）。 */
export async function createTemplate(req: TemplateRequest): Promise<Template> {
  const { data } = await http.post<ApiResponse<Template>>('/templates', req)
  return data.data
}

/** PUT /api/templates/{id} —— 更新模板（语法校验）。 */
export async function updateTemplate(id: number, req: TemplateRequest): Promise<Template> {
  const { data } = await http.put<ApiResponse<Template>>(`/templates/${id}`, req)
  return data.data
}

/** DELETE /api/templates/{id} —— 删除模板。 */
export async function deleteTemplate(id: number): Promise<void> {
  await http.delete<ApiResponse<void>>(`/templates/${id}`)
}