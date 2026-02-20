# Shared State Management & Event Bus Documentation

## Overview

This document describes the shared state management infrastructure for the SimpleEC OMS qiankun micro-frontend container. It enables:

1. **User Authentication Management** - JWT tokens, user info, session handling
2. **Global Polling Configuration** - Auto-refresh settings across all microapps
3. **Theme Preferences** - Light/dark theme with persistence
4. **Inter-Microapp Communication** - Event bus for loose coupling

## Architecture

### Three-Layer Pattern

```
Container (main app)
├── Pinia Store (Shared State)
├── Event Bus (Event Emitter)
└── Microapps
    ├── Admin Microapp (reads/writes shared state)
    ├── User Microapp (reads/writes shared state)
    └── Future Microapps (same pattern)
```

## Files Created

### 1. `src/types/index.ts`
Defines TypeScript interfaces for type safety:
- `User` - User information structure
- `PollingSettings` - Polling configuration
- `Theme` - Theme type ('light' | 'dark')
- `EventMap` - Event payload type mappings
- `EventCallback` - Event listener type

### 2. `src/stores/shared.ts`
Pinia store providing:
- **State**: currentUser, pollingSettings, theme, token, sessionExpired
- **Getters**: isLoggedIn, userRole, pollingInterval, isPollingEnabled, userMerchantId, userWarehouseId
- **Actions**: setCurrentUser, clearCurrentUser, updatePollingSettings, togglePolling, setTheme, setToken, setSessionExpired, restoreFromStorage, resetAll

**Key Features**:
- Full TypeScript support with strict typing
- Automatic localStorage persistence
- Session recovery on app reload
- Memory-safe operations

### 3. `src/eventBus.ts`
Type-safe event emitter class:
- `on(event, callback)` - Subscribe to events
- `off(event, callback)` - Unsubscribe from events
- `once(event, callback)` - Subscribe once
- `emit(event, payload)` - Emit events synchronously
- `emitAsync(event, payload)` - Emit events asynchronously
- `clear(event?)` - Remove listeners
- `listenerCount(event?)` - Get listener counts

