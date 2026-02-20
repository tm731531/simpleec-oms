import axios from 'axios'
import { Platform, PaginatedResponse } from '../types'

// 使用相對路徑 - Cloudflare 反向代理會轉發到後端
const API_BASE = '/api/admin'

export const platformAPI = {
  list(page = 1, pageSize = 20) {
    return axios.get<PaginatedResponse<Platform>>(
      `${API_BASE}/platform?page=${page}&pageSize=${pageSize}`
    )
  },

  get(id: string) {
    return axios.get<{ code: number; data: Platform }>(
      `${API_BASE}/platform/${id}`
    )
  },

  create(data: Partial<Platform>) {
    return axios.post<{ code: number; data: Platform }>(
      `${API_BASE}/platform`,
      data
    )
  },

  update(id: string, data: Partial<Platform>) {
    return axios.put<{ code: number; data: Platform }>(
      `${API_BASE}/platform/${id}`,
      data
    )
  },

  delete(id: string) {
    return axios.delete<{ code: number }>(
      `${API_BASE}/platform/${id}`
    )
  }
}
