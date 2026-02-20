import axios from 'axios'
import { Merchant, PaginatedResponse } from '../types'

const API_BASE = 'http://localhost:8082/api/admin'

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
