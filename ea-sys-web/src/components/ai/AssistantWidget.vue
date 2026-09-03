<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type UploadFile } from 'element-plus'
import { Service, Document, Refresh, Delete, UploadFilled, Promotion } from '@element-plus/icons-vue'
import {
  aiChat as assistantChat,
  uploadDocument,
  listDocuments,
  deleteDocument,
  type AssistantChatEvent,
  type KbDocumentView,
} from '../../api/assistant'
import { aiChat as workflowChat, type AiChatEvent } from '../../api/workflow'
import type { AiGenerateResponse } from '../../api/types'

/*
 * AI 智能客服悬浮窗（右下角）：
 * - 会话 1「智能客服」：/api/assistant/ai-chat（知识库问答、数据问答、人群圈定、触发工作流、工作流创建闲聊引导）
 * - 会话 2「工作流创建」：/api/workflows/ai-chat（复用画布对话引擎，产出草稿 → 存 localStorage 载入画布）
 * - 「知识库」标签：文档上传 / 列表 / 删除
 * 事件帧与画布对话同构；assistant_card / switch_workflow_dialogue 为智能客服专用自定义事件。
 */

type Mode = 'assistant' | 'workflow'
type Tab = 'chat' | 'docs'

interface ToolLine {
  name: string
  status: string
}
interface CardView {
  kind: string
  data: Record<string, unknown>
}
interface ChatMsg {
  id: number
  role: 'user' | 'assistant'
  text: string
  streaming: boolean
  tools: ToolLine[]
  cards: CardView[]
  draft: AiGenerateResponse | null
}
interface PendingConfirm {
  replyId: string
  calls: { id: string; name: string; input: Record<string, unknown> | null }[]
}
interface Session {
  sessionId: string
  msgs: ChatMsg[]
  pending: PendingConfirm | null
}

const router = useRouter()

const open = ref(false)
const activeTab = ref<Tab>('chat')
const activeMode = ref<Mode>('assistant')
const sending = ref(false)
const inputText = ref('')
const msgsEl = ref<HTMLElement | null>(null)

const assistantSession: Session = { sessionId: crypto.randomUUID(), msgs: [], pending: null }
const workflowSession: Session = { sessionId: crypto.randomUUID(), msgs: [], pending: null }

let msgSeq = 0

/* ---------- 知识库文档管理 ---------- */

const docs = ref<KbDocumentView[]>([])
const docsLoading = ref(false)
const docUploading = ref(false)
const docFile = ref<File | null>(null)

function onDocPick(file: File) {
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('单个文件不能超过 10MB')
    docFile.value = null
    return
  }
  docFile.value = file
}

async function refreshDocs() {
  docsLoading.value = true
  try {
    docs.value = await listDocuments()
  } catch (e) {
    ElMessage.error((e as Error).message || '文档列表加载失败')
  } finally {
    docsLoading.value = false
  }
}

async function onDocUpload() {
  if (!docFile.value) {
    ElMessage.warning('请先选择文档（txt/md/csv/xlsx/docx/pdf）')
    return
  }
  docUploading.value = true
  try {
    const view = await uploadDocument(docFile.value)
    docFile.value = null
    ElMessage.success(`文档「${view.name}」已解析入库（${view.chunkCount ?? 0} 个分块）`)
    await refreshDocs()
  } catch (e) {
    ElMessage.error((e as Error).message || '上传失败')
  } finally {
    docUploading.value = false
  }
}

async function onDocDelete(d: KbDocumentView) {
  try {
    await deleteDocument(d.id)
    ElMessage.success(`已删除「${d.name}」`)
    await refreshDocs()
  } catch (e) {
    ElMessage.error((e as Error).message || '删除失败')
  }
}

function docStatusTag(d: KbDocumentView) {
  return d.status === 'ready' ? { text: '已就绪', type: 'success' as const } : { text: d.status, type: 'info' as const }
}

function docSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/* ---------- 对话 ---------- */

