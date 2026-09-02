<script setup lang="ts">
/**
 * M2：DAG 编排画布。
 * Vue Flow 画布 ↔ 后端 SaveWorkflowRequest 双向映射：
 *  - VueFlow Node.id = 后端 node.key；position = {x,y}；data.real = WorkflowNodeSpec（除 position）
 *  - VueFlow Edge.id = `${source}->${target}`；data.condition = 边条件 DSL（null = else 兜底）
 * 操作流：拖放/点选节点 → 连线 → 配置 → 保存 → 校验 → 发布 → 干跑（选 ready 快照）→ 报告。
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { VueFlow, MarkerType, useVueFlow } from '@vue-flow/core'
import type { Connection, Edge, Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import dagre from '@dagrejs/dagre'
import CanvasNode from '../components/canvas/CanvasNode.vue'
import EdgeConditionEditor from '../components/canvas/EdgeConditionEditor.vue'
import {
  aiChat,
  createWorkflow,
  downloadPlanValidationTemplate,
  dryRunWorkflow,
  getPlanValidation,
  getWorkflow,
  importPlanValidation,
  publishWorkflow,
  updateWorkflow,
  validateWorkflow,
} from '../api/workflow'
import { listTemplates } from '../api/template'
import { listAudiences, listSnapshots } from '../api/audience'
import type {
  AiGenerateResponse,
  Audience,
  AudienceSnapshot,
  ConditionRule,
  DryRunResponse,
  PlanValidationView,
  SaveWorkflowRequest,
  Template,
  ValidationResponse,
  WorkflowNodeSpec,
  WorkflowNodeType,
} from '../api/types'

const route = useRoute()
const router = useRouter()

const workflowId = computed(() => (route.params.id ? Number(route.params.id) : null))

/* ---------- 画布状态 ---------- */

/**
 * Vue Flow 的 Node/Edge 是联合类型且部分字段可选，精确泛型会引发深度实例化。
 * 数组保留库默认类型，业务访问统一走 cast 辅助（data 在 push/load 时总是写入）。
 */
type CanvasNodeData = { real: WorkflowNodeSpec }
type CanvasEdgeData = { condition: ConditionRule | null }

const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const { screenToFlowCoordinate, fitView } = useVueFlow()

function realOf(n: LiteNode): WorkflowNodeSpec {
  return n.data.real
}
function condOf(e: LiteEdge | undefined): ConditionRule | null {
  return (e?.data.condition ?? null) as ConditionRule | null
}
function dataOf(n: LiteNode): CanvasNodeData {
  return n.data
}
function edgeDataOf(e: LiteEdge): CanvasEdgeData {
  return e.data
}

const nodeTypes = { canvas: CanvasNode }

/* ---------- 工具栏状态 ---------- */

const name = ref('')
const description = ref('')
const status = ref<'draft' | 'published' | 'archived' | ''>('')
const version = ref(0)
const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)

const templates = ref<Template[]>([])
const snapshots = ref<AudienceSnapshot[]>([])
const audiences = ref<Audience[]>([])
const dryRunAudienceId = ref<number | null>(null)
const reportVisible = ref(false)

/* 节点面板（后端 WORKFLOW 节点类型；AGENT_SPLIT 按发布策略分层分流） */
const PALETTE: Array<{ type: WorkflowNodeType; label: string }> = [
  { type: 'TRIGGER', label: '触发' },
  { type: 'CONDITION', label: '条件' },
  { type: 'AGENT_SPLIT', label: 'Agent 分流' },
  { type: 'ACTION', label: '动作' },
  { type: 'UPDATE', label: '更新' },
  { type: 'DELAY', label: '延时' },
  { type: 'END', label: '结束' },
]

const selectedKind = ref<'node' | 'edge' | null>(null)
const selectedId = ref<string>('')

/**
 * 业务视图类型：覆盖画布操作所需的全部字段，避开 Vue Flow Node/Edge 联合泛型的深度实例化。
 * 库的 Node/Edge 结构上可赋值给 Lite 类型（id/position/source/target/data 齐备）。
 */
type LiteNode = { id: string; type?: string; position: { x: number; y: number }; data: CanvasNodeData }
type LiteEdge = { id: string; source: string; target: string; type?: string; markerEnd?: { type: string }; data: CanvasEdgeData }
const liteNodes = computed(() => nodes.value as unknown as LiteNode[])
const liteEdges = computed(() => edges.value as unknown as LiteEdge[])
/** 业务读写一律走 Lite 视图；v-model 绑定仍用原始 ref。 */
function allNodes(): LiteNode[] {
  return nodes.value as unknown as LiteNode[]
}
function allEdges(): LiteEdge[] {
  return edges.value as unknown as LiteEdge[]
}
function setNodes(v: LiteNode[]) {
  nodes.value = v as unknown as Node[]
}
function setEdges(v: LiteEdge[]) {
  edges.value = v as unknown as Edge[]
}

const selectedNode = computed(() =>
  selectedKind.value === 'node' ? liteNodes.value.find((n) => n.id === selectedId.value) : undefined,
)
const selectedEdge = computed(() =>
  selectedKind.value === 'edge' ? liteEdges.value.find((e) => e.id === selectedId.value) : undefined,
)

const NODE_KEY_COUNTERS: Record<WorkflowNodeType, number> = {
  TRIGGER: 0, CONDITION: 0, AGENT_SPLIT: 0, DELAY: 0, ACTION: 0, UPDATE: 0, END: 0,
}

function nextKey(type: WorkflowNodeType): string {
  NODE_KEY_COUNTERS[type] += 1
  return `${type.toLowerCase()}_${NODE_KEY_COUNTERS[type]}`
}

/** 从面板添加节点（画布可视区中心偏右下落，级联偏移）。 */
async function addFromPalette(type: WorkflowNodeType) {
  const pos = screenToFlowCoordinate({ x: window.innerWidth / 2, y: window.innerHeight / 2 })
  let key = nextKey(type)
  while (nodes.value.some((n) => n.id === key)) key = nextKey(type)
  const offset = nodes.value.length * 24
  nodes.value.push({
    id: key,
    type: 'canvas',
    position: { x: pos.x - 80 + offset, y: pos.y - 28 + offset },
    data: { real: { key, type, name: '', config: {}, position: null } },
  })
  selectNode(key)
}

/* ---------- 选中 ---------- */

function selectNode(id: string) {
  selectedKind.value = 'node'
  selectedId.value = id
}
function selectEdge(id: string) {
  selectedKind.value = 'edge'
  selectedId.value = id
}
function clearSelection() {
  selectedKind.value = null
  selectedId.value = ''
}

