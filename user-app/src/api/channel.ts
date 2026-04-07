import api from './index'
import { Platform } from '../types'

export interface ChannelVO {
  id: string
  platformId: string
  platformName: string
  channelName: string
  channelSn: string
  actived: boolean
  writeActived: boolean
  enableSync: boolean
  token1Masked?: string
  token2Masked?: string
  token3Masked?: string
  token4Masked?: string
  token5Masked?: string
  oauthFlow?: string
  oauthStatus?: string
  tokenExpiresAt?: string
  tokenExpiresInMinutes?: number
  tokenLabels?: Record<string, string | null>
  lastSyncTime?: string
  createdAt: string
  updatedAt: string
}

export interface ChannelFormData {
  platformId?: string
  channelName?: string
  channelSn?: string
  token?: string
  token2?: string
  token3?: string
  token4?: string
  token5?: string
  writeActived?: boolean
  enableSync?: boolean
}

export const channelAPI = {
  listChannels() {
    return api.get<ChannelVO[]>('/api/user/channels')
  },

  getChannel(id: string) {
    return api.get<ChannelVO>(`/api/user/channels/${id}`)
  },

  listPlatforms() {
    return api.get<Platform[]>('/api/user/channels/platforms')
  },

  createChannel(data: ChannelFormData) {
    return api.post<ChannelVO>('/api/user/channels', data)
  },

  updateChannel(id: string, data: ChannelFormData) {
    return api.put<ChannelVO>(`/api/user/channels/${id}`, data)
  },

  toggleStatus(id: string) {
    return api.put<ChannelVO>(`/api/user/channels/${id}/toggle-status`)
  },

  getShopeeAuthUrl(id: string) {
    return api.get<{ authUrl: string }>(`/api/user/channels/${id}/shopee/auth-url`)
  },

  refreshShopeeToken(id: string) {
    return api.post<ChannelVO>(`/api/user/channels/${id}/shopee/refresh-token`)
  },

  disconnectShopee(id: string) {
    return api.post<ChannelVO>(`/api/user/channels/${id}/shopee/disconnect`)
  },

  syncSellPack(id: string) {
    return api.post<{ message: string; topic: string }>(`/api/user/channels/${id}/sync-sellpack`)
  },

  getSyncLogs(id: string, page = 1, pageSize = 20) {
    return api.get(`/api/user/channels/${id}/sync-logs`, { params: { page, pageSize } })
  },

  getPlatform(id: string) {
    return api.get<Platform>(`/api/user/channels/platforms/${id}`)
  },

  triggerHealthCheck() {
    return api.post<{ channelsTriggered: number; platformsTriggered: number }>('/api/user/channels/trigger-health-check')
  }
}
