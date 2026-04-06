// Auth
export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  token: string
  user: User
}

export interface User {
  id: string
  email: string
  name: string
  role: string
  merchantId: string
  merchantName: string
}

// Product
export interface Product {
  id: string
  sku: string
  productName: string
  quantity: number
  safetyQuantity: number
  suggestPrice: number
  costPrice: number
  specSummary: string
  status: 'active' | 'inactive'
  merchantId: string
  createdAt: string
  updatedAt: string
}

// SellPack (Product on Platform)
export interface SyncStatus {
  status: 'pending' | 'syncing' | 'completed' | 'failed'
  operation: 'QUANTITY_UPDATE' | 'PRICE_UPDATE' | 'LISTING_UPDATE' | 'IMAGE_UPDATE' | 'DESC_UPDATE'
  oldValue?: any
  newValue?: any
  startTime?: string
  completedTime?: string
  error?: string
  retryCount: number
}

export interface SellPack {
  id: string
  productId: string
  platformId: string
  channelId: string
  sku: string
  channelSpecName: string
  quantity: number
  sellingPrice: number
  channelProductUrl: string
  title: string
  status: 'active' | 'inactive'
  syncStatus: string
  lastSyncAt: string
  syncStatusDetail: SyncStatus
  createdAt: string
  updatedAt: string
}

// Order
export interface Order {
  id: string
  orderNumber: string
  platform: string
  totalAmount: number
  status: 'pending' | 'confirmed' | 'shipped' | 'completed' | 'cancelled'
  syncStatus: {
    operation: 'SHIPMENT' | 'CANCEL'
    status: 'pending' | 'syncing' | 'completed' | 'failed'
    startTime?: string
    completedTime?: string
    error?: string
  }
  createdAt: string
  updatedAt: string
}

// Refund
export interface Refund {
  id: string
  orderId: string
  channelId: string
  channelOrderId: string
  channelRefundId: string
  refundAmount: number
  reason: string
  returnStatus: string
  requestedAt: string
  createdAt: string
  updatedAt: string
}

// Platform
export interface Platform {
  id: string
  platformName: string
  queueTopic: string
  capabilities: Record<string, any>
}
