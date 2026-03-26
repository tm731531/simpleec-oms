# User App Implementation Guide

**Last Updated**: 2026-03-16
**Version**: 1.0.0
**Status**: Development

---

## 📋 Overview

User App is the merchant-facing frontend for SimpleEC OMS, built with **Vue 3 + TypeScript + Element Plus + Pinia**. It provides comprehensive tools for merchants to manage products, sell packs (platform listings), orders, shipments, refunds, and channel integrations.

**Key Features**:
- Multi-platform order management (Shopee, Momo, Yahoo, PChome, Cyberbiz, easystore)
- Real-time sync status tracking for cross-platform operations
- Inventory & product management
- Shipment & refund processing
- Channel configuration
- System health monitoring

**Technology Stack**:
- Frontend: Vue 3.4.0, TypeScript 5.6.0
- UI Framework: Element Plus 2.6.0
- State Management: Pinia 2.1.0
- HTTP Client: Axios 1.6.0
- Build Tool: Vite 5.4.0
- Router: Vue Router 4.3.0
- Micro-frontend: Qiankun (when deployed as sub-app)

---

## 🏗️ Directory Structure

```
user-app/
├── src/
│   ├── api/                          # API client layer
│   │   ├── index.ts                  # Axios instance + auth + common endpoints
│   │   ├── product.ts                # Product CRUD APIs
│   │   ├── order.ts                  # Order list + detail APIs
│   │   ├── sellpack.ts               # SellPack (platform listings) APIs
│   │   ├── refund.ts                 # Refund management APIs
│   │   └── channel.ts                # Channel configuration APIs
│   │
│   ├── components/                   # Reusable UI components
│   │   ├── ProductTable.vue          # Product list with pagination
│   │   ├── ProductForm.vue           # Product create/edit form
│   │   ├── SellPackTable.vue         # SellPack list by platform
│   │   ├── SellPackForm.vue          # SellPack create/edit
│   │   ├── OrderTable.vue            # Order list with filters
│   │   ├── ShipmentTable.vue         # Shipment tracking
│   │   ├── RefundTable.vue           # Refund list + actions
│   │   └── PlatformMapping.vue       # Channel↔Platform mapping UI
│   │
│   ├── views/                        # Page components
│   │   ├── LoginPage.vue             # Login form (email + password)
│   │   ├── Dashboard.vue             # Main dashboard (order stats + charts)
│   │   ├── Products.vue              # Product management page
│   │   ├── ProductPage.vue           # Product detail view
│   │   ├── SellPack.vue              # SellPack management page
│   │   ├── SellPackPage.vue          # SellPack detail view
│   │   ├── Orders.vue                # Order list view
│   │   ├── OrderPage.vue             # Order detail + shipment/refund
│   │   ├── Shipment.vue              # Shipment tracking page
│   │   ├── ShipmentPage.vue          # Shipment detail
│   │   ├── Refund.vue                # Refund list page
│   │   ├── RefundPage.vue            # Refund detail + actions
│   │   ├── ChannelPage.vue           # Channel configuration
│   │   ├── HealthDashboard.vue       # System health monitoring
│   │   ├── StatusReferencePage.vue   # Order status reference
│   │   ├── Settings.vue              # User settings
│   │   └── NotFound.vue              # 404 page
│   │
│   ├── stores/                       # Pinia state management
│   │   ├── index.ts                  # Store setup
│   │   ├── auth.ts                   # JWT + user context + multi-step login
│   │   └── order.ts                  # Order state + filters
│   │
│   ├── composables/                  # Reusable logic
│   │   └── useOrderStatuses.ts       # Order status constants + mappings
│   │
│   ├── utils/                        # Utility functions
│   │   └── syncStatus.ts             # Sync status formatting + tracking
│   │
│   ├── types/
│   │   ├── index.ts                  # All TypeScript interfaces
│   │   └── qiankun.d.ts              # Qiankun type definitions
│   │
│   ├── router/
│   │   └── index.ts                  # Router config + auth guards
│   │
│   ├── App.vue                       # Root component
│   ├── main.ts                       # Entry point
│   └── vite-env.d.ts                 # Vite environment types
│
├── package.json                      # Dependencies
├── vite.config.ts                    # Vite configuration
└── tsconfig.json                     # TypeScript configuration
```

---

## 🔐 Authentication Flow

### JWT Token Management

