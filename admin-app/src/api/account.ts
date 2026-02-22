import axiosInstance from './index'
import { Account, PaginatedResponse } from '../types'

const API_BASE = '/admin'

export const accountAPI = {
  list(page = 1, pageSize = 20, merchantId?: string) {
    const query = merchantId ? `&merchant_id=${merchantId}` : ''
    return axiosInstance.get<PaginatedResponse<Account>>(
      `${API_BASE}/account?page=${page}&pageSize=${pageSize}${query}`
    )
  },

  get(id: string) {
    return axiosInstance.get<{ code: number; data: Account }>(
      `${API_BASE}/account/${id}`
    )
  },

  create(data: Partial<Account>) {
    return axiosInstance.post<{ code: number; data: Account }>(
      `${API_BASE}/account`,
      data
    )
  },

  update(id: string, data: Partial<Account>) {
    return axiosInstance.put<{ code: number; data: Account }>(
      `${API_BASE}/account/${id}`,
      data
    )
  },

  resetPassword(id: string, newPassword: string) {
    return axiosInstance.post<{ code: number }>(
      `${API_BASE}/account/${id}/reset-password`,
      { newPassword: newPassword }
    )
  },

  delete(id: string) {
    return axiosInstance.delete<{ code: number }>(
      `${API_BASE}/account/${id}`
    )
  }
}