interface KbHit {
  documentId: number
  documentName: string
  seq: number
  content: string
  score: number
}
interface KbCard {
  query: string
  hits: KbHit[]
  note: string | null
}
function asKbCard(data: unknown): KbCard {
  const d = (data ?? {}) as Record<string, unknown>
  const hits = Array.isArray(d.hits)
    ? (d.hits as Record<string, unknown>[]).map((h) => ({
        documentId: Number(h.documentId ?? 0),
        documentName: String(h.documentName ?? ''),
        seq: Number(h.seq ?? 0),
        content: String(h.content ?? ''),
        score: Number(h.score ?? 0),
      }))
    : []
  return { query: String(d.query ?? ''), hits, note: d.note == null ? null : String(d.note) }
}

interface TopicRow {
  label: string
  value: string
}
interface TopicView {
  topic: string
  title: string
  rows: TopicRow[]
}
function asStatsCard(data: unknown): TopicView[] {
  const d = (data ?? {}) as Record<string, unknown>
  const topics = Array.isArray(d.topics) ? (d.topics as Record<string, unknown>[]) : []
  return topics.map((t) => {
    const topic = String(t.topic ?? '')
    const rows: TopicRow[] = []
    const items = Array.isArray(t.items) ? (t.items as Record<string, unknown>[]) : []
    if (items.length > 0) {
      // channel / workflow 两类列表型主题：统一按「标签 = 值」平铺
      for (const it of items) {
        for (const [k, v] of Object.entries(it)) {
          if (v == null || v === '') continue
          const label = rowLabel(k)
          rows.push({ label: `${itName(it, topic)}${label}`, value: rowValue(k, v) })
        }
      }
    } else {
      // retention / funnel 标量型主题
      for (const [k, v] of Object.entries(t)) {
        if (k === 'topic' || v == null) continue
        rows.push({ label: rowLabel(k), value: rowValue(k, v) })
      }
    }
    return { topic, title: topicTitle(topic), rows }
  })
}
function rowLabel(key: string): string {
  const M: Record<string, string> = {
    channel: '渠道',
    total: '总量',
    sent: '发送成功',
    failed: '发送失败',
    distinctContacts: '触达人数',
    deliveryRate: '送达率',
    workflowId: '工作流 ID',
    workflowName: '工作流',
    reached: '到达人数',
    retained: '留存人数',
    retentionRate: '留存率',
    days: '观察窗口(天)',
    cohort: '队列人数',
    rate: '留存率',
    seeded: '圈选人数',
    executed: '已执行',
    seededToExecutedRate: '圈选→执行率',
    executedToReachedRate: '执行→到达率',
  }
  return M[key] ?? key
}
function rowValue(key: string, v: unknown): string {
  if (typeof v === 'number') {
    if (key.endsWith('Rate') || key === 'rate') return `${(v * 100).toFixed(1)}%`
    return String(v)
  }
  return String(v)
}
function itName(it: Record<string, unknown>, topic: string): string {
  const n = String(it.channel ?? it.workflowName ?? '')
  return n ? `${n} · ` : topic === 'channel' ? '渠道' : '工作流'
}
function topicTitle(topic: string): string {
  const M: Record<string, string> = { channel: '渠道送达', retention: '留存', funnel: '转化漏斗', workflow: '工作流效果' }
  return M[topic] ?? topic
}

interface AudienceItem {
  id: number
  name: string
  rule: string
}
function asAudiencesCard(data: unknown): { items: AudienceItem[]; note: string | null } {
  if (Array.isArray(data)) {
    const items = (data as Record<string, unknown>[]).map((a) => ({
      id: Number(a.id ?? 0),
      name: String(a.name ?? ''),
      rule: String(a.rule ?? ''),
    }))
    return { items, note: null }
  }
  const d = (data ?? {}) as Record<string, unknown>
  return { items: [], note: d.note == null ? null : String(d.note) }
}

interface WorkflowItem {
  id: number
  name: string
  version: number
  status: string
}
function asWorkflowsCard(data: unknown): WorkflowItem[] {
  if (!Array.isArray(data)) return []
  return (data as Record<string, unknown>[]).map((w) => ({
    id: Number(w.id ?? 0),
    name: String(w.name ?? ''),
    version: Number(w.version ?? 0),
    status: String(w.status ?? ''),
  }))
}

