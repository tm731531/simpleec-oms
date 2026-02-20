import axios from 'axios'
import { Merchant, PaginatedResponse } from '../types'

// 使用相對路徑 - Cloudflare 反向代理會轉發到後端
const API_BASE = '/api/admin'

export const merchantAPI = {
  list(page = 1, pageSize = 20) {
    return axios.get<PaginatedResponse<Merchant>>(
      `${API_BASE}/merchant?page=${page}&pageSize=${pageSize}`
    )
  },

  get(id: string) {
    return axios.get<{ code: number; data: Merchant }>(
      `${API_BASE}/merchant/${id}`
    )
  },

  create(data: Partial<Merchant>) {
    return axios.post<{ code: number; data: Merchant }>(
      `${API_BASE}/merchant`,
      data
    )
  },

  update(id: string, data: Partial<Merchant>) {
    return axios.put<{ code: number; data: Merchant }>(
      `${API_BASE}/merchant/${id}`,
      data
    )
  },

  delete(id: string) {
    return axios.delete<{ code: number }>(
      `${API_BASE}/merchant/${id}`
    )
  }
}
