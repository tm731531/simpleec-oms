import axios from 'axios'

/**
 * Smart API URL detection for different deployment environments:
 * - Localhost: Use direct backend URL at http://localhost:8082/api
 * - Remote domains (via Cloudflare): Use /api relative path for reverse proxy
 */
function getAPIBaseURL(): string {
  if (typeof window === 'undefined') return '/api' // SSR fallback

  const { hostname } = window.location

  // For localhost development/testing, use direct backend URL
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return 'http://localhost:8082'
  }

  // For remote domains (oms.tomting.com, oms-admin.tomting.com)
  // Use relative path that the reverse proxy will handle
  return '/api'
}

const API_BASE_URL = getAPIBaseURL()

/**
 * Axios instance configured with base URL and default headers
 * Includes request/response interceptors for authorization and error handling
 */
const axiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Request interceptor
axiosInstance.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('authToken')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// Response interceptor
axiosInstance.interceptors.response.use(
  (response) => response.data.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('authToken')
      window.dispatchEvent(new CustomEvent('session-expired', {
        detail: { reason: 'unauthorized' }
      }))
      return Promise.reject(new Error('Session expired'))
    }
    return Promise.reject(error)
  }
)

export default axiosInstance
