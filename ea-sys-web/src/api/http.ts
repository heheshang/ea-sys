import axios, { AxiosError } from 'axios'
import type { ApiResponse } from './types'

export const TOKEN_KEY = 'ea_sys_token'
export const TENANT_KEY = 'ea_sys_tenant_id'

export const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  // 文档契约：X-Tenant-Id 租户头；后端当前经 JWT claim(tid) 解析，带上保持契约一致。
  const tenantId = localStorage.getItem(TENANT_KEY)
  if (tenantId) config.headers['X-Tenant-Id'] = tenantId
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (error: AxiosError<ApiResponse<unknown>>) => {
    if (error.response?.status === 401) {
      // Token 缺失/过期 → 清会话回登录页；避免循环跳转。
      if (!location.pathname.startsWith('/login')) {
        localStorage.removeItem(TOKEN_KEY)
        localStorage.removeItem(TENANT_KEY)
        location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)