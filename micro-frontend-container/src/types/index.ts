/**
 * Shared TypeScript types and interfaces for micro-frontend container
 */

/**
 * User information stored in shared state
 */
export interface User {
  id: string
  email: string
  name: string
  role: 'admin' | 'user' | 'manager'
  merchantId: string
  warehouseId?: string
  organizationId?: string
}

/**
 * Polling settings for automatic data refresh
 */
export interface PollingSettings {
  enabled: boolean
  interval: number // milliseconds
}

/**
 * Application theme setting
 */
export type Theme = 'light' | 'dark'

/**
 * Event bus event types mapping
 * Keys are event names, values are the payload types
 */
export interface EventMap {
  'user:login': User
  'user:logout': void
  'order:created': {
    orderId: string
    merchantId: string
    amount: number
  }
  'order:updated': {
    orderId: string
    status: string
    updatedAt: string
  }
  'data:refresh': {
    scope?: 'orders' | 'products' | 'all'
  }
  'error:fatal': {
    message: string
    code?: string
    timestamp: string
  }
}

/**
 * Event listener callback type
 */
export type EventCallback<K extends keyof EventMap> = (
  payload: EventMap[K]
) => void | Promise<void>
