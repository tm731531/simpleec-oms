/**
 * Usage Examples for Shared State Management and Event Bus
 *
 * This file demonstrates how to use the shared store and event bus
 * in microapps within the qiankun container.
 *
 * These examples can be used in microapps by importing:
 * - useSharedStore from '@/stores/shared'
 * - eventBus from '@/eventBus'
 */

import { useSharedStore } from '@/stores/shared'
import { eventBus } from '@/eventBus'

/**
 * Example 1: Login Flow
 * Demonstrates how to set user info and token in the shared store
 */
export function exampleLogin(): void {
  const sharedStore = useSharedStore()

  // Simulate API login response
  const loginResponse = {
    token: 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...',
    user: {
      id: 'user_12345',
      email: 'admin@example.com',
      name: 'Admin User',
      role: 'admin' as const,
      merchantId: 'merchant_001',
      warehouseId: 'warehouse_001',
      organizationId: 'org_001'
    }
  }

  // Set token and user in shared store
  sharedStore.setToken(loginResponse.token)
  sharedStore.setCurrentUser(loginResponse.user)

  // Emit login event for other microapps
  eventBus.emit('user:login', loginResponse.user)

  console.log('Login successful:', sharedStore.currentUser)
}

/**
 * Example 2: Logout Flow
 * Demonstrates how to clear user and handle logout across microapps
 */
export function exampleLogout(): void {
  const sharedStore = useSharedStore()

  // Clear user and token
  sharedStore.clearCurrentUser()

  // Emit logout event for other microapps to react
  eventBus.emit('user:logout', undefined)

  console.log('Logout completed, isLoggedIn:', sharedStore.isLoggedIn)
}

/**
 * Example 3: Accessing User Information in Components
 * Shows how to use the shared store in a Vue component
 */
export function exampleComponentUsage(): void {
  const sharedStore = useSharedStore()

  // Access user info
  console.log('Current User:', sharedStore.currentUser)
  console.log('Is Logged In:', sharedStore.isLoggedIn)
  console.log('User Role:', sharedStore.userRole)
  console.log('Merchant ID:', sharedStore.userMerchantId)
  console.log('Warehouse ID:', sharedStore.userWarehouseId)

  // These are all reactive computed properties
  // Components automatically update when values change
}

/**
 * Example 4: Session Expiration Handling
 * Demonstrates graceful handling of 401 responses
 */
export function exampleSessionExpiration(): void {
  const sharedStore = useSharedStore()

  // Interceptor in axios instance catches 401 responses
  sharedStore.setSessionExpired(true)

  // UI can then show a banner to prompt re-login
  if (sharedStore.sessionExpired) {
    console.log('Session expired, showing re-login prompt...')
  }

  // After successful re-login
  sharedStore.setSessionExpired(false)
  console.log('Session restored')
}

/**
 * Example 5: Polling Configuration
 * Demonstrates how to manage auto-refresh settings
 */
export function examplePollingSettings(): void {
  const sharedStore = useSharedStore()

  // Update polling interval to 60 seconds
  sharedStore.updatePollingSettings({
    enabled: true,
    interval: 60000 // 60 seconds
  })

  console.log('Polling enabled:', sharedStore.isPollingEnabled)
  console.log('Polling interval (ms):', sharedStore.pollingInterval)

  // Toggle polling on/off
  sharedStore.togglePolling()
  console.log('Polling toggled:', sharedStore.isPollingEnabled)
}

/**
 * Example 6: Theme Management
 * Demonstrates theme preference persistence
 */
export function exampleThemeManagement(): void {
  const sharedStore = useSharedStore()

  // Set theme
  sharedStore.setTheme('dark')

  console.log('Current theme:', sharedStore.theme)

  // Theme is automatically persisted to localStorage
  // and document.documentElement.classList is updated
}

/**
 * Example 7: Event Bus - Subscribe to Order Events
 * Demonstrates how microapps listen for events
 */
export function exampleEventBusSubscription(): void {
  // Subscribe to order created events
  eventBus.on('order:created', (payload) => {
    console.log('New order created:', payload.orderId, payload.amount)
    // Update UI, refresh list, etc.
  })

  // Note: In actual components, store the unsubscribe function
  // and call it in onBeforeUnmount for cleanup
}

/**
 * Example 8: Event Bus - One-time Listener
 * Demonstrates subscribing to an event once
 */
export function exampleEventBusOnce(): void {
  // Listen to next fatal error only
  eventBus.once('error:fatal', (payload) => {
    console.error('Fatal error occurred:', payload.message)
    // Show error modal, notify user, etc.
  })
}

