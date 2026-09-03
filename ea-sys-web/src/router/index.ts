import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: () => import('../layouts/AppLayout.vue'),
      redirect: '/workflows',
      children: [
        {
          path: 'workflows',
          name: 'workflows',
          component: () => import('../views/WorkflowListView.vue'),
          meta: { title: '工作流' },
        },
        {
          path: 'contacts',
          name: 'contacts',
          component: () => import('../views/ContactsView.vue'),
          meta: { title: '联系人' },
        },
        {
          path: 'audiences',
          name: 'audiences',
          component: () => import('../views/AudienceListView.vue'),
          meta: { title: '人群管理' },
        },
        {
          path: 'canvas/:id?',
          name: 'canvas',
          component: () => import('../views/WorkflowCanvasView.vue'),
          meta: { title: 'DAG 编排' },
        },
        {
          path: 'dashboard',
          name: 'dashboard',
          component: () => import('../views/DashboardView.vue'),
          meta: { title: '留存看板' },
        },
        {
          path: 'monitoring',
          name: 'monitoring',
          component: () => import('../views/MonitoringView.vue'),
          meta: { title: '触达监控' },
        },
        {
          path: 'agent',
          name: 'agent',
          component: () => import('../views/AgentConfigView.vue'),
          meta: { title: '智能体配置' },
        },
        {
          path: 'templates',
          name: 'templates',
          component: () => import('../views/TemplateChannelView.vue'),
          meta: { title: '模板与通道' },
        },
        {
          path: 'cockpit',
          name: 'cockpit',
          component: () => import('../views/CockpitView.vue'),
          meta: { title: '驾驶舱' },
        },
        {
          path: 'evaluations',
          name: 'evaluations',
          component: () => import('../views/EvaluationView.vue'),
          meta: { title: '评测中心' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/workflows' },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && auth.isAuthenticated) {
    return { name: 'workflows' }
  }
  return true
})

export default router