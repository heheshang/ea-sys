import { http } from './http'
import type { ApiResponse, LoginResult, WhoamiResult } from './types'

/** POST /api/auth/login。 */
export async function login(username: string, password: string): Promise<LoginResult> {
  const { data } = await http.post<ApiResponse<LoginResult>>('/auth/login', { username, password })
  return data.data
}

/** GET /api/whoami —— 验证 token 并取当前租户/用户。 */
export async function whoami(): Promise<WhoamiResult> {
  const { data } = await http.get<ApiResponse<WhoamiResult>>('/whoami')
  return data.data
}