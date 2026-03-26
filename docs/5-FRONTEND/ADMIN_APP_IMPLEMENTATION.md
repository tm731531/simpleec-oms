# Admin App Implementation Guide

**Last Updated**: 2026-03-16
**Version**: 1.0.0
**Status**: Development

---

## 📋 Overview

Admin App is the administrative backend interface for SimpleEC OMS, built with **Vue 3 + TypeScript + Element Plus**. It provides comprehensive management interfaces for merchants, accounts, platforms, and system monitoring.

**Technology Stack**:
- Frontend: Vue 3.4.0, TypeScript 5.3.0
- UI Framework: Element Plus 2.4.0
- State Management: Pinia 2.1.0
- HTTP Client: Axios 1.6.0
- Build Tool: Vite 5.0.0
- Router: Vue Router 4.2.0

---

## 🏗️ Directory Structure

```
admin-app/
├── src/
│   ├── api/                      # API client layer
│   │   ├── index.ts              # Axios instance + interceptors
│   │   ├── merchant.ts           # Merchant CRUD APIs
│   │   ├── account.ts            # Account CRUD + password reset
│   │   ├── platform.ts           # Platform CRUD APIs
│   │   └── stats.ts              # Statistics/Dashboard APIs
│   │
│   ├── components/               # Reusable UI components
│   │   ├── MerchantTable.vue     # Merchant list with pagination
│   │   ├── MerchantForm.vue      # Merchant create/edit form
│   │   ├── AccountTable.vue      # Account list with pagination
│   │   ├── AccountForm.vue       # Account create/edit/password reset
│   │   ├── PlatformTable.vue     # Platform list with pagination
│   │   └── PlatformForm.vue      # Platform create/edit form
│   │
│   ├── views/                    # Page components
│   │   ├── DashboardPage.vue     # System dashboard (4 stats cards)
│   │   ├── MerchantPage.vue      # Merchant management page
│   │   ├── AccountPage.vue       # Account management page
│   │   ├── PlatformPage.vue      # Platform management page
│   │   └── MonitorPage.vue       # System monitoring page
│   │
│   ├── router/
│   │   └── index.ts              # Router configuration
│   │
│   ├── types.ts                  # TypeScript interfaces
│   ├── App.vue                   # Root component (sidebar + layout)
│   └── main.ts                   # Entry point
│
├── package.json                  # Dependencies
├── vite.config.ts                # Vite configuration
└── tsconfig.json                 # TypeScript configuration
```

---

## 🔌 API Integration

### Base URL Configuration

```typescript
// src/api/index.ts
function getAPIBaseURL(): string {
  // Localhost: Direct backend URL
  if (hostname === 'localhost' || hostname === '127.0.0.1') {
    return 'http://localhost:8082'
  }
  // Remote domains: Use relative /api (reverse proxy)
  return '/api'
}
```

**Authorization**: JWT Bearer token in `Authorization` header (auto-injected by request interceptor)

### API Endpoints

#### Merchant APIs
| Method | Endpoint | Function |
|--------|----------|----------|
| GET | `/admin/merchant?page=1&pageSize=20` | List merchants (paginated) |
| GET | `/admin/merchant/{id}` | Get merchant by ID |
| POST | `/admin/merchant` | Create new merchant |
| PUT | `/admin/merchant/{id}` | Update merchant |
| DELETE | `/admin/merchant/{id}` | Delete merchant |

#### Account APIs
| Method | Endpoint | Function |
|--------|----------|----------|
| GET | `/admin/account?page=1&pageSize=20&merchant_id=?` | List accounts (with optional merchant filter) |
| GET | `/admin/account/{id}` | Get account by ID |
| POST | `/admin/account` | Create new account |
| PUT | `/admin/account/{id}` | Update account |
| POST | `/admin/account/{id}/reset-password` | Reset account password |
| DELETE | `/admin/account/{id}` | Delete account |

