import { http } from './http'
import type { ApiResponse, BatchContactCreateRequest, BatchContactCreateResult, Contact, ContactRequest, PageResponse } from './types'

/** GET /api/contacts?keyword=&page=&size= */
export async function listContacts(params: { keyword?: string; page?: number; size?: number }): Promise<PageResponse<Contact>> {
  const { data } = await http.get<ApiResponse<PageResponse<Contact>>>('/contacts', { params })
  return data.data
}

/** POST /api/contacts */
export async function createContact(req: ContactRequest): Promise<Contact> {
  const { data } = await http.post<ApiResponse<Contact>>('/contacts', req)
  return data.data
}

/** POST /api/contacts/batch —— 批量随机创建联系人（测试/压测种子）。 */
export async function batchCreateContacts(req: BatchContactCreateRequest): Promise<BatchContactCreateResult> {
  const { data } = await http.post<ApiResponse<BatchContactCreateResult>>('/contacts/batch', req)
  return data.data
}

/** GET /api/contacts/{id} */
export async function getContact(id: number): Promise<Contact> {
  const { data } = await http.get<ApiResponse<Contact>>(`/contacts/${id}`)
  return data.data
}

/** PUT /api/contacts/{id} */
export async function updateContact(id: number, req: ContactRequest): Promise<Contact> {
  const { data } = await http.put<ApiResponse<Contact>>(`/contacts/${id}`, req)
  return data.data
}

/** DELETE /api/contacts/{id} */
export async function deleteContact(id: number): Promise<void> {
  await http.delete<ApiResponse<null>>(`/contacts/${id}`)
}