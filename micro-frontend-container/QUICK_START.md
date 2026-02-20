# Shared State Management - Quick Start Guide

## 1-Minute Overview

The container app provides **shared state** and **event bus** for all microapps:

```typescript
// In any microapp
import { useSharedStore } from '@/stores/shared'
import { eventBus } from '@/eventBus'

const store = useSharedStore()

// Check if user is logged in
if (store.isLoggedIn) {
  console.log('User:', store.currentUser?.name)
}

// Listen to events
eventBus.on('order:created', (payload) => {
  console.log('New order:', payload.orderId)
})
```

## Installation

**Already done!** The container app has:
- ✅ Pinia installed and configured in `src/main.ts`
- ✅ Shared store at `src/stores/shared.ts`
- ✅ Event bus at `src/eventBus.ts`

## Quick Examples

### Login/Logout
```typescript
const store = useSharedStore()

// On successful login
store.setToken('jwt_token')
store.setCurrentUser(userData)

// On logout
store.clearCurrentUser()
```

### Access User Info in Any Component
```typescript
import { useSharedStore } from '@/stores/shared'
import { computed } from 'vue'

export default {
  setup() {
    const store = useSharedStore()

    return {
      isLoggedIn: computed(() => store.isLoggedIn),
      userRole: computed(() => store.userRole),
      userName: computed(() => store.currentUser?.name)
    }
  }
}
```

### Listen to Events
```typescript
import { eventBus } from '@/eventBus'
import { onBeforeUnmount } from 'vue'

const unsubscribe = eventBus.on('order:updated', (payload) => {
  console.log('Order updated:', payload)
})

// Cleanup on unmount
onBeforeUnmount(() => unsubscribe())
```

### Emit Events (from Admin to notify User app)
```typescript
import { eventBus } from '@/eventBus'

eventBus.emit('order:created', {
  orderId: 'order_123',
  merchantId: 'merchant_001',
  amount: 299.99
})
```

## Available Store Properties

| Property | Type | Description |
|----------|------|-------------|
| `currentUser` | User \| null | Current logged-in user |
| `token` | string \| null | JWT authentication token |
| `isLoggedIn` | boolean | Whether user is authenticated |
| `userRole` | string \| null | User's role (admin/user/manager) |
| `userMerchantId` | string \| null | User's merchant ID |
| `pollingSettings` | { enabled, interval } | Auto-refresh config |
| `isPollingEnabled` | boolean | Whether polling is on |
| `pollingInterval` | number | Polling interval in ms |
| `theme` | 'light' \| 'dark' | Current theme |
| `sessionExpired` | boolean | Session expired flag |

## Available Store Actions

```typescript
const store = useSharedStore()

// User management
store.setCurrentUser(user)
store.clearCurrentUser()

// Token management
store.setToken(token)

// Polling
store.updatePollingSettings({ interval: 60000 })
store.togglePolling()

// Theme
store.setTheme('dark')

// Session
store.setSessionExpired(true)

// Utilities
store.restoreFromStorage()  // Recover session on app load
store.resetAll()             // Clear everything
```

## Available Events

**User Events:**
```typescript
eventBus.emit('user:login', user)           // User logged in
eventBus.emit('user:logout', undefined)     // User logged out
```

**Order Events:**
```typescript
eventBus.emit('order:created', { orderId, merchantId, amount })
eventBus.emit('order:updated', { orderId, status, updatedAt })
```

**System Events:**
```typescript
eventBus.emit('data:refresh', { scope: 'orders' | 'products' | 'all' })
eventBus.emit('error:fatal', { message, code, timestamp })
```

## Session Recovery Pattern

In container app's `onMounted`:
```typescript
const store = useSharedStore()
store.restoreFromStorage()

if (store.isLoggedIn) {
  // User has valid session, proceed
} else {
  // Redirect to login
}
```

## Axios Integration

In microapp's axios config:
```typescript
instance.interceptors.request.use(config => {
  const store = useSharedStore()
  if (store.token) {
    config.headers.Authorization = `Bearer ${store.token}`
  }
  return config
})

instance.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      const store = useSharedStore()
      store.setSessionExpired(true)
      // Show re-login banner, don't auto-logout
    }
    return Promise.reject(error)
  }
)
```

## Key Design Decisions

1. **Centralized State** - All microapps share same user/token/settings
2. **Event-Driven Communication** - Loose coupling between microapps
3. **localStorage Persistence** - User session survives page reload
4. **Type Safety** - Full TypeScript with zero 'any' types
5. **No Memory Leaks** - Proper cleanup when listeners unsubscribe
6. **Graceful Degradation** - Errors in listeners don't break other listeners

## Storage Keys Used

- `shared:currentUser` - User info
- `shared:token` - JWT token
- `shared:pollingSettings` - Polling config
- `shared:theme` - Theme preference
- `shared:sessionExpired` - Session state

Clear all with: `localStorage.clear()`

## Full Documentation

See `SHARED_STATE_MANAGEMENT.md` for:
- Complete API reference
- 15 detailed usage examples
- Testing patterns
- Best practices
- Troubleshooting guide
- Security considerations

## Common Patterns

### Pattern: Check Login Before Accessing Data
```typescript
if (store.isLoggedIn) {
  const merchant = store.userMerchantId
  // Fetch merchant data
} else {
  // Redirect to login
}
```

### Pattern: React to User Login
```typescript
eventBus.on('user:login', (user) => {
  // Refresh dashboard, load user-specific data
  console.log(`Welcome, ${user.name}!`)
})
```

### Pattern: Handle Data Refresh Across Apps
```typescript
// Admin app broadcasts refresh
eventBus.emit('data:refresh', { scope: 'orders' })

// User app listens
eventBus.on('data:refresh', (payload) => {
  if (payload.scope === 'orders' || payload.scope === 'all') {
    // Refresh orders list
  }
})
```

## Debugging

```typescript
import { useSharedStore } from '@/stores/shared'
import { eventBus } from '@/eventBus'

// Check store state
const store = useSharedStore()
console.log(store.$state)

// Check event listeners
console.log('Total listeners:', eventBus.listenerCount())
console.log('Login listeners:', eventBus.listenerCount('user:login'))

// Clear all (for testing)
eventBus.clear()
store.resetAll()
```

## Next Steps

1. ✅ Container app ready
2. In Admin microapp:
   - Import `useSharedStore` for user management
   - Emit events when creating/updating orders
3. In User microapp:
   - Listen to events for real-time updates
   - Use `store.userMerchantId` for API calls
4. Run `npm run dev` in container to test both microapps

---

**For full details, see `SHARED_STATE_MANAGEMENT.md`**
