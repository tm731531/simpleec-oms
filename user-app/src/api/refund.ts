import api from './index'
import { Refund } from '../types'

const API_BASE = '/api/user'

export const refundAPI = {
  list(page: number = 1, pageSize: number = 10, orderId?: string) {
    const params: Record<string, any> = { page, pageSize }
    if (orderId) params.orderId = orderId
    return api.get<{
      data: Refund[]
      pagination: { total: number; pages: number; page: number; pageSize: number }
    }>(`${API_BASE}/refunds`, { params })
  },

  get(id: string) {
    return api.get<Refund>(`${API_BASE}/refunds/${id}`)
  },

  create(data: Partial<Refund>) {
    return api.post<Refund>(`${API_BASE}/refunds`, data)
  },

  approve(id: string) {
    return api.patch<Refund>(`${API_BASE}/refunds/${id}`, { action: 'approve' })
  },

  reject(id: string, reason?: string) {
    return api.patch<Refund>(`${API_BASE}/refunds/${id}`, { action: 'reject', reason })
  }
}
