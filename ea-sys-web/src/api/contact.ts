import { http } from './http'
import type { ApiResponse, Contact, ContactRequest, PageResponse } from './types'

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