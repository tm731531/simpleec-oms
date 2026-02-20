import axios from 'axios'
import { Account, PaginatedResponse } from '../types'

// 使用相對路徑 - Cloudflare 反向代理會轉發到後端
const API_BASE = '/api/admin'

export const accountAPI = {
  list(page = 1, pageSize = 20, merchantId?: string) {
    const query = merchantId ? `&merchant_id=${merchantId}` : ''
    return axios.get<PaginatedResponse<Account>>(
      `${API_BASE}/account?page=${page}&pageSize=${pageSize}${query}`
    )
  },

  get(id: string) {
    return axios.get<{ code: number; data: Account }>(
      `${API_BASE}/account/${id}`
    )
  },

  create(data: Partial<Account>) {
    return axios.post<{ code: number; data: Account }>(
      `${API_BASE}/account`,
      data
    )
  },

  update(id: string, data: Partial<Account>) {
    return axios.put<{ code: number; data: Account }>(
      `${API_BASE}/account/${id}`,
      data
    )
  },

  resetPassword(id: string, newPassword: string) {
    return axios.post<{ code: number }>(
      `${API_BASE}/account/${id}/reset-password`,
      { password: newPassword }
    )
  },

  delete(id: string) {
    return axios.delete<{ code: number }>(
      `${API_BASE}/account/${id}`
    )
  }
}
