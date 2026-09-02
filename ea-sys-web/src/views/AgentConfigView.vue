<script setup lang="ts">
/**
 * 智能体配置（M4）：分层策略 CRUD/发布闸门 + 路由预览 + 流失扫描。
 * 数据：GET/POST /api/agent/strategies、POST /api/agent/route-preview、POST /api/agent/churn/scan。
 */
import { ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteStrategy,
  generateStrategy,
  getActiveStrategy,
  listAgentStrategies,
  publishStrategy,
  routePreview,
  scanChurn,
  updateStrategy,
} from '../api/agent'
import type {
  ChurnScanRequest,
  ChurnScanView,
  RoutePreviewRequest,
  RoutePreviewView,
  StrategyLayerEdit,
  StrategyUpdateRequest,
  StrategyView,
} from '../api/types'

const {
  data: strategies,
  isLoading,
  isError,
  refetch,
} = useQuery<StrategyView[]>({ queryKey: ['agent-strategies'], queryFn: listAgentStrategies })
const {
  data: active,
  refetch: refetchActive,
} = useQuery<StrategyView | null>({ queryKey: ['agent-active'], queryFn: getActiveStrategy })

async function refresh() {
  try {
    await Promise.all([refetch(), refetchActive()])
  } catch {
    ElMessage.error('刷新失败')
  }
}

function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function statusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  if (status === 'published') return 'success'
  if (status === 'draft') return 'info'
  if (status === 'archived') return 'danger'
  return 'info'
}

const routeChoices = ['sms', 'email']

/* ---------- 生成策略 ---------- */
const genVisible = ref(false)
const genLoading = ref(false)
const genName = ref('')
const genRouteOrder = ref<string[]>(['sms', 'email'])

async function submitGenerate() {
  if (!genName.value.trim()) {
    ElMessage.warning('请填写策略名称')
    return
  }
  genLoading.value = true
  try {
    await generateStrategy({ name: genName.value.trim(), routeOrder: genRouteOrder.value })
    ElMessage.success('策略已生成（draft，需发布后生效）')
    genVisible.value = false
    genName.value = ''
    await refetch()
  } catch {
    ElMessage.error('策略生成失败')
  } finally {
    genLoading.value = false
  }
}

/* ---------- 发布 / 删除 ---------- */
async function publish(row: StrategyView) {
  await ElMessageBox.confirm(`确认发布策略「${row.name}」？发布后即为当前生效策略。`, '发布闸门', {
    type: 'warning',
    confirmButtonText: '发布',
  })
  try {
    await publishStrategy(row.id)
    ElMessage.success('已发布')
    await Promise.all([refetch(), refetchActive()])
  } catch {
    ElMessage.error('发布失败')
  }
}

async function remove(row: StrategyView) {
  await ElMessageBox.confirm(`确认删除策略「${row.name}」？`, '删除', { type: 'warning' })
  try {
    await deleteStrategy(row.id)
    ElMessage.success('已删除')
    await refetch()
  } catch {
    ElMessage.error('删除失败')
  }
}

/* ---------- 路由预览 ---------- */
const previewVisible = ref(false)
const previewLoading = ref(false)
const previewReq = ref<RoutePreviewRequest>({ contactId: 1, routeOrder: ['sms', 'email'] })
const previewResult = ref<RoutePreviewView | null>(null)

async function submitPreview() {
  if (!previewReq.value.contactId) {
    ElMessage.warning('请填写联系人 ID')
    return
  }
  previewLoading.value = true
  try {
    previewResult.value = await routePreview(previewReq.value)
  } catch {
    ElMessage.error('路由预览失败')
  } finally {
    previewLoading.value = false
  }
}

/* ---------- 流失扫描 ---------- */
const churnVisible = ref(false)
const churnLoading = ref(false)
const churnReq = ref<ChurnScanRequest>({ audienceSnapshotId: 1, inactiveDays: 30 })
const churnResult = ref<ChurnScanView | null>(null)

async function submitChurn() {
  if (!churnReq.value.audienceSnapshotId) {
    ElMessage.warning('请填写人群快照 ID')
    return
  }
  churnLoading.value = true
  try {
    churnResult.value = await scanChurn(churnReq.value)
  } catch {
    ElMessage.error('流失扫描失败')
  } finally {
    churnLoading.value = false
  }
}

/* ---------- 策略详情 ---------- */
const detailVisible = ref(false)
const detailStrategy = ref<StrategyView | null>(null)

function showDetail(row: StrategyView) {
  detailStrategy.value = row
  detailVisible.value = true
}

const prettyJson = (v: unknown): string => JSON.stringify(v, null, 2) ?? '-'

