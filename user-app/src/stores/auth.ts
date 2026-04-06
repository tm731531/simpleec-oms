import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import api from '../api/index'
import { User, LoginRequest, LoginResponse } from '../types'

// Storage key must match what index.ts interceptor reads: 'authToken'
const TOKEN_KEY = 'authToken'
const USER_KEY = 'user'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const user = ref<User | null>((() => {
    const stored = localStorage.getItem(USER_KEY)
    return stored ? JSON.parse(stored) : null
  })())
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!token.value)

  async function login(credentials: LoginRequest) {
    loading.value = true
    error.value = null
    try {
      const data: any = await api.post<LoginResponse>(`/api/auth/login`, credentials)
      // The interceptor returns response.data directly (no code field on login response)
      const newToken: string = data?.token ?? data
      const userData: User = data?.user ?? null

      token.value = newToken
      user.value = userData

      localStorage.setItem(TOKEN_KEY, newToken)
      if (userData) localStorage.setItem(USER_KEY, JSON.stringify(userData))

      return true
    } catch (err: any) {
      error.value = err.response?.data?.message || '登入失敗'
      return false
    } finally {
      loading.value = false
    }
  }

  function logout() {
    token.value = null
    user.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  function restoreToken() {
    const stored = localStorage.getItem(TOKEN_KEY)
    if (stored) {
      token.value = stored
    }
  }

  return {
    token,
    user,
    loading,
    error,
    isAuthenticated,
    login,
    logout,
    restoreToken
  }
})
