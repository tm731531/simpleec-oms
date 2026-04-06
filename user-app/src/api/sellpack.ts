import api from './index'
import { SellPack } from '../types'

const API_BASE = '/api/user'

export const sellpackAPI = {
  list(page: number = 0, size: number = 10) {
    const params: Record<string, any> = { page, size }
    return api.get<{
      data: SellPack[]
      pagination: { total: number; pages: number; page: number; pageSize: number }
    }>(`${API_BASE}/sellpacks`, { params })
  },

  get(id: string) {
    return api.get<SellPack>(`${API_BASE}/sellpacks/${id}`)
  },

  update(id: string, action: string, value: any) {
    return api.patch<SellPack>(`${API_BASE}/sellpacks/${id}`, { action, value })
  }
}