function deleteSelected() {
  if (selectedKind.value === 'node') {
    const id = selectedId.value
    const dependents = allEdges().filter((e) => e.source === id || e.target === id)
    if (dependents.length) {
      ElMessage.warning('请先断开该节点的连线')
      return
    }
    setNodes(allNodes().filter((n) => n.id !== id))
    clearSelection()
  } else if (selectedKind.value === 'edge') {
    setEdges(allEdges().filter((e) => e.id !== selectedId.value))
    clearSelection()
  }
}

/* ---------- 连线 ---------- */

const CHANNEL_IDS = ['sms', 'email']

function canConnect(conn: Connection): boolean {
  if (!conn.source || !conn.target) return false
  const src = allNodes().find((n) => n.id === conn.source)
  const tgt = allNodes().find((n) => n.id === conn.target)
  if (!src || !tgt) return false
  const srcType = (realOf(src).type as WorkflowNodeType) ?? 'END'
  const tgtType = (realOf(tgt).type as WorkflowNodeType) ?? 'TRIGGER'
  if (srcType === 'END') {
    ElMessage.warning('END 节点不能有出边')
    return false
  }
  if (tgtType === 'TRIGGER') {
    ElMessage.warning('TRIGGER 节点不能有入边')
    return false
  }
  if (allEdges().some((e) => e.source === conn.source && e.target === conn.target)) {
    ElMessage.warning('两点之间已有连线')
    return false
  }
  if (srcType !== 'CONDITION' && srcType !== 'AGENT_SPLIT') {
    if (allEdges().some((e) => e.source === conn.source)) {
      ElMessage.warning('该节点已有一条出边（仅 CONDITION 可多路分流）')
      return false
    }
  }
  if (conn.source === conn.target) {
    ElMessage.warning('不能自连')
    return false
  }
  return true
}

function onConnect(conn: Connection) {
  if (!canConnect(conn)) return
  edges.value.push({
    id: `${conn.source}->${conn.target}`,
    source: conn.source!,
    target: conn.target!,
    type: 'smoothstep',
    markerEnd: { type: MarkerType.ArrowClosed },
    data: { condition: null },
  })
}

/* ---------- 节点配置 ---------- */

function nodeTypeOf(n: LiteNode): WorkflowNodeType {
  return (realOf(n).type as WorkflowNodeType) ?? 'END'
}

function nodeConfig(n: LiteNode): Record<string, unknown> {
  return (realOf(n).config ?? {}) as Record<string, unknown>
}

/** 事件过滤 JSON 编辑器：合法 JSON 提交入 config，非法暂存原始文本等待修正。 */
const eventFilterDraft = ref<string | null>(null)
const eventFilterText = computed<string>({
  get: () => {
    if (eventFilterDraft.value != null) return eventFilterDraft.value
    if (!selectedNode.value) return ''
    const f = nodeConfig(selectedNode.value).eventFilter
    return f ? JSON.stringify(f, null, 2) : ''
  },
  set: (v: string) => {
    const trimmed = v.trim()
    if (!trimmed) {
      updateNodeConfig('eventFilter', null)
      eventFilterDraft.value = null
      return
    }
    try {
      updateNodeConfig('eventFilter', JSON.parse(trimmed))
      eventFilterDraft.value = null
    } catch {
      eventFilterDraft.value = v
    }
  },
})
function setEventFilterText(v: string) {
  eventFilterText.value = v
}

function updateNodeName(value: string) {
  const node = selectedNode.value
  if (!node) return
  const d = dataOf(node)
  d.real.name = value
  node.data = { ...d }
}

function updateNodeConfig(k: string, value: unknown) {
  const node = selectedNode.value
  if (!node) return
  const d = dataOf(node)
  const cfg = (d.real.config ?? {}) as Record<string, unknown>
  if (value === null || value === undefined || value === '') delete cfg[k]
  else cfg[k] = value
  d.real.config = cfg
  node.data = { ...d }
}

function nodeLabel(n: LiteNode): string {
  return realOf(n).name?.trim() || PALETTE.find((p) => p.type === nodeTypeOf(n))?.label || nodeTypeOf(n)
}

/** 条件分支模型：condition=null 为 else 兜底 */
const edgeMode = computed<'else' | 'if'>(() => (condOf(selectedEdge.value) ? 'if' : 'else'))

function setEdgeModeOf(edge: LiteEdge | undefined, mode: 'else' | 'if') {
  if (!edge) return
  const d = edgeDataOf(edge)
  if (mode === 'else') {
    d.condition = null
  } else {
    d.condition = d.condition ?? { op: 'AND', items: [{ field: 'event.channel', op: 'equals', value: '' }] }
  }
  edge.data = { ...d }
}

function setEdgeMode(mode: 'else' | 'if') {
  setEdgeModeOf(selectedEdge.value, mode)
}

function updateEdgeConditionOf(edge: LiteEdge | undefined, value: ConditionRule) {
  if (!edge) return
  const d = edgeDataOf(edge)
  d.condition = value
  edge.data = { ...d }
}

function updateEdgeCondition(value: ConditionRule) {
  updateEdgeConditionOf(selectedEdge.value, value)
}

/** 从某节点出发的出边（CONDITION 节点侧配置用） */
function outEdgesOf(nodeKey: string): LiteEdge[] {
  return liteEdges.value.filter((e) => e.source === nodeKey)
}

/* ---------- 模板 / 快照数据 ---------- */

async function loadTemplates() {
  templates.value = await listTemplates()
}

async function loadAudiences() {
  const page = await listAudiences(1, 100)
  audiences.value = page.records
}

async function selectAudience(id: number) {
  snapshots.value = []
  if (!id) return
  const page = await listSnapshots(id, 1, 100)
  // 干跑需要 ready 快照
  snapshots.value = page.records.filter((s) => s.status === 'ready')
}

/* ---------- 加载 / 保存 ---------- */

