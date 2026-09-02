<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Contact, ContactRequest } from '../api/types'
import { batchCreateContacts, createContact, deleteContact, listContacts, updateContact } from '../api/contact'

const loading = ref(false)
const rows = ref<Contact[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const keyword = ref('')

async function load() {
  loading.value = true
  try {
    const res = await listContacts({ keyword: keyword.value || undefined, page: page.value, size: size.value })
    rows.value = res.records
    total.value = res.total
  } finally {
    loading.value = false
  }
}
onMounted(load)

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<ContactRequest>({
  externalId: '',
  phone: '',
  email: '',
  pushToken: '',
  status: 'active',
  attributes: {},
  tags: [],
})
const attributesText = ref('{}')

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    externalId: '',
    phone: '',
    email: '',
    pushToken: '',
    status: 'active',
    attributes: {},
    tags: [],
  })
  attributesText.value = '{}'
  dialogVisible.value = true
}

function openEdit(row: Contact) {
  editingId.value = row.id
  Object.assign(form, {
    externalId: row.externalId,
    phone: row.phone,
    email: row.email,
    pushToken: row.pushToken,
    status: row.status,
    attributes: row.attributes,
    tags: [...row.tags],
  })
  attributesText.value = JSON.stringify(row.attributes ?? {}, null, 2)
  dialogVisible.value = true
}

async function save() {
  // 属性 JSON 文本 → 对象（非法 JSON 直接报错，不静默丢弃）
  let attributes: Record<string, unknown>
  try {
    attributes = attributesText.value.trim() ? JSON.parse(attributesText.value) : {}
  } catch {
    ElMessage.error('属性必须是合法 JSON')
    return
  }
  const payload: ContactRequest = {
    externalId: form.externalId || undefined,
    phone: form.phone || undefined,
    email: form.email || undefined,
    pushToken: form.pushToken || undefined,
    status: form.status,
    attributes,
    tags: form.tags,
  }
  saving.value = true
  try {
    if (editingId.value == null) {
      await createContact(payload)
      ElMessage.success('联系人已创建')
    } else {
      await updateContact(editingId.value, payload)
      ElMessage.success('联系人已更新')
    }
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: Contact) {
  await ElMessageBox.confirm(`确定删除联系人 ${row.externalId || row.phone || row.id}？`, '删除确认', {
    type: 'warning',
  })
  await deleteContact(row.id)
  ElMessage.success('已删除')
  // 删空当前页末行时回退一页
  if (rows.value.length === 1 && page.value > 1) page.value -= 1
  await load()
}

function onSearch() {
  page.value = 1
  load()
}

function fmtAttrs(attrs: Record<string, unknown>) {
  const entries = Object.entries(attrs ?? {})
  if (!entries.length) return '—'
  return entries.map(([k, v]) => `${k}=${JSON.stringify(v)}`).join(' ')
}

/* ---------- 批量添加 ---------- */
const batchDialogVisible = ref(false)
const batchCount = ref(1000)
const batchSaving = ref(false)

function openBatch() {
  batchCount.value = 1000
  batchDialogVisible.value = true
}

async function saveBatch() {
  const n = Number(batchCount.value)
  if (!Number.isInteger(n) || n < 1 || n > 5000) {
    ElMessage.warning('数量需为 1–5000 的整数')
    return
  }
  batchSaving.value = true
  try {
    const res = await batchCreateContacts({ count: n })
    ElMessage.success(`批量添加完成：成功 ${res.created} 个${res.skipped ? `，跳过 ${res.skipped} 个` : ''}`)
    batchDialogVisible.value = false
    page.value = 1
    await load()
  } catch (e) {
    ElMessage.error('批量添加失败，请稍后重试')
    throw e
  } finally {
    batchSaving.value = false
  }
}
</script>

<template>
  <div class="contacts-page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索 externalId / 手机号 / 邮箱"
          clearable
          class="search-input"
          @keyup.enter="onSearch"
          @clear="onSearch"
        />
        <el-button type="primary" @click="onSearch">搜索</el-button>
        <div class="spacer" />
        <el-button @click="openBatch">批量添加</el-button>
        <el-button type="primary" @click="openCreate">新建联系人</el-button>
      </div>

      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="externalId" label="externalId" min-width="130" show-overflow-tooltip />
        <el-table-column prop="phone" label="手机号" min-width="130" />
        <el-table-column prop="email" label="邮箱" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : row.status === 'silent' ? 'warning' : 'danger'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="属性" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ fmtAttrs(row.attributes) }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="t in row.tags" :key="t" size="small" class="tag-chip" type="info">{{ t }}</el-tag>
            <span v-if="!row.tags?.length">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
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

    <el-dialog v-model="batchDialogVisible" title="批量添加联系人" width="480px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="数量">
          <el-input-number v-model="batchCount" :min="1" :max="5000" :step="100" style="width: 200px" />
        </el-form-item>
        <p class="batch-tip">
          随机生成 externalId 与手机号，并随机画像属性（churn_risk、level），可直接用于人群圈选与触达验证。单次上限 5000。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSaving" @click="saveBatch">生成</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新建联系人' : '编辑联系人'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form :model="form" label-width="90px">
        <el-form-item label="externalId">
          <el-input v-model="form.externalId" placeholder="外部系统 ID（可选，租户内唯一）" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="如 13800000000" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="如 user@example.com" />
        </el-form-item>
        <el-form-item label="pushToken">
          <el-input v-model="form.pushToken" placeholder="推送令牌（可选）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="active" value="active" />
            <el-option label="silent" value="silent" />
            <el-option label="unsubscribed" value="unsubscribed" />
          </el-select>
        </el-form-item>
        <el-form-item label="属性">
          <el-input
            v-model="attributesText"
            type="textarea"
            :rows="4"
            placeholder='JSON 对象，如 {"city":"Hangzhou","amount":320}'
            class="mono"
          />
        </el-form-item>
        <el-form-item label="标签">
          <el-select
            v-model="form.tags"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="输入后回车添加标签"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.search-input {
  width: 280px;
}
.spacer {
  flex: 1;
}
.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
.tag-chip {
  margin-right: 4px;
}
.mono :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.batch-tip {
  margin: 0;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}
</style>