/**
 * Example 9: Event Bus - Emit from One Microapp
 * Demonstrates publishing events from one app for others to consume
 */
export function exampleEventBusEmit(): void {
  // In Admin microapp, after creating an order
  eventBus.emit('order:created', {
    orderId: 'order_12345',
    merchantId: 'merchant_001',
    amount: 299.99
  })

  // User microapp listening to this event will immediately be notified
}

/**
 * Example 10: Event Bus - Async Event Emission
 * Demonstrates waiting for all listeners to complete
 */
export async function exampleEventBusAsync(): Promise<void> {
  // Register listeners that are async
  eventBus.on('data:refresh', async (payload) => {
    console.log('Refreshing data:', payload.scope)
    // await API calls, etc.
  })

  // Emit and wait for all listeners to complete
  await eventBus.emitAsync('data:refresh', { scope: 'all' })
  console.log('All refresh operations completed')
}

/**
 * Example 11: Multiple Listeners
 * Demonstrates multiple apps listening to same event
 */
export function exampleMultipleListeners(): void {
  // Admin app listening
  eventBus.on('order:updated', (payload) => {
    console.log('[Admin] Order status changed:', payload.status)
  })

  // User app listening
  eventBus.on('order:updated', (payload) => {
    console.log('[User] Your order status:', payload.status)
  })

  // Emit once, both listeners receive it
  eventBus.emit('order:updated', {
    orderId: 'order_12345',
    status: 'shipped',
    updatedAt: new Date().toISOString()
  })
}

/**
 * Example 12: Session Recovery on App Load
 * Demonstrates restoring state from localStorage
 */
export function exampleSessionRecovery(): void {
  const sharedStore = useSharedStore()

  // Call this in container app's onMounted
  sharedStore.restoreFromStorage()

  if (sharedStore.isLoggedIn) {
    console.log('Session recovered, user:', sharedStore.currentUser)
  } else {
    console.log('No active session, redirect to login')
  }
}

/**
 * Example 13: Vue Component Usage Pattern
 * Shows the recommended pattern for using shared store in components
 */
export function exampleVueComponentPattern(): void {
  // In a Vue component's setup() function:
  // import { useSharedStore } from '@/stores/shared'
  // import { eventBus } from '@/eventBus'
  //
  // export default defineComponent({
  //   setup() {
  //     const sharedStore = useSharedStore()
  //
  //     // Computed properties are reactive
  //     const userEmail = computed(() => sharedStore.currentUser?.email)
  //
  //     // Subscribe to events
  //     onMounted(() => {
  //       const unsubscribe = eventBus.on('order:updated', (payload) => {
  //         console.log('Order updated:', payload)
  //       })
  //
  //       // Cleanup on unmount
  //       onBeforeUnmount(() => {
  //         unsubscribe()
  //       })
  //     })
  //
  //     return {
  //       sharedStore,
  //       userEmail
  //     }
  //   }
  // })
}

/**
 * Example 14: Event Listener Cleanup
 * Demonstrates proper cleanup to prevent memory leaks
 */
export function exampleEventBusCleanup(): void {
  // When microapp is unmounted, clean up listeners
  eventBus.clear('order:created')

  // Or clear all listeners
  eventBus.clear()

  // Check listener counts
  console.log(
    'Total listeners:',
    eventBus.listenerCount()
  )
  console.log(
    'Order created listeners:',
    eventBus.listenerCount('order:created')
  )
}

/**
 * Example 15: State Persistence Pattern
 * Demonstrates proper state management for AJAX/API integration
 */
export function exampleAjaxIntegration(): void {
  const sharedStore = useSharedStore()

  // After successful login API call:
  // const response = await axios.post('/api/auth/login', credentials)
  // const { token, user } = response.data

  // Store token in shared state (also persisted to localStorage)
  sharedStore.setToken('jwt_token_here')

  // Store user info
  sharedStore.setCurrentUser({
    id: 'user_id',
    email: 'user@example.com',
    name: 'User Name',
    role: 'user',
    merchantId: 'merchant_id',
    warehouseId: 'warehouse_id'
  })

  // Token is now available to:
  // 1. All microapps through useSharedStore()
  // 2. localStorage for session recovery
  // 3. Can be injected into axios Authorization header
  //    in microapp's axios interceptor:
  //    instance.defaults.headers.common['Authorization'] = `Bearer ${sharedStore.token}`

  // On logout
  sharedStore.clearCurrentUser()
  // Token is cleared from state and localStorage
}