interface TriggerView {
  workflowName: string
  status: string
  totalMembers: number
  durationMs: number
  error: string | null
}
function asTriggerCard(data: unknown): TriggerView | null {
  const d = (data ?? {}) as Record<string, unknown>
  if (!d.workflowName) return null
  return {
    workflowName: String(d.workflowName),
    status: String(d.status ?? ''),
    totalMembers: Number(d.totalMembers ?? 0),
    durationMs: Number(d.durationMs ?? 0),
    error: d.error == null ? null : String(d.error),
  }
}

function toolLabel(name: string): string {
  const M: Record<string, string> = {
    search_kb: '检索知识库',
    query_stats: '查询运营数据',
    search_audiences: '检索人群',
    search_workflows: '检索工作流',
    trigger_workflow: '触发工作流',
    begin_workflow_dialogue: '切换工作流创建',
    list_channels: '查询通道配置',
    search_templates: '检索模板',
    build_dag: '编排 DAG',
    validate_dag: '校验 DAG',
    plan_workflow: '生成计划',
  }
  return M[name] ?? name
}

function pushMsg(session: Session, role: 'user' | 'assistant', text: string): ChatMsg {
  const m: ChatMsg = { id: ++msgSeq, role, text, streaming: false, tools: [], cards: [], draft: null }
  session.msgs.push(m)
  return m
}

function activeSession(): Session {
  return activeMode.value === 'assistant' ? assistantSession : workflowSession
}

/** 仅当「最新一条助手消息」且存在待确认轮时展示确认卡。 */
function isLastAndPending(m: ChatMsg): boolean {
  const s = activeSession()
  if (!s.pending) return false
  const last = s.msgs[s.msgs.length - 1]
  return last?.id === m.id
}

function scrollBottom() {
  setTimeout(() => {
    if (msgsEl.value) msgsEl.value.scrollTop = msgsEl.value.scrollHeight
  }, 20)
}

function initSession(session: Session, greeting: string) {
  if (session.msgs.length > 0) return
  pushMsg(session, 'assistant', greeting)
}

const CHAT_GREETING =
  '你好，我是 AI 智能客服，可以帮你：1. 知识库问答（上传文档后直接提问）；2. 创建运营工作流；3. 触发已发布工作流执行；4. 人群圈定；5. 查看到达率 / 留存率等数据；6. 日常闲聊。试试对我说「查一下近 30 天留存率」或「创建一个每天 9 点的运营工作流」。'
const WORKFLOW_GREETING =
  '描述你想创建的工作流，例如「每天上午9点向近30天未购买会员发送短信」。我会逐项查询通道、模板与人群，和你确认后再生成草稿。'

function switchMode(mode: Mode) {
  activeMode.value = mode
  const session = mode === 'assistant' ? assistantSession : workflowSession
  initSession(session, mode === 'assistant' ? CHAT_GREETING : WORKFLOW_GREETING)
  scrollBottom()
}

/** 校验确认卡里的工具调用（外部 JSON，逐字段校验）。 */
function confirmCalls(v: unknown): { id: string; name: string; input: Record<string, unknown> | null }[] {
  if (!Array.isArray(v)) return []
  return v.flatMap((c) => {
    if (c == null || typeof c !== 'object' || !('id' in c) || !('name' in c)) return []
    if (typeof c.id !== 'string' || typeof c.name !== 'string') return []
    const input = 'input' in c && c.input != null && typeof c.input === 'object' ? c.input : null
    return [{ id: c.id, name: c.name, input: input as Record<string, unknown> | null }]
  })
}

/** 定位工具行：按时间倒序找最新同名行（确认轮恢复执行时结果不带 TOOL_CALL_START）。 */
function findToolLine(session: Session, name: string): ToolLine | null {
  for (let i = session.msgs.length - 1; i >= 0; i--) {
    const m = session.msgs[i]
    if (m.role !== 'assistant') continue
    const hit = [...m.tools].reverse().find((t) => t.name === name)
    if (hit) return hit
  }
  return null
}