async function load() {
  if (!workflowId.value) {
    // 新画布也要加载模板，供 ACTION 节点配置下拉使用
    await loadTemplates()
    return
  }
  const wf = await getWorkflow(workflowId.value)
  // 播种节点 key 计数器，避免新节点与已加载节点重名
  wf.nodes.forEach((spec) => {
    const m = /^(TRIGGER|CONDITION|AGENT_SPLIT|DELAY|ACTION|UPDATE|END)_(\d+)$/.exec(spec.key)
    if (m) {
      const t = m[1] as WorkflowNodeType
      const n = Number(m[2])
      if (n > NODE_KEY_COUNTERS[t]) NODE_KEY_COUNTERS[t] = n
    }
  })
  name.value = wf.name
  description.value = wf.description
  status.value = wf.status
  version.value = wf.version
  setNodes(
    wf.nodes.map((spec): LiteNode => ({
      id: spec.key,
      type: 'canvas',
      position: spec.position && !Number.isNaN(spec.position.x) ? spec.position : { x: 120, y: 80 },
      data: { real: { ...spec, position: null } },
    })),
  )
  setEdges(
    (wf.edges ?? []).map((spec): LiteEdge => ({
      id: `${spec.source}->${spec.target}`,
    source: spec.source,
    target: spec.target,
      type: 'smoothstep',
      markerEnd: { type: MarkerType.ArrowClosed },
      data: { condition: spec.condition ?? null },
    })),
  )
  // 无位置数据 → 自动布局（dagre，LR 拓扑，覆盖手工空 position）
  const positioned = nodes.value.some((n) => n.position.x !== 120 || n.position.y !== 80)
  if (!positioned && nodes.value.length > 0) {
    setTimeout(autoLayout, 0)
  }
  await loadTemplates()
  fitView({ padding: 0.15 })
}

function buildRequest(): SaveWorkflowRequest {
  return {
    name: name.value.trim(),
    description: description.value.trim() || undefined,
    nodes: allNodes().map((n) => {
      const real = realOf(n)
      return {
        key: real.key,
        type: real.type,
        name: real.name?.trim() || undefined,
        config: (real.config && Object.keys(real.config).length ? real.config : null) ?? null,
        position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
      }
    }),
    edges: allEdges().map((e) => ({
      source: e.source,
      target: e.target,
      condition: condOf(e),
    })),
  }
}

/** 持久化：已存则覆盖当前行；未存则新建并跳转。返回最终业务 id。 */
async function saveOnce(): Promise<number | null> {
  if (!name.value.trim()) {
    ElMessage.warning('请填写工作流名称')
    return null
  }
  saving.value = true
  try {
    const req = buildRequest()
    let id = workflowId.value
    if (id != null) {
      const wf = await updateWorkflow(id, req)
      status.value = wf.status
      version.value = wf.version
    } else {
      const wf = await createWorkflow(req)
      id = wf.id
      router.replace(`/canvas/${id}`)
      status.value = wf.status
      version.value = wf.version
    }
    await loadTemplates()
    return id
  } catch (e) {
    ElMessage.error((e as Error).message || '保存失败')
    return null
  } finally {
    saving.value = false
  }
}

async function save() {
  const id = await saveOnce()
  if (id != null) {
    ElMessage.success('已保存')
  }
}

async function validate() {
  const id = await saveOnce()
  if (id == null) return
  validating.value = true
  try {
    const res: ValidationResponse = await validateWorkflow(id)
    if (res.valid) {
      ElMessage.success('校验通过')
    } else {
      ElMessageBox.alert(res.errors.map((e) => `<div>${e}</div>`).join(''), '校验未通过（点击关闭后修改画布）', {
        type: 'error',
        dangerouslyUseHTMLString: true,
      })
    }
  } catch (e) {
    ElMessage.error((e as Error).message || '校验失败')
  } finally {
    validating.value = false
  }
}

async function publish() {
  const id = await saveOnce()
  if (id == null) return
  try {
    publishing.value = true
    const wf = await publishWorkflow(id)
    status.value = wf.status
    version.value = wf.version
    ElMessage.success(`已发布 v${wf.version}`)
  } catch (e) {
    ElMessage.error((e as Error).message || '发布失败')
  } finally {
    publishing.value = false
  }
}

/* ---------- 干跑 ---------- */

const dryRunDialog = ref(false)
const dryRunSnapshotId = ref<number | null>(null)
const report = ref<DryRunResponse | null>(null)

async function openDryRun() {
  if (!workflowId.value) return
  await loadAudiences()
  dryRunAudienceId.value = null
  dryRunSnapshotId.value = null
  snapshots.value = []
  dryRunDialog.value = true
}

async function runDryRun() {
  if (!dryRunSnapshotId.value) {
    ElMessage.warning('请选择人群快照')
    return
  }
  try {
    report.value = await dryRunWorkflow(workflowId.value!, { audienceSnapshotId: dryRunSnapshotId.value })
    dryRunDialog.value = false
    reportVisible.value = true
  } catch (e) {
    ElMessage.error((e as Error).message || '干跑失败')
  }
}

/* ---------- 计划导入校验 ---------- */

const planImportDialog = ref(false)
const planImportFile = ref<File | null>(null)
const planImporting = ref(false)
const planReportVisible = ref(false)
const planReport = ref<PlanValidationView | null>(null)

function openPlanImport() {
  planImportFile.value = null
  planImportDialog.value = true
}

function onPlanFileChange(file: File) {
  planImportFile.value = file
}

async function downloadPlanTemplate(type: 'xlsx' | 'csv') {
  try {
    const blob = await downloadPlanValidationTemplate(type)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `计划导入模板.${type}`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error((e as Error).message || '模板下载失败')
  }
}

async function runPlanImport() {
  if (!planImportFile.value) {
    ElMessage.warning('请选择计划文件（.xlsx / .csv）')
    return
  }
  planImporting.value = true
  try {
    const view = await importPlanValidation(workflowId.value!, planImportFile.value)
    planImportDialog.value = false
    planReport.value = view
    planReportVisible.value = true
    if (view.decision === 'PASSED') {
      ElMessage.success(`校验通过：${view.summary.passed} 项一致`)
    } else if (view.decision === 'WARNINGS') {
      ElMessage.warning(`校验有告警：${view.summary.warnings} 项需关注`)
    } else {
      ElMessage.error(`校验未通过：${view.summary.conflicts} 项冲突，发布将被拦截`)
    }
  } catch (e) {
    ElMessage.error((e as Error).message || '计划校验失败')
  } finally {
    planImporting.value = false
  }
}

async function openPlanReport() {
  if (!workflowId.value) return
  try {
    const view = await getPlanValidation(workflowId.value)
    if (!view) {
      ElMessage.info('尚无校验记录，请先导入计划')
      return
    }
    planReport.value = view
    planReportVisible.value = true
  } catch (e) {
    ElMessage.error((e as Error).message || '获取校验报告失败')
  }
}

/* ---------- AI 创建（流式对话 + HITL 确认） ---------- */

