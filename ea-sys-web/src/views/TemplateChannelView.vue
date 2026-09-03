<script setup lang="ts">
/**
 * 模板与通道（M3）：触达模板 CRUD。
 * 数据：GET/POST /api/templates、PUT/DELETE /api/templates/{id}。
 * 模板内容为 FreeMarker，服务端 create/update 时做语法渲染校验。
 */
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createTemplate, deleteTemplate, listTemplates, updateTemplate } from '../api/template'
import type { TemplateRequest } from '../api/template'
import type { Template } from '../api/types'

/** FreeMarker 字面量（模板里直接写 {{ }} 会被 Vue 解析，故提为常量）。 */
const fm = {
  name: '{{name}}',
  phone: '{{phone}}',
  email: '{{email}}',
  externalId: '{{externalId}}',
}
const fmPlaceholder = `FreeMarker 模板，如：您好 ${fm.name}，限时促销已开启！`

const {
  data: templates,
  isLoading,
  isError,
  refetch,
} = useQuery<Template[]>({ queryKey: ['templates'], queryFn: listTemplates })

const channelCount = computed(() => {
  const rows = templates.value ?? []
  return {
    total: rows.length,
    sms: rows.filter((t) => t.channel === 'sms').length,
    email: rows.filter((t) => t.channel === 'email').length,
    wechat: rows.filter((t) => t.channel === 'wechat').length,
  }
})

function fmtTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function channelType(ch: string): 'success' | 'warning' | 'primary' {
  if (ch === 'sms') return 'success'
  if (ch === 'wechat') return 'primary'
  return 'warning'
}

/* ---------- 新建 / 编辑 ---------- */
const formVisible = ref(false)
const formLoading = ref(false)
const editingId = ref<number | null>(null)
const form = ref<TemplateRequest>({ channel: 'sms', name: '', content: '' })

function openCreate() {
  editingId.value = null
  form.value = { channel: 'sms', name: '', content: '' }
  formVisible.value = true
}

function openEdit(row: Template) {
  editingId.value = row.id
  form.value = { channel: row.channel, name: row.name, content: row.content }
  formVisible.value = true
}

async function submitForm() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写模板名称')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('请填写模板内容')
    return
  }
  formLoading.value = true
  try {
    const req: TemplateRequest = {
      channel: form.value.channel,
      name: form.value.name.trim(),
      content: form.value.content,
    }
    if (editingId.value === null) {
      await createTemplate(req)
      ElMessage.success('模板已创建')
    } else {
      await updateTemplate(editingId.value, req)
      ElMessage.success('模板已更新')
    }
    formVisible.value = false
    await refetch()
  } catch {
    ElMessage.error(editingId.value === null ? '模板创建失败（检查 FreeMarker 语法）' : '模板更新失败（检查 FreeMarker 语法）')
  } finally {
    formLoading.value = false
  }
}

/* ---------- 删除 ---------- */
async function remove(row: Template) {
  await ElMessageBox.confirm(`确认删除模板「${row.name}」？`, '删除确认', { type: 'warning' })
  try {
    await deleteTemplate(row.id)
    ElMessage.success('已删除')
    await refetch()
  } catch {
    ElMessage.error('删除失败')
  }
}

/* ---------- 预览 ---------- */
const previewVisible = ref(false)
const previewTpl = ref<Template | null>(null)

function showPreview(row: Template) {
  previewTpl.value = row
  previewVisible.value = true
}
</script>

<template>
  <div class="tpl-page">
    <div class="page-head">
      <h3>模板与通道</h3>
      <el-button type="primary" @click="openCreate">新建模板</el-button>
    </div>

    <el-row :gutter="12" class="stats">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num">{{ channelCount.total }}</div>
          <div class="stat-label">启用模板</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num stat-sms">{{ channelCount.sms }}</div>
          <div class="stat-label">短信 SMS</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num stat-email">{{ channelCount.email }}</div>
          <div class="stat-label">邮件 Email</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-num stat-wechat">{{ channelCount.wechat }}</div>
          <div class="stat-label">微信 WeChat</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <el-alert
        v-if="isError"
        title="模板列表加载失败"
        type="error"
        :closable="false"
        class="list-error"
      />
      <el-table v-loading="isLoading" :data="templates ?? []" size="small" border stripe>
        <el-table-column prop="id" label="ID" width="56" />
        <el-table-column label="通道" width="100">
          <template #default="{ row }">
            <el-tag :type="channelType(row.channel)" size="small">{{ row.channel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="内容（FreeMarker）" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="tpl-code">{{ row.content }}</code>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="132">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="showPreview(row)">预览</el-button>
            <el-button size="small" type="success" link @click="openEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" link @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无模板，点击「新建模板」创建" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="formVisible"
      :title="editingId === null ? '新建模板' : `编辑模板 #${editingId}`"
      width="560px"
    >
      <el-form label-width="80px">
        <el-form-item label="通道">
          <el-radio-group v-model="form.channel">
            <el-radio value="sms">短信 SMS</el-radio>
            <el-radio value="email">邮件 Email</el-radio>
            <el-radio value="wechat">微信 WeChat</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如：促销通知" maxlength="128" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="6" :placeholder="fmPlaceholder" />
          <div class="form-tip">
            可用变量：<code>{{ fm.name }}</code> 姓名、<code>{{ fm.phone }}</code> 手机号、<code>{{ fm.email }}</code> 邮箱、<code>{{ fm.externalId }}</code> 外部ID（微信模板建议用 <code>{{ fm.externalId }}</code>，渲染时以 <code>!</code> 防空，如 <code>{{ fm.externalId }}!</code>）；保存时后端做语法渲染校验。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="formLoading" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预览 -->
    <el-dialog v-model="previewVisible" :title="`模板预览：${previewTpl?.name ?? ''}`" width="520px">
      <template v-if="previewTpl">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="通道">
            <el-tag :type="channelType(previewTpl.channel)" size="small">{{ previewTpl.channel }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">{{ previewTpl.status }}</el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">{{ fmtTime(previewTpl.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <h4 class="tpl-head">模板内容（FreeMarker）</h4>
        <pre class="tpl-block">{{ previewTpl.content }}</pre>
      </template>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tpl-page {
  padding: 16px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.stats {
  margin-bottom: 12px;
}
.stat-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 0;
}
.stat-num {
  font-size: 24px;
  font-weight: 700;
  color: #409eff;
}
.stat-sms {
  color: #67c23a;
}
.stat-email {
  color: #e6a23c;
}
.stat-wechat {
  color: #67c23a;
}
.stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}
.list-error {
  margin-bottom: 8px;
}
.tpl-code {
  font-size: 12px;
  color: #606266;
}
.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-top: 4px;
}
.form-tip code,
.form-tip :deep(code) {
  background: #f5f7fa;
  border-radius: 3px;
  padding: 0 4px;
  color: #e6a23c;
}
.tpl-head {
  margin: 14px 0 6px;
}
.tpl-block {
  margin: 0;
  padding: 10px;
  background: #f8f9fb;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>