async function stream(message: string, confirm: { confirmed: boolean } | undefined) {
  if (sending.value) return
  const session = activeSession()
  const isAssistantMode = activeMode.value === 'assistant'
  pushMsg(session, 'user', message)
  const assistant = pushMsg(session, 'assistant', '')
  assistant.streaming = true
  sending.value = true
  inputText.value = ''
  let autoSwitch = false
  try {
    const onEvent = (ev: AssistantChatEvent | AiChatEvent) => {
      switch (ev.type) {
        case 'TEXT_BLOCK_DELTA':
          assistant.text += String(ev.delta ?? '')
          break
        case 'TEXT_BLOCK_END':
          assistant.streaming = false
          break
        case 'TOOL_CALL_START':
          assistant.tools.push({ name: String(ev.toolCallName ?? ''), status: '执行中' })
          break
        case 'TOOL_RESULT_TEXT_DELTA':
          // 工具结果增量仅作进度参考，卡片数据由 controller 在 TOOL_RESULT_END 时下发
          break
        case 'TOOL_RESULT_END': {
          const line = findToolLine(session, String(ev.toolCallName ?? ''))
          if (line) line.status = String(ev.state ?? '')
          break
        }
        case 'REQUIRE_USER_CONFIRM': {
          session.pending = {
            replyId: String(ev.replyId ?? ''),
            calls: confirmCalls(ev.toolCalls),
          }
          break
        }
        case 'USER_CONFIRM_RESULT': {
          const results = Array.isArray(ev.confirmResults) ? ev.confirmResults : []
          const declined = results.some(
            (r) => r != null && typeof r === 'object' && 'confirmed' in r && r.confirmed === false,
          )
          if (declined) {
            for (const c of session.pending?.calls ?? []) {
              const line = findToolLine(session, c.name)
              if (line && line.status === '执行中') line.status = '已取消'
            }
          }
          session.pending = null
          break
        }
        case 'AGENT_RESULT': {
          if (!assistant.text && ev.summary) assistant.text = String(ev.summary)
          assistant.streaming = false
          break
        }
        case 'assistant_card': {
          const kind = String(ev.kind ?? '')
          if (typeof ev.data === 'object' && ev.data != null) {
            assistant.cards.push({ kind, data: ev.data as Record<string, unknown> })
          }
          break
        }
        case 'switch_workflow_dialogue': {
          // 智能客服里用户提“创建工作流” → 自动切换到工作流创建会话
          autoSwitch = true
          break
        }
        case 'draft_ready': {
          if (ev.draft) assistant.draft = ev.draft as unknown as AiGenerateResponse
          break
        }
      }
    }
    if (isAssistantMode) {
      await assistantChat({ message, sessionId: session.sessionId, confirm }, onEvent)
    } else {
      await workflowChat({ message, sessionId: session.sessionId, confirm }, onEvent)
    }
  } catch (e) {
    assistant.streaming = false
    if (!assistant.text) assistant.text = (e as Error).message || '对话失败'
    ElMessage.error((e as Error).message || '对话失败')
  } finally {
    assistant.streaming = false
    sending.value = false
    scrollBottom()
  }
  // 智能客服流已结束（finally 已复位 sending）再切换并自动发起创建，避免被发送中闸门吞掉
  if (autoSwitch) {
    switchMode('workflow')
    pushMsg(workflowSession, 'assistant', '已为你切换到工作流创建助手，现开始创建：')
    scrollBottom()
    void autoStartWorkflow()
  }
}

async function autoStartWorkflow() {
  if (sending.value) return
  await stream('我来创建一个运营工作流', undefined)
}

function sendInput() {
  const text = inputText.value.trim()
  if (!text || sending.value) return
  void stream(text, undefined)
}

function sendConfirm(confirmed: boolean) {
  if (sending.value) return
  void stream(confirmed ? '确认' : '取消', { confirmed })
}