interface AiToolLine {
  name: string
  status: string
  delta: string
}
interface AiChatMsg {
  id: number
  role: 'user' | 'assistant'
  text: string
  streaming: boolean
  tools: AiToolLine[]
}

const aiDialog = ref(false)
const chatSessionId = ref('')
const chatMsgs = ref<AiChatMsg[]>([])
const chatInput = ref('')
const chatSending = ref(false)
/** 挂起中的 HITL 确认（卡片出现时输入框禁用，只能确认/取消）。 */
const pendingConfirm = ref<{
  replyId: string
  calls: { id: string; name: string; input: Record<string, unknown> | null }[]
} | null>(null)
const latestDraft = ref<AiGenerateResponse | null>(null)
/** 草稿卡挂在哪条助手消息下（draft_ready 到达时是当前流消息）。 */
const latestDraftMsgId = ref<number | null>(null)
const chatBodyRef = ref<HTMLElement | null>(null)
let msgSeq = 0

function pushMsg(role: 'user' | 'assistant', text: string): AiChatMsg {
  const m: AiChatMsg = { id: ++msgSeq, role, text, streaming: false, tools: [] }
  chatMsgs.value.push(m)
  return m
}

function openAiDialog() {
  chatSessionId.value = crypto.randomUUID()
  chatMsgs.value = []
  chatInput.value = ''
  pendingConfirm.value = null
  latestDraft.value = null
  latestDraftMsgId.value = null
  msgSeq = 0
  aiDialog.value = true
  pushMsg(
    'assistant',
    '描述你想创建的工作流，例如「每天上午9点向近30天未购买会员发送短信」。我会逐项查询通道、模板与人群，和你确认后再生成草稿。',
  )
}

async function sendChat() {
  const text = chatInput.value.trim()
  if (!text || chatSending.value) return
  await stream(text, undefined)
}

async function sendConfirm(confirmed: boolean) {
  if (chatSending.value) return
  await stream(confirmed ? '确认生成' : '取消', { confirmed })
}

/**
 * 定位工具行：按时间倒序找最新同名行——正常轮命中当前消息；
 * 确认轮恢复执行时结果事件不带 TOOL_CALL_START，会命中上一轮挂起的行。
 */
function findToolLine(name: string): AiToolLine | null {
  for (let i = chatMsgs.value.length - 1; i >= 0; i--) {
    const m = chatMsgs.value[i]
    if (m.role !== 'assistant') continue
    const hit = [...m.tools].reverse().find((t) => t.name === name)
    if (hit) return hit
  }
  return null
}

/** 从 SSE 帧还原确认卡片里的工具调用（外部 JSON，逐字段校验）。 */
function confirmCalls(v: unknown): { id: string; name: string; input: Record<string, unknown> | null }[] {
  if (!Array.isArray(v)) return []
  return v.flatMap((c) => {
    if (c == null || typeof c !== 'object' || !('id' in c) || !('name' in c)) return []
    if (typeof c.id !== 'string' || typeof c.name !== 'string') return []
    const input = 'input' in c && c.input != null && typeof c.input === 'object' ? c.input : null
    return [{ id: c.id, name: c.name, input: input as Record<string, unknown> | null }]
  })
}

async function stream(message: string, confirm: { confirmed: boolean } | undefined) {
  pushMsg('user', message)
  const assistant = pushMsg('assistant', '')
  assistant.streaming = true
  chatSending.value = true
  chatInput.value = ''
  try {
    await aiChat({ message, sessionId: chatSessionId.value, confirm }, (ev) => {
      switch (ev.type) {
        case 'TEXT_BLOCK_DELTA':
          assistant.text += String(ev.delta ?? '')
          break
        case 'TEXT_BLOCK_END':
          assistant.streaming = false
          break
        case 'TOOL_CALL_START':
          assistant.tools.push({ name: String(ev.toolCallName ?? ''), status: '执行中', delta: '' })
          break
        case 'TOOL_RESULT_TEXT_DELTA': {
          // 确认轮恢复执行时结果属于上一轮挂起的工具行（本消息无 TOOL_CALL_START）
          const line = findToolLine(String(ev.toolCallName ?? ''))
          if (line) line.delta += String(ev.delta ?? '')
          break
        }
        case 'TOOL_RESULT_END': {
          const line = findToolLine(String(ev.toolCallName ?? ''))
          if (line) line.status = String(ev.state ?? '')
          break
        }
        case 'REQUIRE_USER_CONFIRM': {
          pendingConfirm.value = { replyId: String(ev.replyId ?? ''), calls: confirmCalls(ev.toolCalls) }
          break
        }
        case 'USER_CONFIRM_RESULT': {
          // 拒绝确认：挂起的工具行不再执行，标记为已取消
          const results = Array.isArray(ev.confirmResults) ? ev.confirmResults : []
          const declined = results.some((r) => r != null && typeof r === 'object' && 'confirmed' in r && r.confirmed === false)
          if (declined) {
            for (const c of pendingConfirm.value?.calls ?? []) {
              const line = findToolLine(c.name)
              if (line && line.status === '执行中') line.status = '已取消'
            }
          }
          pendingConfirm.value = null
          break
        }
        case 'AGENT_RESULT': {
          if (!assistant.text && ev.summary) assistant.text = String(ev.summary)
          assistant.streaming = false
          break
        }
        case 'draft_ready':
          if (ev.draft) {
            latestDraft.value = ev.draft as unknown as AiGenerateResponse
            latestDraftMsgId.value = assistant.id
          }
          break
      }
    })
  } catch (e) {
    assistant.streaming = false
    if (!assistant.text) assistant.text = (e as Error).message || '对话失败'
    ElMessage.error((e as Error).message || '对话失败')
  } finally {
    assistant.streaming = false
    chatSending.value = false
  }
}