```typescript
// Automatic token injection in all requests
axiosInstance.interceptors.request.use((config) => {
  const token = localStorage.getItem('authToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Session expiration handling
axiosInstance.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('authToken')
      // Emit event for parent app (Qiankun)
      window.dispatchEvent(new CustomEvent('qiankun:session-expired'))
      return Promise.reject(new Error('Session expired'))
    }
    return Promise.reject(error)
  }
)
```

### Login Flow

1. User enters email + password on `/login`
2. Frontend posts to `/auth/login`
3. Backend returns `{ token, user }`
4. Token stored in `localStorage`
5. Redirect to `/dashboard`
6. All subsequent requests auto-inject token in Authorization header

**Test Credentials** (hardcoded in UI):
```
Email: admin@a00000.com
Password: pass123456
```

### Multi-Step Login (if implemented)

Auth store supports future multi-step flow:
```typescript
type LoginStep = 'initial' | 'client' | 'role' | 'org' | 'warehouse' | 'done'
// Currently only 'initial' (email/password) is used
```

---

## 🛣️ Router Configuration

| Path | Component | Auth Required | Purpose |
|------|-----------|---------------|---------|
| `/login` | LoginPage | ✗ | Login form |
| `/dashboard` | Dashboard | ✅ | Order stats, revenue, charts |
| `/product` | Products | ✅ | Product list + management |
| `/sellpack` | SellPack | ✅ | Platform listings by product |
| `/order` | OrderPage | ✅ | Order list + filtering |
| `/shipment` | ShipmentPage | ✅ | Shipment tracking |
| `/refund` | RefundPage | ✅ | Refund management |
| `/channel` | ChannelPage | ✅ | Platform channel config |
| `/health` | HealthDashboard | ✅ | System monitoring |
| `/status-reference` | StatusReferencePage | ✅ | Order status guide |
| `/settings` | Settings | ✅ | User preferences |
| `/` | → `/dashboard` | — | Default redirect |
| `/*` | NotFound | — | 404 page |

**Global Navigation Guard**: Redirects unauthenticated users to `/login` (except `/login` itself)

---

## 📊 Core Data Models

### User
```typescript
interface User {
  id: string
  email: string
  name: string
  role: string
}
```

### Product (Internal Inventory)
```typescript
interface Product {
  id: string
  sku: string
  name: string
  quantity: number
  price: number
  image: string
  description: string
  status: 'active' | 'inactive'
  createdAt: string
  updatedAt: string
}
```

### SellPack (Platform Listing)
```typescript
interface SellPack {
  id: string
  productId: string              // Links to Product
  platformId: string             // Which platform (Shopee, Momo, etc)
  platformName: string           // e.g., "Shopee", "Momo"
  quantity: number               // Available qty on this platform
  price: number                  // Platform-specific price
  image: string
  description: string
  listingStatus: 'active' | 'inactive' | 'delisting' | 'delisted'
  syncStatus: SyncStatus         // Real-time sync tracking
  createdAt: string
  updatedAt: string
  lastSyncAt?: string
}
```

### SyncStatus (Real-time Tracking)
```typescript
interface SyncStatus {
  status: 'pending' | 'syncing' | 'success' | 'failed'
  operation: string              // e.g., "SYNC_PRICE", "UPDATE_INVENTORY"
  oldValue?: string | number | boolean
  newValue?: string | number | boolean
  startTime: string
  completedTime?: string
  error?: string
  retryCount: number
}
```

### Order
```typescript
interface Order {
  // Core identification
  id: string
  merchantId: string

  // Frontend-facing fields
  orderNumber: string            // from channelOrderId
  platform: string               // resolved from Channel→Platform
  status: OrderStatus            // uppercase: PENDING, CONFIRMED, etc

  // Customer info
  buyerName: string
  buyerEmail: string
  buyerPhone: string

  // Order details
  items: OrderItem[]
  totalPrice: number
  shippingFee: number

  // Fulfillment
  shippingMethod: string
  trackingNumber?: string

  // Timestamps
  createdAt: string
  updatedAt: string
  shippedAt?: string
  completedAt?: string
}

type OrderStatus = 'PENDING' | 'CONFIRMED' | 'READY_TO_SHIP' | 'SHIPPING' | 'SHIPPED' | 'COMPLETED' | 'CANCELLED'
```

---

## 🎯 Key Pages

