import axiosInstance from './index'

const API_BASE = '/admin'

export const statsAPI = {
  getStats() {
    return axiosInstance.get<{
      code: number
      data: {
        merchantCount: number
        accountCount: number
        platformCount: number
        orderCount: number
      }
    }>(`${API_BASE}/stats`)
  }
}
