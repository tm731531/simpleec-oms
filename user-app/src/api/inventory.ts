import api from './index'

export interface InventoryItem {
  id: string
  sku: string
  productName?: string
  specSummary?: string
  quantity?: number
  safetyQuantity?: number
}

export const inventoryAPI = {
  list(page: number = 1, pageSize: number = 20) {
    const params: Record<string, any> = { page, pageSize }
    return api.get<{
      data: InventoryItem[]
      pagination: { total: number; pages: number; page: number; pageSize: number }
    }>('/api/user/inventory', { params })
  },

  listLowStock(page: number = 1, pageSize: number = 20) {
    return api.get<{
      data: InventoryItem[]
      pagination: { total: number; pages: number; page: number; pageSize: number }
    }>('/api/user/inventory/low-stock', { params: { page, pageSize } })
  },

  update(id: string, quantity: number) {
    return api.patch(`/api/user/inventory/${id}`, { quantity })
  }
}