### Dashboard
**Purpose**: Executive summary of business metrics

**Displays**:
- Total orders count
- Pending shipment orders
- Completed orders
- Total revenue (formatted as currency)
- Optional: Charts (revenue trend, order by platform, etc)

**API Calls**:
```typescript
// Get order statistics
GET /user/orders/stats
  → { totalOrders, pendingOrders, completedOrders, totalRevenue }
```

---

### Products
**Purpose**: Internal inventory management

**Features**:
- List all products with pagination
- Create new product (name, SKU, price, quantity, image, description)
- Edit product details
- Delete product

**API Endpoints**:
```typescript
GET    /user/product?page=1&pageSize=20
POST   /user/product
PUT    /user/product/{id}
DELETE /user/product/{id}
```

**Form Validation**:
- SKU: Required, unique
- Name: Required
- Price: Required, positive number
- Quantity: Required, non-negative

---

### SellPack (Platform Listings)
**Purpose**: Manage how products are listed on each platform

**Key Concept**:
- One Product can have multiple SellPacks (one per platform)
- Each SellPack has platform-specific price, quantity, description
- Real-time sync status shows if latest changes were synced to platform

**Features**:
- View all SellPacks grouped by platform
- Create SellPack (select product + platform, set price + qty)
- Edit SellPack details
- Monitor sync status in real-time
- Bulk sync actions

**Sync Status UI**:
```
Status: Syncing (spinner)
Operation: SYNC_PRICE
Started: 2 seconds ago
Progress indicator + manual retry button
```

**API Endpoints**:
```typescript
GET    /user/sellpack?platform=shopee&page=1
POST   /user/sellpack
PUT    /user/sellpack/{id}
PATCH  /user/sellpack/{id}/sync        // Force sync to platform
DELETE /user/sellpack/{id}
```

---

### Orders
**Purpose**: View and manage customer orders

**Features**:
- List orders with filtering (status, platform, date range)
- View order detail (items, customer, address, timestamps)
- Track shipment status
- Process refunds
- Export orders (future)

**Order Statuses** (uppercase):
- `PENDING`: Awaiting payment
- `CONFIRMED`: Payment received
- `READY_TO_SHIP`: Prepared for shipment
- `SHIPPING`: In transit
- `SHIPPED`: Delivered to customer
- `COMPLETED`: Order finished
- `CANCELLED`: Order cancelled

**API Endpoints**:
```typescript
GET    /user/order?page=1&status=CONFIRMED&platform=shopee
GET    /user/order/{id}
PATCH  /user/order/{id}/status          // Change order status
POST   /user/order/{id}/shipment        // Record shipment
GET    /user/order/{id}/refund          // Get refunds for order
```

---

### Shipment Tracking
**Purpose**: Monitor and manage order fulfillment

**Features**:
- List all shipments with tracking numbers
- Update tracking information
- Bulk print labels (future)
- Integration with shipping providers

**Tracking Fields**:
- Tracking Number
- Carrier (e.g., Taiwan Post, DHL)
- Estimated Delivery Date
- Current Status

---

### Refunds
**Purpose**: Handle customer returns and refunds

**Features**:
- List refund requests
- View refund details (reason, amount, items)
- Approve/reject refunds
- Track refund status

**Refund Statuses**:
- `REQUESTED`: Customer initiated refund
- `APPROVED`: Merchant approved
- `REJECTED`: Merchant rejected
- `SHIPPED_BACK`: Items returned to warehouse
- `COMPLETED`: Refund issued

---

### Channels (Platform Integration)
**Purpose**: Configure and manage platform connections

**Managed Platforms**:
- Shopee
- Momo
- Yahoo
- PChome
- Cyberbiz
- easystore
- Shopline
- Shopify

**Features**:
- View connected channels
- Add new platform (requires API key)
- Update platform credentials
- Test connection
- View sync history per channel

**Channel Info**:
```typescript
interface Channel {
  id: string
  merchantId: string
  platformCode: string           // e.g., "shopee"
  platformName: string           // e.g., "Shopee"
  apiKey: string
  apiSecret?: string
  status: 'active' | 'inactive'
  lastSyncAt: string
  syncErrors?: string[]
}
```

---

## 🔌 API Integration

### Base URL Configuration

```typescript
function getAPIBaseURL(): string {
  // Development: direct backend
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return 'http://localhost:8082/api'
  }
  // Production: reverse proxy
  return '/api'
}
```

