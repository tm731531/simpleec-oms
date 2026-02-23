import axiosInstance from './index'
import { Platform, PaginatedResponse } from '../types'

const API_BASE = '/admin'

export const platformAPI = {
  list(page = 1, pageSize = 20) {
    return axiosInstance.get<PaginatedResponse<Platform>>(
      `${API_BASE}/platforms?page=${page}&pageSize=${pageSize}`
    )
  },

  get(id: string) {
    return axiosInstance.get<{ code: number; data: Platform }>(
      `${API_BASE}/platforms/${id}`
    )
  },

  create(data: Partial<Platform>) {
    return axiosInstance.post<{ code: number; data: Platform }>(
      `${API_BASE}/platforms`,
      data
    )
  },

  update(id: string, data: Partial<Platform>) {
    return axiosInstance.put<{ code: number; data: Platform }>(
      `${API_BASE}/platforms/${id}`,
      data
    )
  },

  delete(id: string) {
    return axiosInstance.delete<{ code: number }>(
      `${API_BASE}/platforms/${id}`
    )
  }
}