/** 工作流列表卡上的快捷触发：以点名文案发起（策略器命中唯一已发布工作流后走 HITL 确认）。 */
function quickTrigger(w: WorkflowItem) {
  void stream(`立即执行工作流《${w.name}》`, undefined)
}

/** 草稿卡：写入 localStorage 并跳转画布页（WorkflowCanvasView onMounted 消费）。 */
function loadDraftToCanvas(draft: AiGenerateResponse) {
  localStorage.setItem('ea_sys_ai_draft', JSON.stringify(draft))
  ElMessage.success('草稿已就绪，正在载入画布…')
  router.push('/canvas')
}

function toggleOpen() {
  open.value = !open.value
  if (open.value) {
    switchMode(activeMode.value)
    if (activeTab.value === 'docs') void refreshDocs()
    scrollBottom()
  }
}

function onTabChange(tab: Tab) {
  activeTab.value = tab
  if (tab === 'docs') void refreshDocs()
}

/** 外部 <el-upload> 适配：转 File 校验。 */
function pickDoc(uploadFile: UploadFile) {
  onDocPick(uploadFile.raw as File)
}
</script>

<template>
  <div class="ai-widget">
    <transition name="ai-panel">
      <div v-if="open" class="ai-panel">
        <el-card class="ai-card" shadow="always">
          <template #header>
            <div class="ai-header">
              <span class="ai-title">AI 智能客服</span>
              <el-button link @click="open = false">收起</el-button>
            </div>
            <div class="ai-modes">
              <el-radio-group v-model="activeMode" size="small" @change="(v: string | number | boolean | undefined) => switchMode(v as Mode)">
                <el-radio-button value="assistant">智能客服</el-radio-button>
                <el-radio-button value="workflow">工作流创建</el-radio-button>
              </el-radio-group>
              <el-radio-group v-model="activeTab" size="small" @change="(v: string | number | boolean | undefined) => onTabChange(v as Tab)">
                <el-radio-button value="chat">对话</el-radio-button>
                <el-radio-button value="docs">知识库</el-radio-button>
              </el-radio-group>
            </div>
          </template>

          <!-- 对话标签 -->
          <div v-if="activeTab === 'chat'" class="ai-chat">
            <div ref="msgsEl" class="ai-msgs">
              <div v-for="m in (activeMode === 'assistant' ? assistantSession.msgs : workflowSession.msgs)" :key="m.id" class="ai-msg" :class="m.role">
                <div v-if="m.role === 'assistant'" class="ai-bubble">
                  <template v-if="m.text">{{ m.text }}<span v-if="m.streaming" class="ai-cursor">▍</span></template>
                  <template v-else-if="m.streaming">思考中…</template>
                </div>
                <div v-else class="ai-bubble">{{ m.text }}</div>

                <!-- 工具状态行 -->
                <div v-if="m.tools.length" class="ai-tools">
                  <div v-for="(t, i) in m.tools" :key="i" class="ai-tool">
                    <span class="ai-tool-name">{{ toolLabel(t.name) }}</span>
                    <el-tag size="small" :type="t.status === 'SUCCESS' ? 'success' : t.status === 'ERROR' ? 'danger' : 'info'">
                      {{ t.status === 'SUCCESS' ? '完成' : t.status === 'ERROR' ? '失败' : t.status === '已取消' ? '已取消' : '执行中' }}
                    </el-tag>
                  </div>
                </div>

                <!-- 卡片区 -->
                <div v-for="(c, ci) in m.cards" :key="ci" class="ai-card-area">
                  <!-- 知识库引用卡 -->
                  <div v-if="c.kind === 'kb'" class="ai-inner-card">
                    <div class="ai-card-title">知识库引用</div>
                    <template v-if="asKbCard(c.data).hits.length">
                      <div v-for="h in asKbCard(c.data).hits" :key="h.documentId + '-' + h.seq" class="kb-hit">
                        <div class="kb-hit-head">《{{ h.documentName }}》 · 段落 {{ h.seq }} · 相关度 {{ (h.score * 100).toFixed(0) }}%</div>
                        <div class="kb-hit-body">{{ h.content }}</div>
                      </div>
                    </template>
                    <el-alert v-else type="info" :closable="false" class="kb-miss">
                      {{ asKbCard(c.data).note || '知识库暂未找到相关内容，可先上传文档再问' }}
                    </el-alert>
                  </div>

                  <!-- 统计卡 -->
                  <div v-else-if="c.kind === 'stats'" class="ai-inner-card">
                    <div class="ai-card-title">运营数据</div>
                    <div v-for="(t, ti) in asStatsCard(c.data)" :key="ti" class="stats-topic">
                      <div class="stats-topic-title">{{ t.title }}</div>
                      <table class="stats-table">
                        <tbody>
                          <tr v-for="(row, ri) in t.rows" :key="ri">
                            <td class="stats-k">{{ row.label }}</td>
                            <td class="stats-v">{{ row.value }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>

                  <!-- 人群卡 -->
                  <div v-else-if="c.kind === 'audiences'" class="ai-inner-card">
                    <div class="ai-card-title">当前租户人群</div>
                    <template v-if="asAudiencesCard(c.data).items.length">
                      <div v-for="(a, ai) in asAudiencesCard(c.data).items" :key="ai" class="audience-item">
                        <span>{{ a.name }}</span>
                        <span class="audience-id">#{{ a.id }}</span>
                      </div>
                    </template>
                    <el-alert v-else type="info" :closable="false">
                      {{ asAudiencesCard(c.data).note || '暂无人群，可先到「人群管理」创建' }}
                    </el-alert>
                  </div>

                  <!-- 工作流列表卡（快捷触发） -->
                  <div v-else-if="c.kind === 'workflows'" class="ai-inner-card">
                    <div class="ai-card-title">可触发的工作流</div>
                    <div v-for="(w, wi) in asWorkflowsCard(c.data)" :key="wi" class="wf-item">
                      <div class="wf-item-info">
                        <span class="wf-item-name">《{{ w.name }}》</span>
                        <el-tag size="small" type="success">v{{ w.version }}</el-tag>
                      </div>
                      <el-button size="small" type="primary" plain :disabled="sending" @click="quickTrigger(w)">
                        立即触发
                      </el-button>
                    </div>
                    <el-alert v-if="!asWorkflowsCard(c.data).length" type="info" :closable="false">
                      没有已发布的工作流可供触发
                    </el-alert>
                  </div>

                  <!-- 触发结果卡 -->
                  <div v-else-if="c.kind === 'trigger'" class="ai-inner-card ai-trigger">
                    <template v-if="asTriggerCard(c.data)">
                      <div class="ai-card-title">触发结果</div>
                      <div class="trigger-line">工作流：<b>《{{ asTriggerCard(c.data)?.workflowName }}》</b></div>
                      <div class="trigger-line">状态：<b>{{ asTriggerCard(c.data)?.status }}</b></div>
                      <div class="trigger-line">覆盖人数：{{ asTriggerCard(c.data)?.totalMembers }}</div>
                      <div v-if="asTriggerCard(c.data)?.durationMs" class="trigger-line">
                        耗时：{{ Math.round((asTriggerCard(c.data)?.durationMs ?? 0) / 100) / 10 }}s
                      </div>
                      <el-alert v-if="asTriggerCard(c.data)?.error" type="error" :closable="false">
                        {{ asTriggerCard(c.data)?.error }}
                      </el-alert>
                    </template>
                  </div>
                </div>

                <!-- HITL 确认 -->
                <div v-if="isLastAndPending(m)" class="ai-confirm">
                  <div class="ai-confirm-text">
                    需要确认：{{ toolLabel(activeSession().pending?.calls[0]?.name ?? '') }}（人工确认后才会执行）
                  </div>
                  <div class="ai-confirm-actions">
                    <el-button type="primary" size="small" :disabled="sending" @click="sendConfirm(true)">确认执行</el-button>
                    <el-button size="small" :disabled="sending" @click="sendConfirm(false)">取消</el-button>
                  </div>
                </div>

                <!-- 草稿卡（工作流创建会话） -->
                <div v-if="m.draft" class="ai-draft">
                  <div class="ai-draft-title">
                    草稿已生成：《{{ m.draft.workflowDraft.name || '未命名工作流' }}》（{{ m.draft.workflowDraft.nodes.length }} 节点）
                  </div>
                  <div v-if="m.draft.planSummary" class="ai-draft-summary">{{ m.draft.planSummary }}</div>
                  <el-button type="primary" size="small" :disabled="sending" @click="loadDraftToCanvas(m.draft)">
                    载入画布
                  </el-button>
                </div>
              </div>
            </div>

            <!-- 输入区 -->
            <div class="ai-input">
              <el-input
                v-model="inputText"
                :placeholder="sending ? '智能客服思考中…' : '输入消息，回车发送'"
                :disabled="sending || !!activeSession().pending"
                @keyup.enter="sendInput"
              >
                <template #append>
                  <el-button :icon="Promotion" :disabled="sending" @click="sendInput">发送</el-button>
                </template>
              </el-input>
            </div>
          </div>

          <!-- 知识库标签 -->
          <div v-else class="ai-docs">
            <div class="ai-docs-upload">
              <el-upload
                :auto-upload="false"
                :show-file-list="false"
                accept=".txt,.md,.csv,.xlsx,.docx,.pdf"
                :on-change="pickDoc"
              >
                <el-button :icon="UploadFilled" :disabled="docUploading">选择文档</el-button>
              </el-upload>
              <el-button type="primary" :icon="UploadFilled" :loading="docUploading" @click="onDocUpload">
                上传入库
              </el-button>
            </div>
            <div v-if="docFile" class="ai-docs-file">已选择：{{ docFile.name }}（{{ docSize(docFile.size) }}）</div>
            <div v-if="!docFile && !docsLoading" class="ai-docs-hint">支持 txt / md / csv / xlsx / docx / pdf，单个 ≤10MB</div>

            <div class="ai-docs-list">
              <el-empty v-if="!docsLoading && !docs.length" description="知识库暂无文档" :image-size="60" />
              <div v-for="d in docs" :key="d.id" class="ai-doc">
                <div class="ai-doc-info">
                  <div class="ai-doc-name">
                    <el-icon><Document /></el-icon>
                    {{ d.name }}
                  </div>
                  <div class="ai-doc-meta">
                    <el-tag size="small" :type="docStatusTag(d).type">{{ docStatusTag(d).text }}</el-tag>
                    <span>{{ docSize(d.sizeBytes) }} · {{ d.chunkCount ?? 0 }} 分块</span>
                  </div>
                </div>
                <el-button size="small" link type="danger" :icon="Delete" @click="onDocDelete(d)">删除</el-button>
              </div>
            </div>
          </div>
        </el-card>
      </div>
    </transition>

    <!-- 悬浮开关 -->
    <el-button v-if="!open" class="ai-fab" type="primary" :icon="Service" @click="toggleOpen">
      AI 客服
    </el-button>
    <el-button v-else class="ai-fab ai-fab-close" circle @click="toggleOpen">
      <el-icon><Refresh /></el-icon>
    </el-button>
  </div>
</template>

<style scoped>
.ai-widget {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2000;
}
.ai-fab {
  height: 44px;
  border-radius: 22px;
  box-shadow: 0 4px 16px rgba(64, 158, 255, 0.35);
}
.ai-fab-close {
  width: 44px;
  color: #606266;
}
.ai-panel {
  position: absolute;
  right: 0;
  bottom: 56px;
  width: 380px;
  height: 560px;
}
.ai-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  border-radius: 10px;
}
.ai-card :deep(.el-card__body) {
  flex: 1;
  overflow: hidden;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
}
.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ai-title {
  font-weight: 600;
  font-size: 15px;
}
.ai-modes {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
}
.ai-chat {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
.ai-msgs {
  flex: 1;
  overflow-y: auto;
  padding: 4px 2px;
}
.ai-msg {
  margin-bottom: 10px;
}
.ai-msg.user {
  display: flex;
  justify-content: flex-end;
}
.ai-bubble {
  max-width: 88%;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
  white-space: pre-wrap;
}
.ai-msg.assistant .ai-bubble {
  background: #f4f4f5;
  color: #303133;
  border-top-left-radius: 2px;
}
.ai-msg.user .ai-bubble {
  background: #409eff;
  color: #fff;
  border-top-right-radius: 2px;
}
.ai-cursor {
  animation: ai-blink 1s steps(1) infinite;
}
@keyframes ai-blink {
  50% {
    opacity: 0;
  }
}
.ai-tools {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ai-tool {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  background: #fafafa;
  border-radius: 6px;
  font-size: 12px;
}
.ai-tool-name {
  color: #606266;
}
.ai-card-area {
  margin-top: 6px;
}
.ai-inner-card {
  background: #f9fbff;
  border: 1px solid #e6edf8;
  border-radius: 8px;
  padding: 8px 10px;
}
.ai-card-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
  color: #1f2d3d;
}
.kb-hit {
  margin-bottom: 6px;
}
.kb-hit-head {
  font-size: 12px;
  color: #409eff;
  margin-bottom: 2px;
}
.kb-hit-body {
  font-size: 12px;
  color: #606266;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.kb-miss {
  margin-top: 0;
}
.stats-topic {
  margin-bottom: 8px;
}
.stats-topic-title {
  font-size: 12px;
  font-weight: 600;
  color: #409eff;
  margin-bottom: 4px;
}
.stats-table {
  width: 100%;
  font-size: 12px;
  border-collapse: collapse;
}
.stats-table td {
  padding: 2px 6px;
  border-bottom: 1px solid #f0f2f5;
}
.stats-k {
  color: #909399;
  width: 45%;
}
.stats-v {
  text-align: right;
  color: #303133;
}
.audience-item {
  display: flex;
  justify-content: space-between;
  padding: 3px 0;
  font-size: 13px;
  border-bottom: 1px solid #f0f2f5;
}
.audience-id {
  color: #909399;
  font-size: 12px;
}
.wf-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 0;
  border-bottom: 1px solid #f0f2f5;
}
.wf-item-info {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.wf-item-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.trigger-line {
  font-size: 13px;
  margin-bottom: 4px;
}
.ai-confirm {
  margin-top: 8px;
  border: 1px dashed #e6a23c;
  background: #fdf6ec;
  border-radius: 8px;
  padding: 8px 10px;
}
.ai-confirm-text {
  font-size: 13px;
  color: #b88230;
  margin-bottom: 6px;
}
.ai-confirm-actions {
  display: flex;
  gap: 8px;
}
.ai-draft {
  margin-top: 8px;
  border: 1px solid #409eff;
  background: #ecf5ff;
  border-radius: 8px;
  padding: 8px 10px;
}
.ai-draft-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f2d3d;
  margin-bottom: 4px;
}
.ai-draft-summary {
  font-size: 12px;
  color: #606266;
  margin-bottom: 8px;
  white-space: pre-wrap;
}
.ai-input {
  margin-top: 8px;
}
.ai-input :deep(.el-input-group__append) {
  padding: 0;
}
.ai-input :deep(.el-input-group__append .el-button) {
  margin: 0;
}
.ai-docs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.ai-docs-upload {
  display: flex;
  gap: 8px;
  margin-bottom: 6px;
}
.ai-docs-file {
  font-size: 12px;
  color: #409eff;
  margin-bottom: 4px;
}
.ai-docs-hint {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.ai-docs-list {
  flex: 1;
  overflow-y: auto;
  margin-top: 4px;
}
.ai-doc {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 6px;
  border-bottom: 1px solid #f0f2f5;
}
.ai-doc-info {
  min-width: 0;
}
.ai-doc-name {
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-doc-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.ai-panel-enter-active,
.ai-panel-leave-active {
  transition: transform 0.25s ease, opacity 0.25s ease;
}
.ai-panel-enter-from,
.ai-panel-leave-to {
  transform: translateY(12px);
  opacity: 0;
}
</style>