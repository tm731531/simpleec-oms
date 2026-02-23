import axios from 'axios'

/**
 * API URL configuration:
 * Uses relative path /api which works in all environments:
 * - Browser requests /api/...
 * - Nginx reverse proxy on 8089 routes to backend API
 * - Works for localhost, Docker domains, and remote domains
 * This is the correct approach because:
 * - Browsers cannot access Docker container names (simpleec-api)
 * - Relative paths are resolved by the browser to current host:port
 * - Nginx handles the internal routing to backend
 */
function getAPIBaseURL(): string {
  // Always use relative path - browser will resolve to current host
  // This works in all environments and lets Nginx handle routing
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
