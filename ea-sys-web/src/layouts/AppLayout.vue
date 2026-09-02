<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const menus = [
  { path: '/workflows', label: '工作流' },
  { path: '/contacts', label: '联系人' },
  { path: '/audiences', label: '人群管理' },
  { path: '/canvas', label: 'DAG 编排' },
  { path: '/dashboard', label: '留存看板' },
  { path: '/monitoring', label: '触达监控' },
  { path: '/agent', label: '智能体配置' },
  { path: '/templates', label: '模板与通道' },
]

const activeMenu = computed(() => {
  const match = menus.find((m) => m.path !== '/canvas' && route.path.startsWith(m.path))
  if (match) return match.path
  // DAG 编排带 :id 时归入 /canvas
  if (route.path.startsWith('/canvas')) return '/canvas'
  return route.path
})

function onLogout() {
  auth.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="200px" class="app-aside">
      <div class="app-logo">EA-Sys</div>
      <el-menu :default-active="activeMenu" router class="app-menu">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          {{ m.label }}
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="app-header">
        <span class="app-header-title">{{ route.meta.title ?? '' }}</span>
        <span class="app-header-info">
          租户 {{ auth.tenantId }} · {{ auth.username }}（{{ auth.role }}）
          <el-button link type="primary" @click="onLogout">退出</el-button>
        </span>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-shell {
  height: 100vh;
}
.app-aside {
  background: #001529;
}
.app-logo {
  height: 56px;
  line-height: 56px;
  text-align: center;
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  letter-spacing: 2px;
}
.app-menu {
  border-right: none;
  background: transparent;
  --el-menu-text-color: rgba(255, 255, 255, 0.68);
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.08);
  --el-menu-active-color: #409eff;
}
.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e4e7ed;
  background: #fff;
}
.app-header-title {
  font-size: 16px;
  font-weight: 600;
}
.app-header-info {
  color: #606266;
  font-size: 13px;
}
.app-main {
  background: #f5f7fa;
}
</style>