import axiosInstance from './index'
import { Platform, PaginatedResponse } from '../types'

const API_BASE = '/admin'

export const platformAPI = {
  list(page = 1, pageSize = 20) {
    return axiosInstance.get<PaginatedResponse<Platform>>(
      `${API_BASE}/platform?page=${page}&pageSize=${pageSize}`
    )
  },

  get(id: string) {
    return axiosInstance.get<{ code: number; data: Platform }>(
      `${API_BASE}/platform/${id}`
    )
  },

  create(data: Partial<Platform>) {
    return axiosInstance.post<{ code: number; data: Platform }>(
      `${API_BASE}/platform`,
      data
    )
  },

  update(id: string, data: Partial<Platform>) {
    return axiosInstance.put<{ code: number; data: Platform }>(
      `${API_BASE}/platform/${id}`,
      data
    )
  },

  delete(id: string) {
    return axiosInstance.delete<{ code: number }>(
      `${API_BASE}/platform/${id}`
    )
  }
}
