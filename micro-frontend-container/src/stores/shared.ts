/**
 * Pinia shared store for container-level state management
 * Manages user authentication, polling settings, and theme preferences
 * Shared across all microapps in the qiankun container
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { User, PollingSettings, Theme } from '@/types'

/**
 * Shared store for container-wide state
 * Available to all microapps through Pinia
 */
export const useSharedStore = defineStore('shared', () => {
  /**
   * State: Current authenticated user
   */
  const currentUser = ref<User | null>(null)

  /**
   * State: Global polling configuration
   */
  const pollingSettings = ref<PollingSettings>({
    enabled: true,
    interval: 30000 // 30 seconds default
  })

  /**
   * State: Application theme preference
   */
  const theme = ref<Theme>('light')

  /**
   * State: Session expiration flag for graceful logout
   */
  const sessionExpired = ref(false)

  /**
   * State: JWT token (stored in state, localStorage, and axios headers)
   */
  const token = ref<string | null>(null)

  /**
   * Getters: isLoggedIn - Check if user is authenticated
   */
  const isLoggedIn = computed(() => currentUser.value !== null)

  /**
   * Getters: userRole - Get current user's role
   */
  const userRole = computed(() => currentUser.value?.role ?? null)

  /**
   * Getters: pollingInterval - Get current polling interval in milliseconds
   */
  const pollingInterval = computed(() => pollingSettings.value.interval)

  /**
   * Getters: isPollingEnabled - Check if polling is enabled
   */
  const isPollingEnabled = computed(() => pollingSettings.value.enabled)

  /**
   * Getters: userMerchantId - Get current user's merchant ID
   */
  const userMerchantId = computed(() => currentUser.value?.merchantId ?? null)

  /**
   * Getters: userWarehouseId - Get current user's warehouse ID
   */
  const userWarehouseId = computed(
    () => currentUser.value?.warehouseId ?? null
  )

  /**
   * Action: Set current user information
   * Persists to localStorage for session recovery
   * @param user - User information object
   */
  function setCurrentUser(user: User): void {
    currentUser.value = user
    sessionExpired.value = false

    // Persist to localStorage for session recovery
    localStorage.setItem('shared:currentUser', JSON.stringify(user))
  }

  /**
   * Action: Clear current user information
   * Removes from localStorage and clears token
   */
  function clearCurrentUser(): void {
    currentUser.value = null
    token.value = null
    sessionExpired.value = false

    // Remove from localStorage
    localStorage.removeItem('shared:currentUser')
    localStorage.removeItem('shared:token')

    // Clear any auth headers from axios if needed
    // This would be handled by the microapp's axios instance
  }

  /**
   * Action: Update polling settings
   * Both enabled flag and interval can be updated
   * @param settings - Partial polling settings to update
   */
  function updatePollingSettings(
    settings: Partial<PollingSettings>
  ): void {
    if (settings.enabled !== undefined) {
      pollingSettings.value.enabled = settings.enabled
    }

    if (settings.interval !== undefined && settings.interval > 0) {
      pollingSettings.value.interval = settings.interval
    }

    // Persist to localStorage
    localStorage.setItem(
      'shared:pollingSettings',
      JSON.stringify(pollingSettings.value)
    )
  }

  /**
   * Action: Toggle polling on/off
   */
  function togglePolling(): void {
    pollingSettings.value.enabled = !pollingSettings.value.enabled

    localStorage.setItem(
      'shared:pollingSettings',
      JSON.stringify(pollingSettings.value)
    )
  }

  /**
   * Action: Set theme preference
   * @param newTheme - 'light' or 'dark'
   */
  function setTheme(newTheme: Theme): void {
    theme.value = newTheme

    // Persist to localStorage
    localStorage.setItem('shared:theme', newTheme)

    // Update document class if needed
    if (newTheme === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }

  /**
   * Action: Set authentication token
   * @param newToken - JWT token string
   */
  function setToken(newToken: string): void {
    token.value = newToken

    // Persist to localStorage
    localStorage.setItem('shared:token', newToken)
  }

  /**
   * Action: Set session expired flag
   * Used for graceful logout on 401 responses
   * @param expired - Whether session is expired
   */
  function setSessionExpired(expired: boolean): void {
    sessionExpired.value = expired

    if (expired) {
      localStorage.setItem('shared:sessionExpired', 'true')
    } else {
      localStorage.removeItem('shared:sessionExpired')
    }
  }

  /**
   * Action: Restore state from localStorage
   * Called on app initialization to recover session
   */
  function restoreFromStorage(): void {
    // Restore user
    const storedUser = localStorage.getItem('shared:currentUser')
    if (storedUser) {
      try {
        currentUser.value = JSON.parse(storedUser)
      } catch (error) {
        console.error('Failed to restore user from localStorage:', error)
      }
    }

    // Restore token
    const storedToken = localStorage.getItem('shared:token')
    if (storedToken) {
      token.value = storedToken
    }

    // Restore polling settings
    const storedPollingSettings = localStorage.getItem(
      'shared:pollingSettings'
    )
    if (storedPollingSettings) {
      try {
        const settings = JSON.parse(storedPollingSettings)
        pollingSettings.value = {
          enabled: settings.enabled ?? true,
          interval: settings.interval ?? 30000
        }
      } catch (error) {
        console.error(
          'Failed to restore polling settings from localStorage:',
          error
        )
      }
    }

    // Restore theme
    const storedTheme = localStorage.getItem('shared:theme') as Theme | null
    if (storedTheme && (storedTheme === 'light' || storedTheme === 'dark')) {
      theme.value = storedTheme
      if (storedTheme === 'dark') {
        document.documentElement.classList.add('dark')
      }
    }

    // Restore session expired flag
    const isExpired = localStorage.getItem('shared:sessionExpired')
    sessionExpired.value = isExpired === 'true'
  }

  /**
   * Action: Reset all state
   * Useful for complete logout or app reset
   */
  function resetAll(): void {
    currentUser.value = null
    token.value = null
    sessionExpired.value = false
    pollingSettings.value = {
      enabled: true,
      interval: 30000
    }
    theme.value = 'light'

    // Clear all localStorage entries
    localStorage.removeItem('shared:currentUser')
    localStorage.removeItem('shared:token')
    localStorage.removeItem('shared:pollingSettings')
    localStorage.removeItem('shared:theme')
    localStorage.removeItem('shared:sessionExpired')

    // Reset document class
    document.documentElement.classList.remove('dark')
  }

  return {
    // State
    currentUser,
    pollingSettings,
    theme,
    sessionExpired,
    token,

    // Computed
    isLoggedIn,
    userRole,
    pollingInterval,
    isPollingEnabled,
    userMerchantId,
    userWarehouseId,

    // Actions
    setCurrentUser,
    clearCurrentUser,
    updatePollingSettings,
    togglePolling,
    setTheme,
    setToken,
    setSessionExpired,
    restoreFromStorage,
    resetAll
  }
})
