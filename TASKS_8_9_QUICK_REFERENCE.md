# Tasks 8 & 9: Quick Reference Guide

## File Locations

### New Components
```
src/views/
  ├── OrderPage.vue (307 lines)
  ├── ShipmentPage.vue (232 lines)
  ├── RefundPage.vue (283 lines)
  └── ChannelPage.vue (515 lines)

src/components/
  ├── OrderTable.vue (365 lines)
  ├── ShipmentTable.vue (285 lines)
  └── RefundTable.vue (380 lines)
```

### Modified Files
```
src/router/index.ts (route definitions updated)
src/views/Orders.vue (wrapper for OrderPage)
src/views/Shipment.vue (wrapper for ShipmentPage)
src/views/Refund.vue (wrapper for RefundPage)
src/views/Settings.vue (wrapper for ChannelPage)
```

## Quick Access Routes

| Route | Component | Purpose |
|-------|-----------|---------|
| `/order` | OrderPage | Comprehensive order management |
| `/shipment` | ShipmentPage | Pending shipment management |
| `/refund` | RefundPage | Refund approval workflow |
| `/channel` | ChannelPage | Connected platform overview |

## Component APIs

### OrderPage Props
- `refreshTrigger?: number` (passed to OrderTable)

### OrderTable Events
```typescript
@ship-order="handleShipOrder(order)"
@cancel-order="handleCancelOrder(order)"
@view-details="handleViewDetails(order)"
```

### ShipmentPage Props
- `refreshTrigger?: number` (passed to ShipmentTable)

### ShipmentTable Events
```typescript
@shipped="handleShipped(order)"
@view-details="handleViewDetails(order)"
```

### RefundPage Props
- `refreshTrigger?: number` (passed to RefundTable)

### RefundTable Events
```typescript
@approved="handleApproved(refund)"
@rejected="handleRejected(refund)"
@view-details="handleViewDetails(refund)"
```

## Status Mapping Reference

### Order Status
```
pending    → "待支付" (Awaiting Payment) → warning badge
confirmed  → "待出貨" (Ready to Ship) → warning badge
shipped    → "已出貨" (Shipped) → info badge
completed  → "已完成" (Completed) → success badge
cancelled  → "已取消" (Cancelled) → danger badge
```

### Sync Status
```
pending  → "待同步" (Pending) → info badge
syncing  → "同步中" (Syncing) → warning badge
success  → "已同步" (Synced) → success badge
failed   → "同步失敗" (Failed) → danger badge
```

### Refund Status
```
pending  → "待處理" (Pending) → warning badge
syncing  → "同步中" (Syncing) → warning badge
success  → "已完成" (Completed) → success badge
failed   → "失敗" (Failed) → danger badge
```

## API Calls

### Order API
```typescript
import { listOrders, ship, cancel } from '../api/order'

// List orders with optional filters
const response = await listOrders(page, pageSize, platform?, status?)

// Ship order with optional tracking number
await ship(orderId, trackingNumber?)

// Cancel order with optional reason
await cancel(orderId, reason?)
```

### Refund API
```typescript
import { listRefunds, approve, reject } from '../api/refund'

// List refunds
const response = await listRefunds(page, pageSize, orderId?)

// Approve refund
await approve(refundId)

// Reject refund (reason required)
await reject(refundId, reason)
```

### Channel API
```typescript
import { listChannels } from '../api/channel'

// List all connected channels
const channels = await listChannels()
```

## Utility Functions

### Currency Formatting
```typescript
formatCurrency(value: number): string
// Example: 1234 → "NT$ 1,234"
```

### Date Formatting
```typescript
formatDate(dateString: string): string
// Example: "2026-02-21T14:30:45Z" → "2026年2月21日 14:30"
```

## Pagination Constants
```typescript
pageSize: 10 | 20 | 50  // Available options
currentPage: 1          // 1-indexed
totalCount: number      // Total records
```

## Dialog Patterns

### Order Ship Dialog
```
- Order Number (read-only)
- Amount (read-only)
- Tracking Number Input (optional)
- [Cancel] [Confirm Ship]
```

### Order Cancel Dialog
```
- Order Number (read-only)
- Cancellation Reason (textarea, optional)
- [Cancel] [Confirm Cancel]
```

### Refund Approve Dialog
```
- Order ID (read-only)
- Amount (read-only)
- Reason (read-only)
- Confirmation Message
- [Cancel] [Approve]
```

### Refund Reject Dialog
```
- Order ID (read-only)
- Amount (read-only)
- Rejection Reason (textarea, REQUIRED)
- Form Validation
- [Cancel] [Reject]
```

### Channel Management Dialog
```
- Channel Name (read-only)
- Channel Code (read-only)
- Connection Status (read-only)
- Statistics Grid
- [Close]
```

## Responsive Breakpoints
```
Desktop:  > 768px  (full controls, multi-column)
Mobile:   ≤ 768px  (single column, stacked)
```

