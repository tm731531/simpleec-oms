import api from './index'

export interface TodayStats {
  date: string
  totalOrders: number
  totalAmount: number
  channels: Array<{
    channelId: string
    platformName: string
    orderCount: number
  }>
}

export const statsAPI = {
  today() {
    return api.get<{ data: TodayStats; success: boolean }>('/api/user/stats/today')
  },

  daily() {
    return api.get<{ data: any; success: boolean }>('/api/user/stats/daily')
  }
}
