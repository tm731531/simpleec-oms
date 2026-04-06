import { SyncStatus } from '../types'

export function getSyncStatusText(status: SyncStatus['status']): string {
  const map: Record<string, string> = {
    pending: '待同步',
    syncing: '同步中',
    completed: '已同步',
    failed: '同步失敗'
  }
  return map[status] || status
}

export function getSyncStatusType(status: SyncStatus['status']): string {
  const map: Record<string, string> = {
    pending: 'info',
    syncing: 'warning',
    completed: 'success',
    failed: 'danger'
  }
  return map[status] || 'info'
}

export function getOperationText(operation: SyncStatus['operation']): string {
  const map: Record<string, string> = {
    QUANTITY_UPDATE: '數量更新',
    PRICE_UPDATE: '價格更新',
    LISTING_UPDATE: '上架狀態',
    IMAGE_UPDATE: '圖片更新',
    DESC_UPDATE: '說明更新'
  }
  return map[operation] || operation
}

export function getElapsedTime(startTime: string | undefined): string {
  if (!startTime) return ''

  const start = new Date(startTime).getTime()
  const now = Date.now()
  const diff = Math.floor((now - start) / 1000)

  if (diff < 60) return `${diff}秒`
  if (diff < 3600) return `${Math.floor(diff / 60)}分${diff % 60}秒`

  const hours = Math.floor(diff / 3600)
  const minutes = Math.floor((diff % 3600) / 60)
  return `${hours}小時${minutes}分`
}

export function formatDateTime(dateStr: string | undefined): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleString('zh-TW', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}