### Response Format Transformation

User App handles **Spring Boot Page format** from backend:

```typescript
// Backend response (Spring Page)
{
  "content": [...items],
  "pageable": { "pageNumber": 0, "pageSize": 10 },
  "totalElements": 100,
  "totalPages": 10
}

// Frontend transforms to
{
  "data": [...items],
  "pagination": {
    "page": 1,           // Convert 0-indexed to 1-indexed
    "pageSize": 10,
    "total": 100,
    "pages": 10
  }
}
```

### API Endpoints Summary

| API | Method | Endpoint | Purpose |
|-----|--------|----------|---------|
| Auth | POST | `/auth/login` | Authenticate |
| Auth | GET | `/auth/me` | Get current user |
| Auth | POST | `/auth/logout` | Logout |
| Product | GET | `/user/product` | List products |
| Product | POST | `/user/product` | Create product |
| Product | PUT | `/user/product/{id}` | Update product |
| Product | DELETE | `/user/product/{id}` | Delete product |
| SellPack | GET | `/user/sellpack` | List sell packs |
| SellPack | POST | `/user/sellpack` | Create sell pack |
| SellPack | PATCH | `/user/sellpack/{id}/sync` | Sync to platform |
| Order | GET | `/user/order` | List orders |
| Order | GET | `/user/order/{id}` | Get order detail |
| Refund | GET | `/user/refund` | List refunds |
| Channel | GET | `/user/channel` | List channels |

---

## 📱 Pinia State Management

### Auth Store (`stores/auth.ts`)

**State**:
```typescript
const token = ref<string | null>(null)        // JWT token
const user = ref<User | null>(null)           // Logged-in user
const isAuthenticated = computed(() => !!token.value)

// Multi-step login
const loginStep = ref<LoginStep>('initial')   // initial | client | role | org | warehouse | done
const selectionContext = ref<SelectionContext>({})  // clientId, roleId, etc
```

**Actions**:
- `login(credentials)`: Email + password authentication
- `logout()`: Clear token and redirect
- `restoreToken()`: Recover token from localStorage on app init
- `getCurrentUser()`: Fetch user profile from backend

**Usage**:
```typescript
const authStore = useAuthStore()

// Check if logged in
if (authStore.isAuthenticated) {
  // Access user info
  console.log(authStore.user?.name)
}

// Login
await authStore.login({ email, password })

// Logout
await authStore.logout()
```

### Order Store (`stores/order.ts`)

**State** (if implemented):
```typescript
const orders = ref<Order[]>([])
const filters = ref({
  status: null,
  platform: null,
  dateFrom: null,
  dateTo: null,
  page: 1,
  pageSize: 20
})
const loading = ref(false)
```

**Actions** (if implemented):
- `fetchOrders(filters)`: Get paginated order list
- `updateFilters(newFilters)`: Apply filters
- `getOrder(id)`: Fetch single order detail

---

## 🎨 UI Components

### ProductTable
- Paginated list of products
- Edit button → opens ProductForm modal
- Delete button with confirmation
- Search/filter support (if implemented)

### ProductForm
- Modal form for create/edit
- Fields: SKU, Name, Price, Quantity, Image, Description, Status
- Validation rules
- Submit → API call → table refresh

### OrderTable
- Paginated list of orders
- Status badge (color-coded)
- Platform indicator
- Date range filter
- Order detail link

### RefundTable
- Refund list with status
- Approve/Reject buttons
- Refund detail modal
- Amount tracking

---

## 🔗 Qiankun Micro-Frontend Integration

User App can run as:
1. **Standalone Vue app** (development, `/user` path)
2. **Qiankun sub-application** (production, micro-frontend architecture)

### Qiankun Detection

```typescript
// In router/index.ts
const history = window.__POWERED_BY_QIANKUN__
  ? createMemoryHistory(import.meta.env.BASE_URL)
  : createWebHistory(import.meta.env.BASE_URL)
```

### Session Expiration (Qiankun)

When backend returns 401:
```typescript
// Emit to parent app (main application frame)
window.dispatchEvent(new CustomEvent('qiankun:session-expired', {
  detail: { reason: 'unauthorized' }
}))
```

Parent app listens and redirects to login page.

---

## ⚠️ Known Issues

