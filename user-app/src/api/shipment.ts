import api from './index'

export interface Shipment {
  id: string
  orderId: string
  orderNumber?: string
  platform?: string
  trackingNumber?: string
  status?: string
  shippedAt?: string
  createdAt: string
  updatedAt: string
}

export const shipmentAPI = {
  list(page: number = 1, pageSize: number = 20) {
    return api.get<{
      data: Shipment[]
      pagination: { total: number; pages: number; page: number; pageSize: number }
    }>('/api/user/shipments', { params: { page, pageSize } })
  }
}