/* ---------- 编辑分层策略（仅 draft） ---------- */
const AVAILABILITY_OPTIONS = [
  { value: 'sms_only', label: '仅短信' },
  { value: 'email_only', label: '仅邮件' },
  { value: 'multi', label: '双通道' },
  { value: 'none', label: '无通道' },
] as const

/** 从策略文档提取可编辑层列表（策略文档 layers[] → 编辑表单）。 */
function layersFromStrategy(strategy: unknown): StrategyLayerEdit[] {
  const doc = strategy as { layers?: unknown[] } | null | undefined
  const raw = doc?.layers
  if (!Array.isArray(raw)) return []
  return raw.map((l) => {
    const layer = (l ?? {}) as Record<string, unknown>
    const rule = (layer.rule ?? {}) as { channel_availability?: unknown }
    return {
      id: String(layer.id ?? ''),
      name: String(layer.name ?? ''),
      channelAvailability: String(rule.channel_availability ?? 'sms_only'),
      routeOrder: Array.isArray(layer.route_order) ? layer.route_order.map(String) : [],
      priority: typeof layer.priority === 'number' ? layer.priority : 1,
    }
  })
}

const editVisible = ref(false)
const editLoading = ref(false)
const editId = ref<number | null>(null)
const editName = ref('')
const editLayers = ref<StrategyLayerEdit[]>([])

function openEdit(row: StrategyView) {
  editId.value = row.id
  editName.value = row.name
  editLayers.value = layersFromStrategy(row.strategy)
  editVisible.value = true
}

function nextLayerId(): string {
  const used = new Set(editLayers.value.map((l) => l.id))
  let n = editLayers.value.length + 1
  while (used.has(`L${n}`)) n += 1
  return `L${n}`
}

function addLayer() {
  editLayers.value.push({
    id: nextLayerId(),
    name: '',
    channelAvailability: 'sms_only',
    routeOrder: ['sms'],
    priority: editLayers.value.length + 1,
  })
}

function removeLayer(i: number) {
  editLayers.value.splice(i, 1)
}

function setLayerAvailability(i: number, v: string) {
  editLayers.value[i].channelAvailability = v
}

function setLayerRouteOrder(i: number, v: string[]) {
  editLayers.value[i].routeOrder = v
}

function setLayerPriority(i: number, v: number | undefined) {
  editLayers.value[i].priority = v ?? 1
}

async function submitEdit() {
  if (!editId.value) return
  if (!editName.value.trim()) {
    ElMessage.warning('请填写策略名称')
    return
  }
  const ids = editLayers.value.map((l) => l.id)
  if (new Set(ids).size !== ids.length) {
    ElMessage.warning('层 ID 不能重复')
    return
  }
  if (!editLayers.value.every((l) => AVAILABILITY_OPTIONS.some((o) => o.value === l.channelAvailability))) {
    ElMessage.warning('存在非法的通道可用性取值')
    return
  }
  if (!editLayers.value.every((l) => l.priority >= 1)) {
    ElMessage.warning('层优先级须 ≥ 1')
    return
  }
  const req: StrategyUpdateRequest = {
    name: editName.value.trim(),
    layers: editLayers.value.map((l) => ({
      id: l.id,
      name: l.name,
      channelAvailability: l.channelAvailability,
      routeOrder: l.routeOrder,
      priority: l.priority,
    })),
  }
  editLoading.value = true
  try {
    await updateStrategy(editId.value, req)
    ElMessage.success('策略已更新（draft，需发布后生效）')
    editVisible.value = false
    await refetch()
  } catch {
    ElMessage.error('策略更新失败')
  } finally {
    editLoading.value = false
  }
}
</script>

