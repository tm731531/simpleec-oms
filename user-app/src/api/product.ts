import api from './index'
import { Product } from '../types'

const API_BASE = '/api/user'

export const productAPI = {
  list(page: number = 0, size: number = 10) {
    const params: Record<string, any> = { page, size }
    return api.get<{
      data: Product[]
      pagination: { total: number; pages: number; page: number; pageSize: number }
    }>(`${API_BASE}/products`, { params })
  },

  get(id: string) {
    return api.get<Product>(`${API_BASE}/products/${id}`)
  },

  create(data: Partial<Product>) {
    return api.post<Product>(`${API_BASE}/products`, data)
  },

  update(id: string, data: Partial<Product>) {
    return api.patch<Product>(`${API_BASE}/products/${id}`, data)
  },

  delete(id: string) {
    return api.delete(`${API_BASE}/products/${id}`)
  }
}