**Key Features**:
- Type-safe event names and payloads
- No memory leaks (proper cleanup)
- Error handling (errors don't stop other listeners)
- Debugging support (listener counts)
- Both sync and async emission

### 4. `src/main.ts`
Already configured with Pinia (no changes needed):
```typescript
import { createPinia } from 'pinia'
app.use(createPinia())
```

## Supported Events

### User Events
```typescript
'user:login' → User
'user:logout' → void
```

### Order Events
```typescript
'order:created' → { orderId, merchantId, amount }
'order:updated' → { orderId, status, updatedAt }
```

### System Events
```typescript
'data:refresh' → { scope?: 'orders' | 'products' | 'all' }
'error:fatal' → { message, code?, timestamp }
```

## Usage Patterns

### Pattern 1: Login/Logout Flow

**In Admin/User Microapp - Login Component**:
```typescript
import { useSharedStore } from '@/stores/shared'
import { eventBus } from '@/eventBus'

export default defineComponent({
  setup() {
    const sharedStore = useSharedStore()

    async function handleLogin(email: string, password: string) {
      const response = await axios.post('/api/auth/login', {
        email,
        password
      })

      // Store token and user in shared state
      sharedStore.setToken(response.data.token)
      sharedStore.setCurrentUser(response.data.user)

      // Emit event for other microapps
      eventBus.emit('user:login', response.data.user)
    }

    function handleLogout() {
      sharedStore.clearCurrentUser()
      eventBus.emit('user:logout', undefined)
    }

    return { handleLogin, handleLogout }
  }
})
```

### Pattern 2: Access User Info in Any Component

```typescript
import { useSharedStore } from '@/stores/shared'
import { computed } from 'vue'

export default defineComponent({
  setup() {
    const sharedStore = useSharedStore()

    // All reactive - component updates when user logs in/out
    const isLoggedIn = computed(() => sharedStore.isLoggedIn)
    const userRole = computed(() => sharedStore.userRole)
    const userName = computed(() => sharedStore.currentUser?.name)

    return {
      isLoggedIn,
      userRole,
      userName
    }
  }
})
```

### Pattern 3: Listen to Events

```typescript
import { eventBus } from '@/eventBus'
import { onMounted, onBeforeUnmount } from 'vue'

export default defineComponent({
  setup() {
    onMounted(() => {
      // Subscribe to order updates from Admin microapp
      const unsubscribe = eventBus.on('order:updated', (payload) => {
        console.log('Order updated:', payload)
        // Refresh UI, update list, etc.
      })

      // Cleanup when component unmounts
      onBeforeUnmount(() => {
        unsubscribe()
      })
    })
  }
})
```

### Pattern 4: Emit Events from One Microapp

```typescript
// In Admin microapp after creating order
import { eventBus } from '@/eventBus'

async function createOrder(orderData) {
  const response = await axios.post('/api/orders', orderData)

  // Notify User microapp about new order
  eventBus.emit('order:created', {
    orderId: response.data.id,
    merchantId: response.data.merchantId,
    amount: response.data.totalAmount
  })
}
```

### Pattern 5: Session Expiration Handling

**In Container App**:
```typescript
import { useSharedStore } from '@/stores/shared'

// In mounted hook
const sharedStore = useSharedStore()
sharedStore.restoreFromStorage()

// Setup axios interceptor in microapp
instance.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      sharedStore.setSessionExpired(true)
      // Show re-login banner
    }
    return Promise.reject(error)
  }
)
```

**In Header Component**:
```typescript
import { useSharedStore } from '@/stores/shared'

const sharedStore = useSharedStore()

const showSessionWarning = computed(() => sharedStore.sessionExpired)

async function handleReLogin() {
  // After successful re-login
  sharedStore.setSessionExpired(false)
}
```

### Pattern 6: Polling Configuration

```typescript
import { useSharedStore } from '@/stores/shared'

const sharedStore = useSharedStore()

// Update polling settings
sharedStore.updatePollingSettings({
  enabled: true,
  interval: 60000 // 60 seconds
})

// Toggle polling on/off
sharedStore.togglePolling()

// Use in data refresh logic
const refreshData = () => {
  if (sharedStore.isPollingEnabled) {
    // Fetch data with interval
    const interval = setInterval(() => {
      // Auto-refresh logic
    }, sharedStore.pollingInterval)
  }
}
```

## localStorage Persistence

The shared store automatically persists the following to localStorage:

| Key | Purpose |
|-----|---------|
| `shared:currentUser` | User information for session recovery |
| `shared:token` | JWT token for auth header injection |
| `shared:pollingSettings` | Polling preferences |
| `shared:theme` | Theme preference |
| `shared:sessionExpired` | Session state flag |

On app reload, call:
```typescript
const sharedStore = useSharedStore()
sharedStore.restoreFromStorage()
```

## Axios Integration Pattern

```typescript
// In microapp's main.ts or axios config
import axios from 'axios'
import { useSharedStore } from '@/stores/shared'

const instance = axios.create()

// Add auth interceptor
instance.interceptors.request.use(config => {
  const sharedStore = useSharedStore()

  if (sharedStore.token) {
    config.headers.Authorization = `Bearer ${sharedStore.token}`
  }

  return config
})

// Handle 401 responses
instance.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      const sharedStore = useSharedStore()
      sharedStore.setSessionExpired(true)

      // Don't auto-logout, let user see banner
      // Banner shows re-login prompt
    }
    return Promise.reject(error)
  }
)

export default instance
```

## Event Bus Memory Leak Prevention

The event bus is designed to prevent memory leaks:

1. **Proper Cleanup**: Always unsubscribe when component unmounts
   ```typescript
   const unsubscribe = eventBus.on('event:name', callback)
   onBeforeUnmount(() => unsubscribe())
   ```

2. **Auto-Cleanup**: Empty listener sets are automatically removed
   - Prevents Map from growing indefinitely
   - Returns to empty state when last listener unsubscribes

3. **Clear on Unmount**: Microapp can clear all listeners on unmount
   ```typescript
   // In microapp's beforeUnmount hook
   eventBus.clear('order:created')
   ```

## Error Handling

### Event Bus Error Handling
- Errors in listeners don't prevent other listeners from executing
- Errors are logged to console but don't break the event chain
- Async errors are caught and logged

### Store Error Handling
- localStorage parsing errors are caught and logged
- State remains valid even if localStorage is corrupted
- Invalid data is simply ignored (defaults used)

## TypeScript Strict Mode

All code follows TypeScript strict mode:
- ✅ No `any` types
- ✅ Full type coverage
- ✅ Null safety checks
- ✅ Proper error handling
- ✅ Build succeeds with zero errors

## Testing

### Unit Tests for Event Bus

```typescript
import { eventBus } from '@/eventBus'

describe('EventBus', () => {
  beforeEach(() => {
    eventBus.clear()
  })

  it('should emit and receive events', (done) => {
    eventBus.on('test:event', (payload) => {
      expect(payload.message).toBe('hello')
      done()
    })

    eventBus.emit('test:event', { message: 'hello' })
  })

  it('should support once listener', (done) => {
    let count = 0

    eventBus.once('test:event', () => {
      count++
    })

    eventBus.emit('test:event', undefined)
    eventBus.emit('test:event', undefined)

    expect(count).toBe(1)
    done()
  })

  it('should cleanup listeners', () => {
    const callback = () => {}
    eventBus.on('test:event', callback)
    expect(eventBus.listenerCount('test:event')).toBe(1)

    eventBus.off('test:event', callback)
    expect(eventBus.listenerCount('test:event')).toBe(0)
  })
})
```

### Store Tests

```typescript
import { useSharedStore } from '@/stores/shared'
import { setActivePinia, createPinia } from 'pinia'

describe('SharedStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('should set and get user', () => {
    const store = useSharedStore()
    const user = {
      id: '1',
      email: 'test@example.com',
      name: 'Test',
      role: 'admin' as const,
      merchantId: 'merchant_1'
    }

    store.setCurrentUser(user)
    expect(store.isLoggedIn).toBe(true)
    expect(store.userRole).toBe('admin')
  })

  it('should persist to localStorage', () => {
    const store = useSharedStore()
    const user = {
      id: '1',
      email: 'test@example.com',
      name: 'Test',
      role: 'user' as const,
      merchantId: 'merchant_1'
    }

    store.setCurrentUser(user)
    expect(localStorage.getItem('shared:currentUser')).toBeTruthy()
  })

  it('should restore from localStorage', () => {
    const user = {
      id: '1',
      email: 'test@example.com',
      name: 'Test',
      role: 'admin' as const,
      merchantId: 'merchant_1'
    }

    localStorage.setItem('shared:currentUser', JSON.stringify(user))

    const store = useSharedStore()
    store.restoreFromStorage()

    expect(store.currentUser).toEqual(user)
    expect(store.isLoggedIn).toBe(true)
  })
})
```

## Best Practices

1. **Always use computed() for reactive UI binding**
   ```typescript
   const isLoggedIn = computed(() => sharedStore.isLoggedIn)
   ```

2. **Unsubscribe from events in onBeforeUnmount**
   ```typescript
   const unsubscribe = eventBus.on('event', handler)
   onBeforeUnmount(() => unsubscribe())
   ```

3. **Never modify state directly in components**
   ```typescript
   // Wrong
   sharedStore.currentUser!.name = 'New Name'

   // Right
   sharedStore.setCurrentUser({ ...sharedStore.currentUser!, name: 'New Name' })
   ```

4. **Use token from shared store in axios interceptors**
   ```typescript
   const token = sharedStore.token // Reactive, always current
   ```

5. **Check isLoggedIn instead of currentUser !== null**
   ```typescript
   if (sharedStore.isLoggedIn) { /* ... */ }
   ```

6. **Handle session expiration gracefully**
   - Never auto-logout on 401
   - Show banner for user to re-login
   - Preserve UI state

## Migration from Old Patterns

### Before (without shared state)
```typescript
// In each microapp's own store
const userStore = useUserStore()
const authToken = ref(null)
```

### After (with shared state)
```typescript
// Across all microapps
const sharedStore = useSharedStore()
const token = computed(() => sharedStore.token)
```

## Performance Considerations

1. **Event Bus** - O(1) emit time, linear in listener count
2. **Pinia Store** - Reactivity is automatic, no manual updates needed
3. **localStorage** - Minimal (only on auth changes)
4. **Memory** - Cleaned up when listeners unsubscribe

## Security Considerations

1. **Token Storage** - JWT stored in localStorage for persistence
   - Browser can access (no secure httpOnly due to SPA nature)
   - Cleared on logout
   - Cleared on session expiration

2. **PII Handling** - User info in shared store
   - Available to all microapps (by design)
   - localStorage contains user profile info
   - Clear on logout

3. **HTTPS Required** - All production deployments must use HTTPS
   - Token transmitted in Authorization header
   - Prevents man-in-the-middle attacks

## Debugging

### Check Store State
```typescript
const store = useSharedStore()
console.log(store.$state)
```

### Check Event Listeners
```typescript
import { eventBus } from '@/eventBus'

console.log('Total listeners:', eventBus.listenerCount())
console.log('Order listeners:', eventBus.listenerCount('order:created'))
```

### Dev Tools
- Pinia DevTools shows store mutations
- Vue DevTools shows component state
- Browser DevTools shows localStorage

## Troubleshooting

### Events not received?
1. Check if event name matches exactly (case-sensitive)
2. Ensure listener is subscribed before emit
3. Check console for errors in listener callback
4. Use `eventBus.listenerCount()` to debug

### State not persisting?
1. Check localStorage in DevTools
2. Call `restoreFromStorage()` after app init
3. Check for localStorage quota issues
4. Check browser privacy settings

### Null reference errors?
1. Always check `isLoggedIn` before accessing user
2. Use optional chaining: `sharedStore.currentUser?.name`
3. Use nullish coalescing: `userRole ?? 'guest'`

## Future Enhancements

Possible improvements:
1. Add localStorage encryption for sensitive data
2. Add Pinia persistence plugin for automatic sync
3. Add event history/replay for debugging
4. Add listener execution statistics
5. Add message queue for events during startup
6. Add cross-tab synchronization via `storage` event
