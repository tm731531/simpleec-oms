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

export const channelAPI = {
  listChannels() {
    return api.get<ChannelVO[]>('/api/user/channels')
  },

  listPlatforms() {
    return api.get<Platform[]>('/api/user/channels/platforms')
  },

  getPlatform(id: string) {
    return api.get<Platform>(`/api/user/channels/platforms/${id}`)
  }
}