/** 应用 AI 草稿到画布（仍为未保存状态，走人工校对 → 保存/校验/发布）。 */
function applyAiDraft() {
  const draft = latestDraft.value?.workflowDraft
  if (!draft) {
    ElMessage.warning('尚无可用草稿，请先在对话中完成生成')
    return
  }
  name.value = draft.name ?? ''
  description.value = draft.description ?? ''
  status.value = ''
  version.value = 0
  draft.nodes.forEach((spec) => {
    const m = /^(TRIGGER|CONDITION|AGENT_SPLIT|DELAY|ACTION|UPDATE|END)_(\d+)$/.exec(spec.key)
    if (m) {
      const t = m[1] as WorkflowNodeType
      const n = Number(m[2])
      if (n > NODE_KEY_COUNTERS[t]) NODE_KEY_COUNTERS[t] = n
    }
  })
  setNodes(
    draft.nodes.map((spec): LiteNode => ({
      id: spec.key,
      type: 'canvas',
      position: spec.position && !Number.isNaN(spec.position.x) ? spec.position : { x: 120, y: 80 },
      data: { real: { ...spec, position: null } },
    })),
  )
  setEdges(
    (draft.edges ?? []).map((spec): LiteEdge => ({
      id: `${spec.source}->${spec.target}`,
      source: spec.source,
      target: spec.target,
      type: 'smoothstep',
      markerEnd: { type: MarkerType.ArrowClosed },
      data: { condition: spec.condition ?? null },
    })),
  )
  aiDialog.value = false
  // 补拉人群列表：空画布 load() 不加载，AI 草稿需人工补 audienceId 才有下拉可选
  loadAudiences()
  // position 全为兜底默认 → 触发自动布局；渲染后 fitView
  setTimeout(() => {
    autoLayout()
    fitView({ padding: 0.15 })
  }, 0)
  ElMessage.success('AI 草稿已载入画布，请人工校对节点配置后保存')
}

function toolLabel(name: string): string {
  const M: Record<string, string> = {
    list_channels: '查询通道配置',
    search_templates: '检索模板',
    search_audiences: '检索人群',
    build_dag: '编排 DAG',
    validate_dag: '校验 DAG',
    plan_workflow: '生成工作流草稿',
  }
  return M[name] ?? name
}

/* ---------- AI 对话自动滚动 ---------- */
watch(
  () => [chatMsgs.value.length, chatMsgs.value.map((m) => m.text).join(''), pendingConfirm.value],
  async () => {
    await nextTick()
    if (chatBodyRef.value) chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
  },
)

/* ---------- 自动布局 ---------- */

function autoLayout() {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', nodesep: 50, ranksep: 90 })
  const W = 160
  const H = 64
  allNodes().forEach((n) => g.setNode(n.id, { width: W, height: H }))
  allEdges().forEach((e) => g.setEdge(e.source, e.target))
  dagre.layout(g)
  setNodes(
    allNodes().map((n) => {
      const p = g.node(n.id)
      return { ...n, position: { x: p.x - W / 2, y: p.y - H / 2 } }
    }),
  )
  clearSelection()
}

onMounted(load)
</script>

