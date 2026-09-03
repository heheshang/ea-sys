<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import RuleEditor from '../components/RuleEditor.vue'
import type { Audience, AudienceMember, AudienceRequest, AudienceRule, AudienceSnapshot, DryRunResponse, WorkflowSummary, WorkflowView } from '../api/types'
import { circleAudience, createAudience, deleteAudience, listAudiences, listMembers, listSnapshots, updateAudience } from '../api/audience'
import { executeWorkflow, getWorkflow, listWorkflows } from '../api/workflow'

/* ---------- 人群列表 ---------- */
const loading = ref(false)
const rows = ref<Audience[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

async function load() {
  loading.value = true
  try {
    const res = await listAudiences(page.value, size.value)
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}
onMounted(load)

/* ---------- 新建 / 编辑对话框 ---------- */
const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const name = ref('')
const rule = reactive<AudienceRule>({ op: 'AND', items: [{ field: 'contact.phone', op: 'equals', value: '' }] })

function defaultRule(): AudienceRule {
  return { op: 'AND', items: [{ field: 'contact.phone', op: 'equals', value: '' }] }
}
function openCreate() {
  editingId.value = null
  name.value = ''
  Object.assign(rule, defaultRule())
  dialogVisible.value = true
}
function openEdit(row: Audience) {
  editingId.value = row.id
  name.value = row.name
  try {
    Object.assign(rule, JSON.parse(row.rule))
  } catch {
    Object.assign(rule, defaultRule())
  }
  dialogVisible.value = true
}

function ruleSummary(r: AudienceRule): string {
  const cnt = r.items.length
  return `${r.op}(${cnt} ${r.items.length === 1 ? '项' : '项'})`
}

async function save() {
  if (!name.value.trim()) {
    ElMessage.warning('请输入人群名称')
    return
  }
  if (!rule.items.length) {
    ElMessage.warning('规则至少需要一个条件')
    return
  }
  const payload: AudienceRequest = { name: name.value.trim(), rule }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createAudience(payload)
      ElMessage.success('人群已创建（状态 published，可直接圈选）')
    } else {
      await updateAudience(editingId.value, payload)
      ElMessage.success('人群已更新')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: Audience) {
  await ElMessageBox.confirm(`确定删除人群「${row.name}」？历史快照将一并删除。`, '删除确认', { type: 'warning' })
  await deleteAudience(row.id)
  ElMessage.success('已删除')
  if (rows.value.length === 1 && page.value > 1) page.value -= 1
  await load()
}

/* ---------- 圈选快照 ---------- */
const circlingId = ref<number | null>(null)

/** 触发圈选（同步执行，可能耗时数秒）。 */
async function circle(row: Audience) {
  circlingId.value = row.id
  try {
    const snap = await circleAudience(row.id)
    ElMessage.success(`圈选完成：命中 ${snap.memberCount} 人（快照 #${snap.id}）`)
    await load() // latestSnapshot 摘要刷新
    openSnapshots(row, snap.id)
  } catch (e) {
    ElMessage.error('圈选失败，请检查规则与联系人数据')
    throw e
  } finally {
    circlingId.value = null
  }
}

/* ---------- 快照历史 ---------- */
const snapDialogVisible = ref(false)
const snapLoading = ref(false)
const snapAudience = ref<Audience | null>(null)
const snaps = ref<AudienceSnapshot[]>([])
const snapTotal = ref(0)
const snapPage = ref(1)
const snapSize = ref(20)

async function loadSnapshots() {
  if (!snapAudience.value) return
  snapLoading.value = true
  try {
    const res = await listSnapshots(snapAudience.value.id, snapPage.value, snapSize.value)
    snaps.value = res.records
    snapTotal.value = res.total
  } finally {
    snapLoading.value = false
  }
}
function openSnapshots(row: Audience, focusId?: number) {
  snapAudience.value = row
  snapPage.value = 1
  snapDialogVisible.value = true
  loadSnapshots().then(() => {
    // 聚焦刚生成的快照：滚动到对应行并自动打开成员
    if (focusId != null) {
      const target = snaps.value.find((s) => s.id === focusId)
      if (target) openMembers(target)
    }
  })
}

/* ---------- 成员预览 ---------- */
const memberDialogVisible = ref(false)
const memberLoading = ref(false)
const memberSnap = ref<AudienceSnapshot | null>(null)
const members = ref<AudienceMember[]>([])
const memberTotal = ref(0)
const memberPage = ref(1)
const memberSize = ref(20)

async function loadMembers() {
  if (!memberSnap.value) return
  memberLoading.value = true
  try {
    const res = await listMembers(memberSnap.value.id, memberPage.value, memberSize.value)
    members.value = res.records
    memberTotal.value = res.total
  } finally {
    memberLoading.value = false
  }
}
function openMembers(snap: AudienceSnapshot) {
  memberSnap.value = snap
  memberPage.value = 1
  memberDialogVisible.value = true
  loadMembers()
}

/* ---------- 发起触达（手动触发：人群 → 已发布工作流） ---------- */
const router = useRouter()
const triggerDialogVisible = ref(false)
const triggerAudience = ref<Audience | null>(null)
const wfLoading = ref(false)
const publishedWfs = ref<WorkflowSummary[]>([])
const selectedWfId = ref<number | null>(null)
const wfDetail = ref<WorkflowView | null>(null)
const executing = ref(false)
const execResult = ref<DryRunResponse | null>(null)

const snapReady = computed(() => triggerAudience.value?.latestSnapshot?.status === 'ready')
function wfHasAudienceNode(): boolean {
  return wfDetail.value?.nodes.some((n) => n.type === 'AUDIENCE') ?? false
}
/** 画布有 AUDIENCE 节点 → 成员由节点圈选（快照可缺省）；否则必须已有本人群快照。 */
const canTrigger = computed(
  () => selectedWfId.value != null && wfDetail.value != null && (wfHasAudienceNode() || snapReady.value) && !executing.value,
)
/** 真实触达人数：通道级下发记录去重联系人（nodes[].contacts 为节点处理数，非触达）。 */
const deliveredContacts = computed(
  () => new Set((execResult.value?.deliveries ?? []).map((d) => d.contactId)).size,
)

async function openTrigger(row: Audience) {
  triggerAudience.value = row
  selectedWfId.value = null
  wfDetail.value = null
  execResult.value = null
  triggerDialogVisible.value = true
  wfLoading.value = true
  try {
    publishedWfs.value = (await listWorkflows()).filter((w) => w.status === 'published')
  } finally {
    wfLoading.value = false
  }
}

async function onWfChange() {
  wfDetail.value = null
  execResult.value = null
  if (selectedWfId.value == null) return
  try {
    wfDetail.value = await getWorkflow(selectedWfId.value)
  } catch {
    wfDetail.value = null
    ElMessage.error('流程加载失败')
  }
}

async function doTrigger() {
  if (selectedWfId.value == null || !triggerAudience.value) return
  executing.value = true
  try {
    const req = wfHasAudienceNode()
      ? {}
      : { audienceSnapshotId: triggerAudience.value.latestSnapshot!.id }
    execResult.value = await executeWorkflow(selectedWfId.value, req)
    if (execResult.value.error) {
      ElMessage.error(`执行未完成：${execResult.value.error}`)
    } else {
      ElMessage.success(`触达已下发：${deliveredContacts.value} 人`)
    }
  } catch {
    execResult.value = null
    ElMessage.error('执行失败：请确认流程已发布、人群快照有效')
  } finally {
    executing.value = false
  }
}

const fmtTime = (t: string | null | undefined) => (t ? new Date(t).toLocaleString() : '—')
function statusType(s: string): 'success' | 'info' | 'warning' | 'danger' {
  if (s === 'published') return 'success'
  if (s === 'archived') return 'warning'
  return 'info'
}
function snapStatusType(s: string) {
  if (s === 'ready') return 'success'
  if (s === 'building') return 'warning'
  return 'danger'
}
</script>

<template>
  <div class="audience-page">
    <el-card shadow="never">
      <div class="toolbar">
        <span class="page-hint">规则圈选：基于联系人画像构建人群，圈选结果冻结为快照</span>
        <div class="spacer" />
        <el-button type="primary" @click="openCreate">新建人群</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="人群名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="规则" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="rule-code">{{ ruleSummary(row.rule ? JSON.parse(row.rule) : { op: 'AND', items: [] }) }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近快照" min-width="190">
          <template #default="{ row }">
            <template v-if="row.latestSnapshot">
              <span>{{ fmtTime(row.latestSnapshot.executedAt) }}</span>
              <el-tag :type="snapStatusType(row.latestSnapshot.status)" size="small" class="snap-count">
                {{ row.latestSnapshot.memberCount }} 人
              </el-tag>
            </template>
            <span v-else class="muted">未圈选</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="创建人" width="100" />
        <el-table-column prop="createdAt" label="创建时间" min-width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              :loading="circlingId === row.id"
              @click="circle(row)"
            >
              {{ circlingId === row.id ? '圈选中…' : '圈选快照' }}
            </el-button>
            <el-button link type="primary" @click="openSnapshots(row)">快照</el-button>
            <el-button link type="primary" @click="openTrigger(row)">发起触达</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pager"
        @current-change="load"
        @size-change="page = 1; load()"
      />
    </el-card>

    <!-- 新建/编辑 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新建人群' : '编辑人群'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="name" maxlength="128" placeholder="人群名称，如「近30天高价值未复购」" />
        </el-form-item>
        <el-form-item label="圈选规则">
          <RuleEditor v-model="rule" root class="rule-editor" />
        </el-form-item>
        <div class="rule-tip">
          字段支持 contact.* 直属列、attribute.* 画像属性（含 layer/churn_risk 分层标签）、tag.* 标签；空规则禁止。
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 快照历史 -->
    <el-dialog v-model="snapDialogVisible" :title="`圈选快照 · ${snapAudience?.name ?? ''}`" width="780px">
      <el-table v-loading="snapLoading" :data="snaps" stripe>
        <el-table-column prop="id" label="快照ID" width="90" />
        <el-table-column label="执行时间" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.executedAt) }}</template>
        </el-table-column>
        <el-table-column label="成员数" width="100">
          <template #default="{ row }">
            <el-tag :type="snapStatusType(row.status)" size="small">{{ row.memberCount }} 人</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column prop="filterVersion" label="规则版本" width="100" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openMembers(row)">成员预览</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="snapPage"
        v-model:page-size="snapSize"
        :total="snapTotal"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="loadSnapshots"
        @size-change="snapPage = 1; loadSnapshots()"
      />
    </el-dialog>

    <!-- 成员预览 -->
    <el-dialog
      v-model="memberDialogVisible"
      :title="`快照成员 · #${memberSnap?.id ?? ''}（${memberSnap?.memberCount ?? 0} 人）`"
      width="720px"
    >
      <el-table v-loading="memberLoading" :data="members" stripe>
        <el-table-column prop="contactId" label="联系人ID" width="100" />
        <el-table-column prop="externalId" label="externalId" min-width="130" show-overflow-tooltip />
        <el-table-column prop="phone" label="手机号" min-width="130" />
        <el-table-column prop="email" label="邮箱" min-width="170" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'active' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="memberPage"
        v-model:page-size="memberSize"
        :total="memberTotal"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="loadMembers"
        @size-change="memberPage = 1; loadMembers()"
      />
    </el-dialog>

    <!-- 发起触达 -->
    <el-dialog v-model="triggerDialogVisible" :title="`发起触达 · ${triggerAudience?.name ?? ''}`" width="600px">
      <el-form label-width="86px">
        <el-form-item label="触达人群">
          <div>
            <b>{{ triggerAudience?.name }}</b>
            <el-tag v-if="snapReady" type="success" size="small" class="snap-count">
              最新快照 {{ triggerAudience?.latestSnapshot?.memberCount }} 人
            </el-tag>
            <span v-else class="muted">（未圈选，需先「圈选快照」）</span>
          </div>
        </el-form-item>
        <el-form-item label="执行流程">
          <el-select
            v-model="selectedWfId"
            placeholder="选择已发布工作流"
            style="width: 100%"
            :loading="wfLoading"
            @change="onWfChange"
          >
            <el-option v-for="w in publishedWfs" :key="w.id" :label="`#${w.id} ${w.name}（v${w.version}）`" :value="w.id" />
          </el-select>
          <div v-if="!wfLoading && !publishedWfs.length" class="rule-tip">
            暂无已发布工作流，请先在「工作流」页创建并发布画布。
          </div>
        </el-form-item>
        <el-form-item v-if="wfDetail" label="成员来源">
          <el-alert
            v-if="wfHasAudienceNode()"
            type="info"
            :closable="false"
            show-icon
            title="画布含「人群」节点：执行时按节点圈选成员，此处人群不参与圈选。"
          />
          <el-alert
            v-else-if="snapReady"
            type="success"
            :closable="false"
            show-icon
            title="画布无「人群」节点：按当前人群最新快照执行。"
          />
          <el-alert
            v-else
            type="warning"
            :closable="false"
            show-icon
            title="该流程无「人群」节点，需先圈选快照后才能发起。"
          />
        </el-form-item>
        <el-form-item v-if="execResult" label="执行结果">
          <el-alert v-if="execResult.error" type="error" :closable="false" :title="execResult.error" />
          <div v-else>
            <el-tag type="success" size="small">已触达 {{ deliveredContacts }} 人</el-tag>
            <el-tag type="info" size="small" class="snap-count">通道记录 {{ execResult.deliveries.length }} 条</el-tag>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="triggerDialogVisible = false">关闭</el-button>
        <el-button @click="router.push('/monitoring')">触达监控</el-button>
        <el-button type="primary" :loading="executing" :disabled="!canTrigger" @click="doTrigger">
          {{ execResult ? '再次执行' : '确认执行' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}
.page-hint {
  color: #909399;
  font-size: 13px;
}
.spacer {
  flex: 1;
}
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
.rule-code {
  font-size: 12px;
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 3px;
}
.muted {
  color: #c0c4cc;
}
.snap-count {
  margin-left: 6px;
}
.rule-tip {
  color: #909399;
  font-size: 12px;
  margin-left: 80px;
  line-height: 1.6;
}
</style>