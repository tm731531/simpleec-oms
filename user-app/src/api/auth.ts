import api from './index'
import { LoginRequest, LoginResponse } from '../types'

const API_BASE = '/api/auth'

export const authAPI = {
  login(data: LoginRequest) {
    return api.post<LoginResponse>(`${API_BASE}/login`, data)
  },

  logout() {
    return api.post(`${API_BASE}/logout`)
  },

  getProfile() {
    return api.get(`${API_BASE}/me`)
  }
}