## Channel Icons (Emoji-based)
```typescript
shopee   → 🛒 Shopee (red gradient)
momo     → 🏪 MOMO (orange gradient)
yahoo    → 📱 Yahoo (gold gradient)
pchome   → 🎁 PChome (blue gradient)
cyberbiz → 💼 Cyberbiz (teal gradient)
easystore → 🏬 easyStore (purple gradient)
```

## Common Patterns

### Refresh Data Pattern
```typescript
const refreshTrigger = ref(0)

const handleAction = (item) => {
  ElMessage.success('Action completed')
  emit('actionCompleted', item)
  refreshTrigger.value++  // Trigger refresh
}
```

### Dialog with Form Pattern
```typescript
const currentItem = ref<Item | null>(null)
const dialogVisible = ref(false)
const formValue = ref('')

const handleAction = (item) => {
  currentItem.value = item
  formValue.value = ''
  dialogVisible.value = true
}

const confirmAction = async () => {
  if (!formValue.value.trim()) {
    ElMessage.warning('Field required')
    return
  }
  await API.action(currentItem.value.id, formValue.value)
  dialogVisible.value = false
}
```

### Dual API Call Pattern (ShipmentTable)
```typescript
const fetchShipments = async () => {
  // Fetch both pending and confirmed
  const [pending, confirmed] = await Promise.all([
    listOrders(page, pageSize, platform, 'pending'),
    listOrders(page, pageSize, platform, 'confirmed')
  ])

  // Combine and deduplicate
  shipments.value = deduplicateById([
    ...(pending.data || []),
    ...(confirmed.data || [])
  ])
}
```

## Error Handling

```typescript
try {
  await API.action(id, data)
  ElMessage.success('Success message')
  // Update state
} catch (error) {
  console.error('Error:', error)
  ElMessage.error('Error message')
  // Don't update state on error
}
```

## TypeScript Imports

```typescript
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { Order, Refund, Platform, OrderStatus } from '../types'
import { listOrders, ship, cancel } from '../api/order'
import { listRefunds, approve, reject } from '../api/refund'
import { listChannels } from '../api/channel'
```

## Performance Tips

1. Use pagination to limit displayed records
2. Debounce search input with @keyup.enter
3. Lazy-load dialogs (don't render until visible)
4. Memoize computed properties if expensive
5. Use loading state during API calls

## Common Issues & Solutions

### Issue: Dialog not closing after action
**Solution**: Set `dialogVisible = false` in try block before emit

### Issue: Refresh not working
**Solution**: Check `refreshTrigger` is passed as prop and watched in child

### Issue: Status filtering not working
**Solution**: Verify status value matches enum (lowercase: pending, confirmed, etc.)

### Issue: Pagination not updating
**Solution**: Reset `currentPage = 1` when filters change

### Issue: Double-clicking ship causes duplicate requests
**Solution**: Add loading state check or disable button during request

## Debugging

### Enable Vue DevTools
- Install Vue DevTools browser extension
- Inspect component state and props

### Check Console
- API responses in Network tab
- Error messages in Console tab

### Log State Changes
```typescript
watch(() => items.value, (newVal) => {
  console.log('Items updated:', newVal)
}, { deep: true })
```

## File Statistics

| File | Lines | Components |
|------|-------|-----------|
| OrderPage.vue | 307 | 1 (page) |
| ShipmentPage.vue | 232 | 1 (page) |
| RefundPage.vue | 283 | 1 (page) |
| ChannelPage.vue | 515 | 1 (page) |
| OrderTable.vue | 365 | 1 (table) |
| ShipmentTable.vue | 285 | 1 (table) |
| RefundTable.vue | 380 | 1 (table) |
| **Total** | **2,367** | **7** |

## Documentation References

For more details, see:
1. **TASKS_8_9_IMPLEMENTATION_SUMMARY.md** - Features, data flow, and architecture
2. **TASKS_8_9_TECHNICAL_DETAILS.md** - Implementation patterns and technical specs

## Testing Checklist

- [x] All pages load without errors
- [x] All filters work correctly
- [x] All dialogs open/close properly
- [x] All API calls complete successfully
- [x] All status badges display correctly
- [x] Pagination works on all tables
- [x] Responsive layout works on mobile
- [x] Empty states display when no data
- [x] Loading states display during fetch
- [x] Error messages display on failure

## Deployment Checklist

- [x] TypeScript compiles without errors
- [x] All imports are correct
- [x] All API endpoints are available
- [x] Environment variables are set
- [x] HTTPS is enabled in production
- [x] CORS is configured for APIs
- [x] Error tracking is enabled (optional)

## Support & Questions

For implementation questions, refer to:
1. Similar components in the codebase (e.g., ProductPage, SellPackPage)
2. Element Plus documentation for UI components
3. Vue 3 Composition API documentation
4. TypeScript handbook for type definitions

---

**Last Updated**: 2026-02-21
**Version**: 1.0
**Status**: Production Ready