#### Platform APIs
| Method | Endpoint | Function |
|--------|----------|----------|
| GET | `/admin/platform?page=1&pageSize=20` | List platforms (paginated) |
| GET | `/admin/platform/{id}` | Get platform by ID |
| POST | `/admin/platform` | Create new platform |
| PUT | `/admin/platform/{id}` | Update platform |
| DELETE | `/admin/platform/{id}` | Delete platform |

---

## 📊 Core Components

### 1. Layout (App.vue)

**Structure**:
```
┌─────────────────────────────────────┐
│    Header: "SimpleEC OMS - 管理後台"   │
├──────────┬──────────────────────────┤
│ Sidebar  │                          │
│ (Menu)   │   Main Content           │
│          │   (RouterView)           │
│          │                          │
└──────────┴──────────────────────────┘
```

**Menu Items**:
- 儀表板 (Dashboard) → `/`
- 商家管理 (Merchant) → `/merchant`
- 帳戶管理 (Account) → `/account`
- 通路管理 (Platform) → `/platform`
- 系統監控 (Monitor) → `/monitor`

### 2. Router Configuration

```typescript
// src/router/index.ts
const routes = [
  { path: '/', name: 'Dashboard', component: DashboardPage },
  { path: '/merchant', name: 'Merchant', component: MerchantPage },
  { path: '/account', name: 'Account', component: AccountPage },
  { path: '/platform', name: 'Platform', component: PlatformPage },
  { path: '/monitor', name: 'Monitor', component: MonitorPage }
]

// Base path: /admin/
createRouter({ history: createWebHashHistory('/admin/'), routes })
```

### 3. Data Models

```typescript
// src/types.ts

interface Merchant {
  id: string
  merchant_name: string
  merchant_email: string
  merchant_phone_number: string
  tax_id_number: string
  address_city: string
  address_region: string
  address_country: string
  address_zip: string
  address_line1: string
  address_line2: string
  address_phone_number: string
  vip_level: number
  user_local_time_zone: string
  payer_name: string
  payer_email: string
  payer_phone_number: string
  status: string
  created_at: string
  updated_at: string
}

interface Account {
  id: string
  account_name: string
  account_email: string
  account_password?: string
  account_tel: string
  is_main_account: boolean
  access_level: number
  merchant_id: string
  status: string
  totp_secret?: string
  created_at: string
  updated_at: string
}

interface Platform {
  id: string
  platform_name: string
  platform_code: string
  merchant_id: string
  api_key: string
  api_secret?: string
  status: string
  created_at: string
  updated_at: string
}
```

---

## 🔄 Page Components

### DashboardPage

**Purpose**: System overview with key statistics

**Data Displayed**:
- Merchant Count: Total number of merchants
- Account Count: Total number of accounts
- Platform Count: Total connected platforms
- Order Count: Total orders (currently 0 - backend API pending)

**API Calls**:
```typescript
// Parallel requests to get stats
merchantAPI.list(1, 1)    // Fetch 1st page, 1 item → get total count
accountAPI.list(1, 1)     // Same for accounts
platformAPI.list(1, 1)    // Same for platforms
```

**Status**:
- ✅ Functional
- ⚠️ Order count always 0 (backend API not yet providing order statistics)

---

### MerchantPage + MerchantForm

**Purpose**: CRUD operations for merchants

**Features**:
- List all merchants with pagination
- Create new merchant (via modal form)
- Edit existing merchant
- Delete merchant (with confirmation)

**Form Fields**:
- Merchant ID (read-only on edit)
- Name, Email, Phone
- Tax ID Number
- Address (City, Region, Country, ZIP, Line1, Line2)
- VIP Level
- Time Zone (default: Asia/Taipei)
- Payer Info (Name, Email, Phone)
- Status (active/inactive)

**Current Issues**:
- 🔴 **DEBUG UI**: Form has debug output (yellow box + red border) in template
- 🔴 **Custom dialog**: Using plain `<div>` instead of `el-dialog` for development
- ⚠️ **Form validation**: No validation rules implemented

---

### AccountPage + AccountForm

**Purpose**: CRUD + password management for accounts

**Features**:
- List accounts with optional merchant filtering
- Create new account
- Edit account details
- Reset password for account
- Delete account

