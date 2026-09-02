import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { login as apiLogin } from '../api/auth'
import { TENANT_KEY, TOKEN_KEY } from '../api/http'
import type { LoginResult } from '../api/types'

const USERNAME_KEY = 'ea_sys_username'
const ROLE_KEY = 'ea_sys_role'

/** 登录会话：token/租户/用户信息，localStorage 持久化 + 内存响应式副本。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
  const username = ref(localStorage.getItem(USERNAME_KEY) ?? '')
  const role = ref(localStorage.getItem(ROLE_KEY) ?? '')
  const tenantId = ref<number | null>(
    localStorage.getItem(TENANT_KEY) ? Number(localStorage.getItem(TENANT_KEY)) : null,
  )

  const isAuthenticated = computed(() => token.value !== '')

  async function login(u: string, p: string) {
    const result: LoginResult = await apiLogin(u, p)
    token.value = result.token
    username.value = result.username
    role.value = result.role
    tenantId.value = result.tenantId
    localStorage.setItem(TOKEN_KEY, result.token)
    localStorage.setItem(USERNAME_KEY, result.username)
    localStorage.setItem(ROLE_KEY, result.role)
    localStorage.setItem(TENANT_KEY, String(result.tenantId))
    return result
  }

  function logout() {
    token.value = ''
    username.value = ''
    role.value = ''
    tenantId.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(ROLE_KEY)
    localStorage.removeItem(TENANT_KEY)
  }

  return { token, username, role, tenantId, isAuthenticated, login, logout }
})