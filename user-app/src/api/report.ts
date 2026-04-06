import api from './index'

export interface SalesReport {
  summary: {
    totalOrders: number
    totalAmount: number
    totalShipped: number
    totalCancelled: number
  }
  byDate: Array<{
    date: string
    orderCount: number
    amount: number
  }>
  byPlatform: Array<{
    platformId: string
    orderCount: number
    percentage: number
  }>
}

export interface ProfitReport {
  totalRevenue: number
  grossProfit?: number
  grossMargin?: number
  byProduct: Array<{
    productName: string
    revenue?: number
    cost?: number
    profit?: number
  }>
}

export const reportAPI = {
  sales(from: string, to: string) {
    return api.get<SalesReport>('/api/user/reports/sales', { params: { from, to } })
  },

  profit(from: string, to: string) {
    return api.get<ProfitReport>('/api/user/reports/profit', { params: { from, to } })
  }
}