**Form Fields**:
- Account Name, Email, Phone
- Password (on create only)
- Is Main Account (checkbox)
- Access Level (number)
- Merchant (dropdown, auto-fetched)
- Status (active/inactive)
- TOTP Secret (read-only, for 2FA setup)

**Special Methods**:
```typescript
accountAPI.resetPassword(accountId, newPassword)
  → POST /admin/account/{id}/reset-password
```

---

### PlatformPage + PlatformForm

**Purpose**: Manage platform integrations (Shopee, Momo, Yahoo, etc.)

**Features**:
- List all connected platforms
- Create new platform connection
- Update platform credentials
- Delete platform

**Form Fields**:
- Platform Name
- Platform Code (e.g., "shopee", "momo")
- Merchant (dropdown)
- API Key
- API Secret (sensitive, not displayed on read)
- Status

**Current Status**:
- ✅ UI Complete
- ✅ Form Complete
- ⚠️ **No validation** for platform code (should match enum: shopee, momo, yahoo, pchome, cyberbiz, easystore, shopline, shopify)

---

### MonitorPage

**Purpose**: System health monitoring

**Current Features** (Static):
- Service status indicators:
  - Backend API
  - Database
  - Cache Service
  - Message Queue
- Performance metrics:
  - API average response time
  - CPU, Memory, Disk usage (hardcoded percentages)
- Recent system events (hardcoded timeline)

**Current Status**:
- ⚠️ **All data is static/hardcoded** - No real backend integration
- ⚠️ **Needs**: Backend API for real-time metrics
  - CPU/Memory/Disk usage
  - API response times
  - Service health checks
  - Event log streaming

---

## 📡 API Response Format

All endpoints follow this response structure:

```typescript
interface ApiResponse<T> {
  code: number        // 0 = success, non-zero = error
  data: T             // Response data
  message: string     // Status message
}

interface PaginatedResponse<T> {
  code: number
  data: {
    total: number
    page: number
    pageSize: number
    totalPages: number
    items: T[]
  }
  message: string
}
```

---

## 🔐 Authentication & Security

**JWT Token Management**:
```typescript
// Request Interceptor
axiosInstance.interceptors.request.use((config) => {
  const token = localStorage.getItem('authToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Response Interceptor (401 handling)
axiosInstance.interceptors.response.use(
  (response) => response.data.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('authToken')
      window.dispatchEvent(new CustomEvent('session-expired'))
      return Promise.reject(new Error('Session expired'))
    }
    return Promise.reject(error)
  }
)
```

**Session Expiration**:
- If server returns 401, app clears token and dispatches `session-expired` event
- Frontend should navigate to login page on this event

---

## 🛠️ Development Guide

### Running Admin App

```bash
cd admin-app

# Install dependencies
npm install

# Development server (port 8084)
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

### Adding a New Feature

#### Example: Add a new management page for "Returns"

1. **Create API client** (`src/api/return.ts`):
```typescript
export const returnAPI = {
  list(page = 1, pageSize = 20) {
    return axiosInstance.get(`/admin/return?page=${page}&pageSize=${pageSize}`)
  },
  // ... other CRUD methods
}
```

2. **Add types** to `src/types.ts`:
```typescript
interface Return {
  id: string
  order_id: string
  reason: string
  status: string
  // ... other fields
}
```

3. **Create view** (`src/views/ReturnPage.vue`):
```typescript
import ReturnTable from '../components/ReturnTable.vue'
import ReturnForm from '../components/ReturnForm.vue'
// Same pattern as MerchantPage
```

4. **Add route** to `src/router/index.ts`:
```typescript
{ path: '/return', name: 'Return', component: ReturnPage }
```

5. **Update sidebar** in `App.vue`:
```vue
<el-menu-item index="/return">
  <span>退貨管理</span>