<template>
  <div class="agent-page">
    <div class="page-head">
      <h3>智能体配置</h3>
      <div class="controls">
        <el-button :loading="isLoading" @click="refresh">刷新</el-button>
        <el-button type="primary" @click="genVisible = true">生成策略</el-button>
        <el-button @click="previewVisible = true; previewResult = null">路由预览</el-button>
        <el-button @click="churnVisible = true; churnResult = null">流失扫描</el-button>
      </div>
    </div>

    <div class="grid">
      <!-- 生效策略 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">当前生效策略</div>
        </template>
        <div v-if="active" class="active-info">
          <div class="active-line">
            <el-tag type="success" size="small">PUBLISHED</el-tag>
            <span class="active-name">{{ active.name }}</span>
          </div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="版本">{{ active.strategyVersion || '-' }}</el-descriptions-item>
            <el-descriptions-item label="置信度">{{ (active.confidence ?? 0).toFixed(3) }}</el-descriptions-item>
            <el-descriptions-item label="来源">{{ active.source }}</el-descriptions-item>
            <el-descriptions-item label="发布时间">{{ fmtTime(active.publishedAt) }}</el-descriptions-item>
          </el-descriptions>
        </div>
        <el-empty v-else description="暂无生效策略（生成后需发布）" :image-size="70" />
      </el-card>

      <!-- 策略列表 -->
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-title">分层策略</div>
        </template>
        <el-alert v-if="isError" title="策略列表加载失败" type="error" :closable="false" class="panel-error" />
        <el-table v-loading="isLoading" :data="strategies ?? []" size="small" border stripe highlight-current-row>
          <el-table-column prop="id" label="ID" width="56" />
          <el-table-column prop="name" label="名称" min-width="130" show-overflow-tooltip />
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="source" label="来源" width="100" />
          <el-table-column label="置信度" width="84">
            <template #default="{ row }">{{ (row.confidence ?? 0).toFixed(3) }}</template>
          </el-table-column>
          <el-table-column prop="strategyVersion" label="版本" width="90" />
          <el-table-column prop="createdBy" label="创建人" width="84" />
          <el-table-column label="创建时间" width="132">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" link @click="showDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'draft'" size="small" type="warning" link @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status === 'draft'" size="small" type="success" link @click="publish(row)">发布</el-button>
              <el-button size="small" type="danger" link @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无策略，点击「生成策略」创建" :image-size="80" />
          </template>
        </el-table>
      </el-card>
    </div>

    <!-- 生成策略 -->
    <el-dialog v-model="genVisible" title="生成分层策略" width="480px">
      <el-form label-width="90px">
        <el-form-item label="策略名称">
          <el-input v-model="genName" placeholder="如：通道优先分层" maxlength="128" />
        </el-form-item>
        <el-form-item label="路由顺序">
          <el-checkbox-group v-model="genRouteOrder">
            <el-checkbox v-for="c in routeChoices" :key="c" :value="c">{{ c }}</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">确定性规划器将按通道可用性生成分层；顺序即双通道时的触达优先级。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="genVisible = false">取消</el-button>
        <el-button type="primary" :loading="genLoading" @click="submitGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 路由预览 -->
    <el-dialog v-model="previewVisible" title="路由预览（近 24h 触达史重排）" width="480px">
      <el-form label-width="90px">
        <el-form-item label="联系人 ID">
          <el-input-number v-model="previewReq.contactId" :min="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="意图顺序">
          <el-checkbox-group v-model="previewReq.routeOrder">
            <el-checkbox v-for="c in routeChoices" :key="c" :value="c">{{ c }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <div v-if="previewResult" v-loading="previewLoading" class="preview-result">
        <el-tag :type="previewResult.unchanged ? 'info' : 'warning'" size="small">
          {{ previewResult.unchanged ? '通道顺序未变' : '按触达史重排' }}
        </el-tag>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="近 24h 触达">
            {{ previewResult.touched.length ? previewResult.touched.join(' → ') : '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="重排后顺序">
            {{ previewResult.reordered.join(' → ') }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" :loading="previewLoading" @click="submitPreview">预览</el-button>
      </template>
    </el-dialog>

    <!-- 流失扫描 -->
    <el-dialog v-model="churnVisible" title="流失风险扫描" width="480px">
      <el-form label-width="110px">
        <el-form-item label="人群快照 ID">
          <el-input-number v-model="churnReq.audienceSnapshotId" :min="1" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="未活跃阈值(天)">
          <el-input-number v-model="churnReq.inactiveDays" :min="1" :max="365" controls-position="right" style="width: 100%" />
        </el-form-item>
      </el-form>
      <div v-if="churnResult" v-loading="churnLoading" class="churn-result">
        <div class="churn-stats">
          <div class="churn-stat">
            <span class="stat-num">{{ churnResult.scanned }}</span>
            <span class="stat-label">扫描成员</span>
          </div>
          <div class="churn-stat churn-high">
            <span class="stat-num">{{ churnResult.high }}</span>
            <span class="stat-label">高流失</span>
          </div>
          <div class="churn-stat churn-mid">
            <span class="stat-num">{{ churnResult.medium }}</span>
            <span class="stat-label">中流失</span>
          </div>
          <div class="churn-stat">
            <span class="stat-num">{{ churnResult.low }}</span>
            <span class="stat-label">低流失</span>
          </div>
        </div>
        <div class="churn-note">已回写 {{ churnResult.updatedAttributes }} 条 contact_attribute.churn_risk</div>
      </div>
      <template #footer>
        <el-button @click="churnVisible = false">关闭</el-button>
        <el-button type="primary" :loading="churnLoading" @click="submitChurn">扫描</el-button>
      </template>
    </el-dialog>

    <!-- 策略详情 -->
    <el-drawer v-model="detailVisible" :title="`策略详情 #${detailStrategy?.id ?? ''}`" size="560px">
      <template v-if="detailStrategy">
        <div class="detail-actions">
          <el-tag :type="statusType(detailStrategy.status)" size="small">{{ detailStrategy.status }}</el-tag>
          <el-button v-if="detailStrategy.status === 'draft'" size="small" type="warning" plain @click="openEdit(detailStrategy)">编辑分层</el-button>
        </div>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="名称">{{ detailStrategy.name }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detailStrategy.status)" size="small">{{ detailStrategy.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ detailStrategy.source }}</el-descriptions-item>
          <el-descriptions-item label="置信度">{{ (detailStrategy.confidence ?? 0).toFixed(3) }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ detailStrategy.strategyVersion || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建人">{{ detailStrategy.createdBy }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmtTime(detailStrategy.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ fmtTime(detailStrategy.publishedAt) }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="detail-head">维度</h4>
        <pre class="json-block">{{ prettyJson(detailStrategy.dimensions) }}</pre>
        <h4 class="detail-head">路由顺序</h4>
        <pre class="json-block">{{ prettyJson(detailStrategy.routeOrder) }}</pre>
        <h4 class="detail-head">分层策略文档</h4>
        <pre class="json-block">{{ prettyJson(detailStrategy.strategy) }}</pre>
      </template>
    </el-drawer>

    <!-- 编辑分层策略（仅 draft） -->
    <el-dialog v-model="editVisible" title="编辑分层策略" width="680px">
      <el-form label-width="90px">
        <el-form-item label="策略名称">
          <el-input v-model="editName" placeholder="如：通道优先分层" maxlength="128" />
        </el-form-item>
        <el-form-item label="层规则">
          <div class="layer-editor">
            <div v-for="(layer, i) in editLayers" :key="layer.id" class="layer-row">
              <div class="layer-row-head">
                <span class="layer-id">{{ layer.id }}</span>
                <el-input v-model="layer.name" placeholder="层名称（如：仅短信）" size="small" style="flex: 1" />
                <el-button link type="danger" size="small" @click="removeLayer(i)">删除该层</el-button>
              </div>
              <div class="layer-row-body">
                <el-form-item label="通道可用性" label-width="88px" class="layer-field">
                  <el-select :model-value="layer.channelAvailability" size="small" style="width: 100%" @update:model-value="(v: string) => setLayerAvailability(i, v)">
                    <el-option v-for="o in AVAILABILITY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
                  </el-select>
                </el-form-item>
                <el-form-item label="路由顺序" label-width="88px" class="layer-field">
                  <el-checkbox-group :model-value="layer.routeOrder" size="small" @update:model-value="(v: Array<string | number | boolean>) => setLayerRouteOrder(i, v.map(String))">
                    <el-checkbox v-for="c in routeChoices" :key="c" :value="c">{{ c }}</el-checkbox>
                  </el-checkbox-group>
                </el-form-item>
                <el-form-item label="优先级" label-width="88px" class="layer-field">
                  <el-input-number :model-value="layer.priority" :min="1" size="small" controls-position="right" style="width: 110px" @update:model-value="(v: number | undefined) => setLayerPriority(i, v)" />
                </el-form-item>
              </div>
            </div>
            <el-button link type="primary" size="small" @click="addLayer">+ 添加层</el-button>
            <div class="form-tip">分层匹配：成员按通道可用性从上到下命中首个可用层；未命中或画布未配置该层时落入兜底出边。</div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editLoading" @click="submitEdit">保存草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.agent-page {
  padding: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.controls {
  display: flex;
  gap: 8px;
}
.grid {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: 16px;
}
@media (max-width: 1100px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
.panel :deep(.el-card__header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.panel-title {
  font-weight: 600;
  font-size: 15px;
}
.panel-error {
  margin-bottom: 8px;
}
.active-info {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.active-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.active-name {
  font-weight: 600;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 4px;
}
.preview-result,
.churn-result {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.churn-stats {
  display: flex;
  gap: 8px;
}
.churn-stat {
  flex: 1;
  padding: 10px 0;
  background: #f5f7fa;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}
.churn-high .stat-num {
  color: #f56c6c;
}
.churn-mid .stat-num {
  color: #e6a23c;
}
.stat-num {
  font-size: 22px;
  font-weight: 700;
  color: #409eff;
}
.stat-label {
  font-size: 12px;
  color: #909399;
}
.churn-note {
  font-size: 12px;
  color: #909399;
}
.detail-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.detail-head {
  margin: 14px 0 6px;
}
.layer-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.layer-row {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
}
.layer-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.layer-id {
  font-weight: 600;
  color: #409eff;
  background: #ecf5ff;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 12px;
}
.layer-row-body {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.layer-field {
  margin-bottom: 0;
}
.json-block {
  margin: 0;
  padding: 10px;
  background: #f8f9fb;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  max-height: 260px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>