<template>
  <div class="canvas-page">
    <!-- 顶部工具条 -->
    <div class="toolbar">
      <div class="wf-meta">
        <el-input v-model="name" placeholder="工作流名称（必填）" style="width: 220px" />
        <el-input v-model="description" placeholder="描述（可选）" style="width: 260px" />
        <el-tag v-if="status === 'published'" type="success">已发布 v{{ version }}</el-tag>
        <el-tag v-else-if="status === 'draft'" type="warning">草稿 v{{ version }}</el-tag>
        <el-tag v-else-if="status === 'archived'" type="info">已归档</el-tag>
        <el-tag v-else type="info">未保存</el-tag>
      </div>
      <div class="wf-actions">
        <el-button @click="router.push('/workflows')">返回列表</el-button>
        <el-button @click="autoLayout">自动布局</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        <el-button :loading="validating" @click="validate">校验</el-button>
        <el-button type="success" :loading="publishing" :disabled="status !== 'draft'" @click="publish">
          发布
        </el-button>
        <el-button type="warning" :disabled="status !== 'published'" @click="openDryRun">干跑</el-button>
        <el-button type="primary" plain @click="openAiDialog">AI 创建</el-button>
        <el-button type="primary" plain @click="openPlanImport">导入计划校验</el-button>
        <el-button @click="openPlanReport">校验报告</el-button>
      </div>
    </div>

    <div class="canvas-body">
      <!-- 左侧节点面板 -->
      <div class="palette">
        <div class="palette-title">节点</div>
        <div
          v-for="p in PALETTE"
          :key="p.type"
          class="palette-item"
          @click="addFromPalette(p.type)"
        >
          {{ p.label }}（{{ p.type }}）
        </div>
        <div class="palette-tip">点击添加节点；从节点右侧圆点拖出连线。</div>
      </div>

      <!-- 画布 -->
      <div class="flow-wrap">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :node-types="nodeTypes"
          :default-viewport="{ x: 40, y: 20, zoom: 0.9 }"
          @connect="onConnect"
          @node-click="(ev) => selectNode(ev.node.id)"
          @edge-click="(ev) => selectEdge(ev.edge.id)"
          @pane-click="clearSelection"
          :fit-view-on-init="true"
          :min-zoom="0.2"
          :max-zoom="2"
        >
          <template #node-canvas="nodeProps">
            <CanvasNode v-bind="nodeProps" />
          </template>
        </VueFlow>
      </div>

      <!-- 右侧配置面板 -->
      <div class="inspector">
        <template v-if="selectedKind === 'node' && selectedNode">
          <div class="inspector-title">
            {{ selectedNode ? nodeLabel(selectedNode) : '' }}
            <el-button size="small" type="danger" plain @click="deleteSelected">删除</el-button>
          </div>
          <el-form label-width="86px" size="small">
            <el-form-item label="节点类型">
              <el-tag>{{ selectedNode ? nodeTypeOf(selectedNode) : '' }}</el-tag>
            </el-form-item>
            <el-form-item label="节点名称">
              <el-input :model-value="selectedNode!.data.real.name" placeholder="留空显示类型" @update:model-value="updateNodeName" />
            </el-form-item>

            <!-- ACTION：channel + templateId + unitCost -->
            <template v-if="nodeTypeOf(selectedNode) === 'ACTION'">
              <el-form-item label="触达渠道">
                <el-select :model-value="selectedNode ? nodeConfig(selectedNode).channel : ''" placeholder="选择渠道" style="width: 100%" @update:model-value="(v: string) => updateNodeConfig('channel', v)">
                  <el-option v-for="c in CHANNEL_IDS" :key="c" :label="c" :value="c" />
                </el-select>
              </el-form-item>
              <el-form-item label="模板">
                <el-select
                  :model-value="selectedNode ? nodeConfig(selectedNode).templateId : ''"
                  placeholder="选择模板（按渠道过滤）"
                  style="width: 100%"
                  @update:model-value="(v: number) => updateNodeConfig('templateId', v)"
                >
                  <el-option
                    v-for="t in templates.filter((t) => !selectedNode || !nodeConfig(selectedNode).channel || t.channel === nodeConfig(selectedNode).channel)"
                    :key="t.id"
                    :label="`[${t.channel}] ${t.name}`"
                    :value="t.id"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="单价(元)">
                <el-input-number
                  :model-value="selectedNode ? Number(nodeConfig(selectedNode).unitCost ?? 0) : 0"
                  :min="0"
                  :precision="2"
                  :step="0.1"
                  controls-position="right"
                  @update:model-value="(v: number | undefined) => updateNodeConfig('unitCost', v)"
                />
              </el-form-item>
            </template>

            <!-- DELAY：durationMinutes -->
            <template v-else-if="selectedNode && nodeTypeOf(selectedNode) === 'DELAY'">
              <el-form-item label="时长(分)">
                <el-input-number
                  :model-value="selectedNode ? Number(nodeConfig(selectedNode).durationMinutes ?? 0) : 0"
                  :min="1"
                  controls-position="right"
                  @update:model-value="(v: number | undefined) => updateNodeConfig('durationMinutes', v)"
                />
              </el-form-item>
            </template>

            <!-- CONDITION：出边分支配置（条件 / else 兜底） -->
            <template v-else-if="selectedNode && nodeTypeOf(selectedNode) === 'CONDITION'">
              <el-form-item label="出边分支">
                <div class="cond-branches">
                  <div v-for="e in outEdgesOf(selectedNode!.data.real.key)" :key="e.id" class="cond-branch">
                    <div class="cond-branch-head">
                      <span class="cond-branch-target">→ {{ e.target }}</span>
                      <el-radio-group
                        :model-value="condOf(e) ? 'if' : 'else'"
                        size="small"
                        @update:model-value="(v: string) => setEdgeModeOf(e, v as 'else' | 'if')"
                      >
                        <el-radio value="if">条件分支</el-radio>
                        <el-radio value="else">else 兜底</el-radio>
                      </el-radio-group>
                    </div>
                    <EdgeConditionEditor
                      v-if="condOf(e)"
                      :model-value="condOf(e) as ConditionRule"
                      :root="true"
                      @update:model-value="(v: ConditionRule) => updateEdgeConditionOf(e, v)"
                    />
                  </div>
                  <div v-if="!outEdgesOf(selectedNode!.data.real.key).length" class="config-hint">
                    该节点暂无出边，先拖线连接。
                  </div>
                </div>
              </el-form-item>
            </template>
            <!-- TRIGGER：触发方式（定时 / 行为事件 / API / 手动） -->
            <template v-else-if="selectedNode && nodeTypeOf(selectedNode) === 'TRIGGER'">
              <el-form-item label="触发方式">
                <el-select
                  :model-value="selectedNode ? (nodeConfig(selectedNode).triggerType as string ?? 'MANUAL') : 'MANUAL'"
                  style="width: 100%"
                  @update:model-value="(v: string) => updateNodeConfig('triggerType', v)"
                >
                  <el-option value="MANUAL" label="手动触达" />
                  <el-option value="SCHEDULED" label="定时触达（cron 圈选）" />
                  <el-option value="EVENT" label="行为事件触发" />
                  <el-option value="API" label="API 触发（外部系统入流）" />
                </el-select>
              </el-form-item>

              <template v-if="nodeConfig(selectedNode).triggerType === 'SCHEDULED'">
                <el-form-item label="人群">
                  <el-select
                    :model-value="selectedNode ? nodeConfig(selectedNode).audienceId : ''"
                    placeholder="选择圈选人群"
                    style="width: 100%"
                    @update:model-value="(v: number) => updateNodeConfig('audienceId', v)"
                  >
                    <el-option v-for="a in audiences" :key="a.id" :label="a.name" :value="a.id" />
                  </el-select>
                </el-form-item>
                <el-form-item label="cron">
                  <el-input
                    :model-value="selectedNode ? (nodeConfig(selectedNode).cron as string ?? '') : ''"
                    placeholder="Quartz 表达式，如 0 30 9 * * ?（每天 09:30）"
                    @update:model-value="(v: string) => updateNodeConfig('cron', v)"
                  />
                </el-form-item>
                <el-form-item label="时区">
                  <el-input
                    :model-value="selectedNode ? (nodeConfig(selectedNode).timezone as string ?? 'Asia/Shanghai') : 'Asia/Shanghai'"
                    placeholder="Asia/Shanghai"
                    @update:model-value="(v: string) => updateNodeConfig('timezone', v)"
                  />
                </el-form-item>
              </template>

              <template v-else-if="nodeConfig(selectedNode).triggerType === 'EVENT'">
                <el-form-item label="事件名">
                  <el-input
                    :model-value="selectedNode ? (nodeConfig(selectedNode).eventName as string ?? '') : ''"
                    placeholder="如 order_paid / visit"
                    @update:model-value="(v: string) => updateNodeConfig('eventName', v)"
                  />
                </el-form-item>
                <el-form-item label="事件过滤">
                  <el-input
                    type="textarea"
                    :rows="4"
                    :model-value="eventFilterText"
                    placeholder='DSL JSON，如 {"op":"AND","items":[{"field":"event.amount","op":"gte","value":100}]}'
                    @update:model-value="setEventFilterText"
                  />
                </el-form-item>
              </template>

              <template v-else>
                <div class="config-hint">手动触达：从首页人群列表发起；API 触发由外部系统按进化流 ID 携带单用户载荷调用接口。</div>
              </template>
            </template>

            <div v-else class="config-hint">该节点无需配置。</div>
          </el-form>
        </template>

        <template v-else-if="selectedKind === 'edge' && selectedEdge">
          <div class="inspector-title">
            连线 {{ selectedEdge ? selectedEdge.source : '' }} → {{ selectedEdge ? selectedEdge.target : '' }}
            <el-button size="small" type="danger" plain @click="deleteSelected">删除</el-button>
          </div>
          <el-form label-width="86px" size="small">
            <el-form-item label="分支类型">
              <el-radio-group :model-value="edgeMode" @update:model-value="(v: string) => setEdgeMode(v as 'else' | 'if')">
                <el-radio value="if">条件分支</el-radio>
                <el-radio value="else">否则(else)</el-radio>
              </el-radio-group>
            </el-form-item>
            <template v-if="edgeMode === 'if' && selectedEdge!.data.condition">
              <el-form-item label="条件 DSL">
                <div class="cond-editor-wrap">
                  <EdgeConditionEditor
                    :model-value="selectedEdge!.data.condition"
                    :root="true"
                    @update:model-value="updateEdgeCondition"
                  />
                </div>
              </el-form-item>
            </template>
            <div v-else class="config-hint">无条件边：流量兜底走此分支。</div>
          </el-form>
        </template>

        <div v-else class="inspector-empty">
          点击节点或连线进行配置。<br />
          提示：TRIGGER 只能有一个；需有 END；CONDITION 出边需带条件。
        </div>
      </div>
    </div>

    <!-- AI 创建（流式对话 + HITL 确认） -->
    <el-dialog v-model="aiDialog" title="AI 创建（对话式，生成前人工确认）" width="720px" class="ai-chat-dialog">
      <div class="ai-chat-body" ref="chatBodyRef">
        <div v-for="m in chatMsgs" :key="m.id" class="ai-msg" :class="m.role">
          <div class="ai-bubble">
            <!-- 工具调用行（实时流转步骤） -->
            <div v-if="m.tools.length" class="ai-tool-list">
              <div v-for="(t, idx) in m.tools" :key="idx" class="ai-tool-card">
                <div class="ai-tool-head">
                  <span class="ai-tool-name">{{ toolLabel(t.name) }}</span>
                  <el-tag
                    :type="t.status === 'SUCCESS' ? 'success' : t.status === 'FAILED' ? 'danger' : 'info'"
                    size="small"
                  >
                    {{ t.status }}
                  </el-tag>
                </div>
                <pre v-if="t.delta" class="ai-tool-result">{{ t.delta }}</pre>
              </div>
            </div>
            <!-- 文本（打字机增量） -->
            <template v-if="m.text || m.streaming">
              <span class="ai-text">{{ m.text }}</span><span v-if="m.streaming" class="ai-caret">▌</span>
            </template>
            <!-- 草稿卡 -->
            <div v-if="m.id === latestDraftMsgId" class="ai-draft-card">
              <div class="ai-draft-title">
                草稿已生成：{{ latestDraft?.workflowDraft.name || '未命名工作流' }}（{{
                  latestDraft?.workflowDraft.nodes.length ?? 0
                }} 节点）
              </div>
              <div class="ai-draft-summary">{{ latestDraft?.planSummary }}</div>
              <el-alert
                v-if="latestDraft?.audienceHint && !latestDraft.audienceHint.matched"
                type="warning"
                :closable="false"
                class="ai-audience-alert"
              >
                <template #default>
                  <div>
                    未匹配到现有人群（建议名：{{ latestDraft.audienceHint.suggestedName ?? '-' }}），AI 不自动创建人群，请先在人群管理圈选。
                  </div>
                  <div class="ai-alert-actions">
                    <el-button size="small" type="warning" plain @click="router.push('/audiences')">去人群管理圈选</el-button>
                  </div>
                </template>
              </el-alert>
              <el-button size="small" type="primary" @click="applyAiDraft">载入画布（人工校对）</el-button>
            </div>
          </div>
        </div>
        <!-- HITL 确认卡片：出现时输入框禁用，只能确认/取消 -->
        <div v-if="pendingConfirm" class="ai-confirm-card">
          <div class="ai-confirm-text">AI 已将你的描述整理为工作流草稿，确认后生成？</div>
          <div class="ai-confirm-actions">
            <el-button size="small" type="primary" :disabled="chatSending" @click="sendConfirm(true)">确认生成</el-button>
            <el-button size="small" :disabled="chatSending" @click="sendConfirm(false)">取消</el-button>
          </div>
        </div>
      </div>
      <div class="ai-chat-input">
        <el-input
          v-model="chatInput"
          :disabled="!!pendingConfirm || chatSending"
          placeholder="补充需求 / 调整触发时间与人群（回车发送）"
          @keyup.enter="sendChat()"
        />
        <el-button
          type="primary"
          :disabled="!!pendingConfirm || chatSending || !chatInput.trim()"
          :loading="chatSending"
          @click="sendChat"
        >
          发送
        </el-button>
      </div>
    </el-dialog>

    <!-- 干跑快照选择 -->
    <el-dialog v-model="dryRunDialog" title="选择人群快照（干跑）" width="420px">
      <el-form label-width="90px">
        <el-form-item label="人群">
          <el-select v-model="dryRunAudienceId" placeholder="选择人群" style="width: 100%" @update:model-value="(v: number) => selectAudience(v)">
            <el-option v-for="a in audiences" :key="a.id" :label="a.name" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="快照">
          <el-select v-model="dryRunSnapshotId" placeholder="选择 ready 快照" style="width: 100%">
            <el-option
              v-for="s in snapshots"
              :key="s.id"
              :label="`快照 #${s.id}（${s.memberCount} 人 · ${s.executedAt}）`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dryRunDialog = false">取消</el-button>
        <el-button type="primary" @click="runDryRun">开始干跑</el-button>
      </template>
    </el-dialog>

    <!-- 干跑报告抽屉 -->
    <el-drawer v-model="reportVisible" title="干跑报告" size="640px">
      <template v-if="report">
        <div class="report-summary">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="执行 ID">#{{ report.executionId }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="report.status === 'done' ? 'success' : 'danger'">{{ report.status }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="成员总数">{{ report.totalMembers }}</el-descriptions-item>
            <el-descriptions-item label="耗时">{{ report.durationMs }} ms</el-descriptions-item>
          </el-descriptions>
          <el-alert v-if="report.error" :title="report.error" type="error" :closable="false" class="report-error" />
        </div>
        <el-table :data="report.nodes" size="small" border>
          <el-table-column prop="key" label="节点" width="120" />
          <el-table-column prop="nodeType" label="类型" width="110" />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'done' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="contacts" label="流入人数" width="90" />
          <el-table-column label="输出" min-width="200">
            <template #default="{ row }">
              <pre class="node-output">{{ JSON.stringify(row.output, null, 1) }}</pre>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>

    <!-- 计划导入校验弹窗 -->
    <el-dialog v-model="planImportDialog" title="导入计划校验" width="480px">
      <el-form label-width="90px">
        <el-form-item label="计划文件">
          <el-upload
            :auto-upload="false"
            :limit="1"
            accept=".xlsx,.csv"
            :on-change="(f: any) => onPlanFileChange(f.raw)"
            :on-remove="() => (planImportFile = null)"
          >
            <el-button>选择文件（.xlsx / .csv）</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label="空白模板">
          <div class="plan-template-links">
            <el-link type="primary" @click="downloadPlanTemplate('xlsx')">下载 xlsx 模板</el-link>
            <el-link type="primary" @click="downloadPlanTemplate('csv')">下载 csv 模板</el-link>
          </div>
          <div class="plan-template-hint">模板为空白填写模板（无示例行），列结构见 docs/07-plan-template.md</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planImportDialog = false">取消</el-button>
        <el-button type="primary" :loading="planImporting" @click="runPlanImport">开始校验</el-button>
      </template>
    </el-dialog>

    <!-- 计划校验报告抽屉 -->
    <el-drawer v-model="planReportVisible" title="计划校验报告" size="640px">
      <template v-if="planReport">
        <div class="report-summary">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="计划名称">{{ planReport.planName ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="决策">
              <el-tag :type="planReport.decision === 'PASSED' ? 'success' : planReport.decision === 'WARNINGS' ? 'warning' : 'danger'">
                {{ planReport.decision }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="文件">{{ planReport.fileName ?? '-' }}（{{ planReport.fileType ?? '-' }}）</el-descriptions-item>
            <el-descriptions-item label="校验时间">{{ planReport.createdAt ?? '-' }}</el-descriptions-item>
          </el-descriptions>
          <el-alert
            v-if="planReport.decision === 'BLOCKED'"
            title="存在 BLOCKED 冲突维度，发布将被拦截，请修正计划后重新导入。"
            type="error"
            :closable="false"
            class="report-error"
          />
          <el-alert
            v-else-if="planReport.decision === 'WARNINGS'"
            title="存在 WARNINGS 告警维度，建议修正后重新导入。"
            type="warning"
            :closable="false"
            class="report-error"
          />
          <el-alert
            v-if="planReport.summary.conflicts > 0 || planReport.summary.warnings > 0"
            :title="`冲突 ${planReport.summary.conflicts} · 告警 ${planReport.summary.warnings} · 一致 ${planReport.summary.passed}`"
            type="info"
            :closable="false"
            class="report-error"
          />
        </div>
        <el-table :data="planReport.dimensions" size="small" border>
          <el-table-column prop="name" label="维度" width="100" />
          <el-table-column prop="level" label="结论" width="90">
            <template #default="{ row }">
              <el-tag :type="row.level === 'PASSED' ? 'success' : row.level === 'WARNINGS' ? 'warning' : 'danger'" size="small">
                {{ row.level }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="plan" label="计划" min-width="110" show-overflow-tooltip />
          <el-table-column prop="workflow" label="工作流" min-width="110" show-overflow-tooltip />
          <el-table-column prop="detail" label="说明" min-width="160" show-overflow-tooltip />
          <el-table-column prop="suggestion" label="建议" min-width="160" show-overflow-tooltip />
        </el-table>
        <div class="plan-summary-text" v-if="planReport.planSummary">计划摘要：{{ planReport.planSummary }}</div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.canvas-page {
  height: calc(100vh - 60px);
  display: flex;
  flex-direction: column;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
  flex-wrap: wrap;
}
.wf-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.wf-actions {
  display: flex;
  gap: 8px;
}
.canvas-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.palette {
  width: 160px;
  border-right: 1px solid #e4e7ed;
  background: #fafafa;
  padding: 12px 10px;
  overflow-y: auto;
}
.palette-title {
  font-weight: 600;
  margin-bottom: 10px;
  font-size: 13px;
}
.palette-item {
  padding: 8px 10px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  cursor: grab;
  font-size: 13px;
  text-align: center;
  transition: border-color 0.2s;
}
.palette-item:hover {
  border-color: #409eff;
  color: #409eff;
}
.plan-template-links {
  display: flex;
  gap: 16px;
}
.plan-template-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
.plan-summary-text {
  margin-top: 12px;
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
}
.palette-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.flow-wrap {
  flex: 1;
  min-width: 300px;
  background: #f8fafc;
}
.inspector {
  width: 320px;
  border-left: 1px solid #e4e7ed;
  background: #fff;
  padding: 14px;
  overflow-y: auto;
}
.inspector-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  margin-bottom: 12px;
  font-size: 14px;
}
.inspector-empty {
  color: #909399;
  font-size: 13px;
  line-height: 1.8;
  padding-top: 20px;
  text-align: center;
}
.config-hint {
  color: #909399;
  font-size: 12px;
  line-height: 1.7;
  padding: 4px 0;
}
.cond-editor-wrap {
  width: 100%;
}
.cond-branches {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.cond-branch {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cond-branch-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.cond-branch-target {
  font-size: 13px;
  color: #606266;
  font-weight: 500;
  word-break: break-all;
}
.ai-audience-alert {
  margin-top: 10px;
}
.ai-alert-note {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
.ai-alert-actions {
  margin-top: 6px;
}
.ai-chat-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-height: 440px;
  overflow-y: auto;
  padding: 2px;
}
.ai-msg {
  display: flex;
}
.ai-msg.user {
  justify-content: flex-end;
}
.ai-msg.assistant {
  justify-content: flex-start;
}
.ai-bubble {
  max-width: 86%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  line-height: 1.7;
  font-size: 13px;
  word-break: break-word;
  white-space: pre-wrap;
}
.ai-msg.user .ai-bubble {
  background: #ecf5ff;
  color: #303133;
}
.ai-msg.assistant .ai-bubble {
  background: #f4f4f5;
  color: #303133;
}
.ai-text {
  white-space: pre-wrap;
}
.ai-caret {
  color: #409eff;
  animation: ai-caret-blink 1s steps(1) infinite;
}
@keyframes ai-caret-blink {
  50% {
    opacity: 0;
  }
}
.ai-tool-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ai-tool-card {
  border: 1px solid #ebeef5;
  background: #fff;
  border-radius: 6px;
  padding: 6px 10px;
}
.ai-tool-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-tool-name {
  font-weight: 600;
  font-size: 12px;
}
.ai-tool-result {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: #909399;
  background: #fafafa;
  border-radius: 4px;
  padding: 4px 8px;
  max-height: 140px;
  overflow-y: auto;
}
.ai-draft-card {
  border: 1px solid #67c23a;
  background: #fff;
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}
.ai-draft-title {
  font-weight: 600;
  font-size: 13px;
  color: #303133;
}
.ai-draft-summary {
  font-size: 12px;
  color: #606266;
  background: #fafafa;
  border-radius: 4px;
  padding: 6px 8px;
  white-space: pre-wrap;
  word-break: break-all;
}
.ai-confirm-card {
  align-self: stretch;
  border: 1px dashed #409eff;
  background: #f0f7ff;
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}
.ai-confirm-text {
  font-size: 13px;
  color: #303133;
}
.ai-confirm-actions {
  display: flex;
  gap: 8px;
}
.ai-chat-input {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
.ai-chat-input .el-input {
  flex: 1;
}
.report-summary {
  margin-bottom: 14px;
}
.report-error {
  margin-top: 10px;
}
.node-output {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
}
</style>