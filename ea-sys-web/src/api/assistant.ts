import { http } from './http'
import type { ApiResponse } from './types'

/** 智能客服文档行（KbDocumentView）。 */
export interface KbDocumentView {
  id: number
  name: string
  contentType: string | null
  sizeBytes: number
  status: string
  error: string | null
  chunkCount: number | null
  createdAt: string
}

/** 智能客服对话请求（与后端 AiChatRequest 对齐）。 */
export interface AssistantChatRequest {
  message: string
  sessionId: string
  confirm?: { confirmed: boolean } | null
}

/** SSE 事件帧（与后端 SseEmitter 下发字段一致，含自定义 assistant_card / switch_workflow_dialogue）。 */
export interface AssistantChatEvent {
  type: string
  [k: string]: unknown
}

/**
 * POST /api/assistant/ai-chat —— AI 智能客服流式对话：SSE 逐帧回调 onEvent。
 * 事件流为 `data: {json}\n\n` 帧；文本增量、工具状态、确认卡、知识库/统计/人群/工作流卡都从帧还原。
 */
export async function aiChat(
  req: AssistantChatRequest,
  onEvent: (ev: AssistantChatEvent) => void,
): Promise<void> {
  let cursor = 0
  await http.post('/assistant/ai-chat', req, {
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
            onEvent(JSON.parse(line.slice(5).trim()) as AssistantChatEvent)
          } catch {
            // 损坏/心跳帧，忽略
          }
        }
      }
    },
  })
}

/** POST /api/assistant/documents —— 上传知识库文档（txt/md/csv/xlsx/docx/pdf，≤10MB）。 */
export async function uploadDocument(file: File): Promise<KbDocumentView> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<ApiResponse<KbDocumentView>>('/assistant/documents', form, {
    timeout: 60_000,
  })
  return data.data
}

/** GET /api/assistant/documents —— 知识库文档列表（含解析状态/分块数/错误）。 */
export async function listDocuments(): Promise<KbDocumentView[]> {
  const { data } = await http.get<ApiResponse<KbDocumentView[]>>('/assistant/documents')
  return data.data
}

/** DELETE /api/assistant/documents/{id} —— 删除文档（软删文档行 + 物理删除分块）。 */
export async function deleteDocument(id: number): Promise<void> {
  await http.delete(`/assistant/documents/${id}`)
}