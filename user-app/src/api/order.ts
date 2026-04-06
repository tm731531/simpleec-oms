import api from './index'
import { Order } from '../types'

const API_BASE = '/api/user'

export const orderAPI = {
  list(page: number = 1, pageSize: number = 10, channelId?: string, status?: string) {
    const params: Record<string, any> = { page, pageSize }
    if (channelId) params.channelId = channelId
    if (status) params.status = status
    return api.get<{
      data: Order[]
      pagination: { total: number; pages: number; page: number; pageSize: number }
    }>(`${API_BASE}/orders`, { params })
  },

  get(id: string) {
    return api.get<Order>(`${API_BASE}/orders/${id}`)
  },

  ship(id: string, trackingNumber?: string) {
    return api.patch<Order>(
      `${API_BASE}/orders/${id}`,
      { action: 'ship', trackingNumber }
    )
  },

  cancel(id: string, reason?: string) {
    return api.patch<Order>(
      `${API_BASE}/orders/${id}`,
      { action: 'cancel', reason }
    )
  },

  getStatusLogs(id: string) {
    return api.get<any[]>(`${API_BASE}/orders/${id}/status-logs`)
  }
}
