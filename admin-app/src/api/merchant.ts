import axiosInstance from './index'
import { Merchant, PaginatedResponse } from '../types'

const API_BASE = '/admin'

export const merchantAPI = {
  list(page = 1, pageSize = 20) {
    return axiosInstance.get<PaginatedResponse<Merchant>>(
      `${API_BASE}/merchants?page=${page}&pageSize=${pageSize}`
    )
  },

  get(id: string) {
    return axiosInstance.get<{ code: number; data: Merchant }>(
      `${API_BASE}/merchants/${id}`
    )
  },

  create(data: Partial<Merchant>) {
    return axiosInstance.post<{ code: number; data: Merchant }>(
      `${API_BASE}/merchants`,
      data
    )
  },

  update(id: string, data: Partial<Merchant>) {
    return axiosInstance.put<{ code: number; data: Merchant }>(
      `${API_BASE}/merchants/${id}`,
      data
    )
  },

  delete(id: string) {
    return axiosInstance.delete<{ code: number }>(
      `${API_BASE}/merchants/${id}`
    )
  }
}
