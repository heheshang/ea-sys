import { http } from './http'
import type {
  ApiResponse,
  Audience,
  AudienceMember,
  AudienceRequest,
  AudienceSnapshot,
  PageResponse,
} from './types'

/** GET /api/audiences?page=&size= */
export async function listAudiences(page = 1, size = 20): Promise<PageResponse<Audience>> {
  const { data } = await http.get<ApiResponse<PageResponse<Audience>>>('/audiences', { params: { page, size } })
  return data.data
}

/** POST /api/audiences */
export async function createAudience(req: AudienceRequest): Promise<Audience> {
  const { data } = await http.post<ApiResponse<Audience>>('/audiences', req)
  return data.data
}

/** GET /api/audiences/{id} */
export async function getAudience(id: number): Promise<Audience> {
  const { data } = await http.get<ApiResponse<Audience>>(`/audiences/${id}`)
  return data.data
}

/** PUT /api/audiences/{id} */
export async function updateAudience(id: number, req: AudienceRequest): Promise<Audience> {
  const { data } = await http.put<ApiResponse<Audience>>(`/audiences/${id}`, req)
  return data.data
}

/** DELETE /api/audiences/{id} */
export async function deleteAudience(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/audiences/${id}`)
}

/** POST /api/audiences/{id}/snapshot —— 同步圈选并冻结快照。 */
export async function circleAudience(id: number): Promise<AudienceSnapshot> {
  const { data } = await http.post<ApiResponse<AudienceSnapshot>>(`/audiences/${id}/snapshot`)
  return data.data
}

/** GET /api/audiences/{id}/snapshots?page=&size= */
export async function listSnapshots(audienceId: number, page = 1, size = 20): Promise<PageResponse<AudienceSnapshot>> {
  const { data } = await http.get<ApiResponse<PageResponse<AudienceSnapshot>>>(
    `/audiences/${audienceId}/snapshots`,
    { params: { page, size } },
  )
  return data.data
}

/** GET /api/snapshots/{id}/members?page=&size= —— 快照成员分页预览。 */
export async function listMembers(snapshotId: number, page = 1, size = 20): Promise<PageResponse<AudienceMember>> {
  const { data } = await http.get<ApiResponse<PageResponse<AudienceMember>>>(
    `/snapshots/${snapshotId}/members`,
    { params: { page, size } },
  )
  return data.data
}