</el-menu-item>
```

---

## ⚠️ Known Issues

### 1. **Debug UI in Forms**
- Location: `MerchantForm.vue` (lines 2-8)
- **Issue**: Yellow DEBUG box + red border div for testing
- **Fix**: Remove before production
  ```vue
  <!-- Remove this before merge -->
  <div v-if="true" style="position: fixed; ...">
    visible: {{ visible }} | isEdit: {{ isEdit }}
  </div>
  ```

### 2. **Custom Dialog Implementation**
- **Current**: Plain `<div>` with fixed positioning + transform
- **Problem**: Not accessible, no animations, custom styling
- **Fix**: Replace with `el-dialog`
  ```vue
  <el-dialog v-model="visible" :title="isEdit ? '編輯商家' : '新增商家'">
    <!-- form content -->
  </el-dialog>
  ```

### 3. **No Form Validation**
- **Issue**: Forms accept any input without validation
- **Missing**:
  - Email format validation
  - Required field checks
  - Phone number format
  - Tax ID format (Taiwan: 8 digits)
  - Platform code enum validation
- **Solution**: Add Element Plus form validation rules
  ```typescript
  const rules = {
    merchant_name: [{ required: true, message: '商家名稱必填' }],
    merchant_email: [{ type: 'email', message: '郵箱格式不正確' }]
  }
  ```

### 4. **MonitorPage is Static**
- **Issue**: All metrics are hardcoded, not real-time
- **Missing Backend APIs**:
  - System metrics (CPU, Memory, Disk)
  - API latency
  - Service health checks
  - Event log endpoint
- **Impact**: Monitor page is display-only, can't diagnose real issues

### 5. **Pagination State Not Preserved**
- **Issue**: When editing and returning, pagination resets to page 1
- **Solution**: Store pagination state in URL or Pinia store

### 6. **No Error Boundaries**
- **Issue**: No error handling for failed API calls (only ElMessage)
- **Missing**: Retry logic, offline mode, graceful degradation

### 7. **Responsive Design Issues**
- **Issue**: Forms may overflow on small screens
- **Current**: Grid uses `xs`, `sm`, `md` but may need `lg`, `xl` for admin interface

---

## 📈 Improvement Roadmap

### Phase 1: Cleanup (Critical)
- [ ] Remove debug UI from forms
- [ ] Replace custom dialog with `el-dialog`
- [ ] Add form validation rules
- [ ] Add error handling/retry logic

### Phase 2: Features (Important)
- [ ] Implement MonitorPage real-time metrics
- [ ] Add search/filter to tables
- [ ] Implement bulk actions (delete multiple)
- [ ] Add audit logs for admin actions
- [ ] Add export data feature (Excel, CSV)

### Phase 3: Polish (Nice-to-have)
- [ ] Add dark mode
- [ ] Implement breadcrumb navigation
- [ ] Add keyboard shortcuts
- [ ] Implement activity timeline
- [ ] Add admin user profile page

### Phase 4: Performance
- [ ] Lazy load routes
- [ ] Virtual scrolling for large tables
- [ ] Caching strategy for frequently accessed data
- [ ] Offline mode support

---

## 🔗 Integration Checklist

**Before deploying Admin App, verify**:
- [ ] Backend API all `/admin/*` endpoints are live
- [ ] JWT authentication working (401 returns on expired token)
- [ ] CORS configured correctly
- [ ] Nginx reverse proxy configured for `/admin` path
- [ ] MonitorPage backend endpoints implemented
- [ ] All form validations match backend validation
- [ ] Database migrations completed for new fields

---

## 📚 Related Documentation

- **Backend API**: `docs/2-API/ADMIN_API.md`
- **Architecture**: `docs/1-ARCHITECTURE/DESIGN_v2.md`
- **Database Schema**: `docs/4-SCHEMA/SCHEMA.md`
- **Deployment**: `docs/6-OPERATIONS/QUICK_REDEPLOY_GUIDE.md`

---

## 👥 Maintenance Notes

**Last Reviewed**: 2026-02-25
**Last Updated**: 2026-03-16
**Owner**: Frontend Team

**Key Contacts**:
- Frontend Development: @team-lead
- Backend API Support: @backend-engineer
- DevOps/Deployment: @ops-team

---

*This document is the source of truth for Admin App implementation. Update after each major change.*
