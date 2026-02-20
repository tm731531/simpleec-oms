import axios from 'axios'

// 使用相對路徑 - Cloudflare 反向代理會轉發到後端
const API_BASE = '/api/admin'

export const statsAPI = {
  getStats() {
    return axios.get<{
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