### 1. **No Form Validation**
- **Issue**: Forms accept any input
- **Missing**: Email format, required fields, number ranges
- **Impact**: Invalid data sent to backend
- **Solution**: Add Element Plus form validation rules

### 2. **Sync Status Polling**
- **Issue**: Real-time sync status may not be reflected immediately
- **Current**: User must refresh to see latest status
- **Solution**: Implement WebSocket or polling interval (e.g., 3 seconds)

### 3. **No Error Recovery**
- **Issue**: Failed API calls show ElMessage but no retry option
- **Missing**: Retry button for failed operations
- **Solution**: Add retry logic + offline queue

### 4. **Hardcoded Test Credentials**
- **Issue**: Login page shows demo credentials (admin@a00000.com / pass123456)
- **Security**: Should be removed or hidden in production
- **Solution**: Remove before production deployment

### 5. **Limited Chart Support**
- **Issue**: Dashboard lacks visualization (revenue trends, order by platform)
- **Current**: Only static stat cards
- **Solution**: Integrate ECharts or similar for charts

### 6. **No Bulk Actions**
- **Issue**: Can only edit/delete one item at a time
- **Missing**: Bulk price update, bulk listing status change
- **Solution**: Add checkboxes + bulk action buttons

### 7. **Pagination State Not Preserved**
- **Issue**: Navigating back to a page resets pagination to page 1
- **Solution**: Store pagination in URL query params or Pinia store

### 8. **No Export Feature**
- **Issue**: Can't export orders/products to Excel/CSV
- **Solution**: Implement xlsx export

---

## 📈 Improvement Roadmap

### Phase 1: Critical Fixes
- [ ] Add form validation (email, required fields, phone format)
- [ ] Add error handling with retry buttons
- [ ] Remove hardcoded test credentials
- [ ] Implement sync status polling (WebSocket)

### Phase 2: Features
- [ ] Add search/filter to all tables
- [ ] Implement bulk actions
- [ ] Add charts to dashboard
- [ ] Implement export (Excel, CSV)
- [ ] Add audit log page

### Phase 3: Polish
- [ ] Implement offline mode
- [ ] Add keyboard shortcuts
- [ ] Improve mobile responsiveness
- [ ] Add dark mode
- [ ] Add notification center

### Phase 4: Performance
- [ ] Lazy load pages
- [ ] Implement virtual scrolling for large tables
- [ ] Cache frequently accessed data
- [ ] Optimize bundle size

---

## 🔗 API Contract Alignment

**Critical Checks Before Deployment**:
- [ ] All `/user/*` endpoints live on backend
- [ ] JWT authentication working (401 on expired token)
- [ ] Order status values match backend (PENDING, CONFIRMED, etc)
- [ ] SellPack sync status endpoint implemented
- [ ] Channel sync history endpoint implemented
- [ ] CORS configured for user app domain
- [ ] Qiankun integration tested (if applicable)

---

## 🧪 Testing Checklist

### Manual Testing
- [ ] Login with test credentials
- [ ] Create product → verify in list
- [ ] Edit product → verify changes
- [ ] Create SellPack → verify sync status
- [ ] Filter orders by status/platform
- [ ] Create refund → verify status tracking
- [ ] Session expiration (401 response)
- [ ] Responsive design (mobile, tablet, desktop)

### Integration Testing
- [ ] Product create → appears in SellPack selection
- [ ] Order sync → shipment status updates
- [ ] Platform sync → real-time status tracking
- [ ] Channel disconnect → order/product operations fail gracefully

---

## 📚 Related Documentation

- **Backend API**: `docs/2-API/USER_API.md`
- **Admin App**: `docs/5-FRONTEND/ADMIN_APP_IMPLEMENTATION.md`
- **Architecture**: `docs/1-ARCHITECTURE/DESIGN_v2.md`
- **Deployment**: `docs/6-OPERATIONS/QUICK_REDEPLOY_GUIDE.md`
- **Qiankun Setup**: `docs/6-OPERATIONS/QIANKUN_SETUP.md` (if applicable)

---

## 👥 Maintenance Notes

**Last Reviewed**: 2026-02-25
**Last Updated**: 2026-03-16
**Owner**: Frontend Team

**Key Contacts**:
- Frontend Development: @team-lead
- Backend API Support: @backend-engineer
- QA/Testing: @qa-engineer
- DevOps/Deployment: @ops-team

---

*This document is the source of truth for User App implementation. Update after each major change.*
