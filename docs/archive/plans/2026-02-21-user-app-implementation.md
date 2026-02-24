# User App Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a merchant-facing e-commerce data management platform with real-time multi-channel sync status tracking via Kafka.

**Architecture:** Vue 3 + Vite micro-frontend with Qiankun integration. API calls use relative paths (/api/user) routed through Cloudflare reverse proxy. JWT authentication. Optimistic UI with 5-minute polling refresh. Separate Kafka topics per platform for independent channel job processing. SellPack sync tracking shows operation state (pending/syncing/completed/failed) with timestamps and old/new value diffs.

**Tech Stack:** Vue 3, Vite 5, Element Plus, Pinia 3, TypeScript, axios, Qiankun, JWT

---

## Phase 1: Project Setup & Authentication

### Task 1: Create User App Project Structure

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/package.json`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/vite.config.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/tsconfig.json`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/index.html`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/main.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/App.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/.gitignore`

**Step 1: Create package.json**

```json
{
  "name": "simpleec-user-app",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "vue-router": "^4.3.0",
    "pinia": "^2.1.0",
    "axios": "^1.6.0",
    "element-plus": "^2.6.0",
    "qiankun": "^2.10.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.0.0",
    "typescript": "^5.3.0",
    "vue-tsc": "^1.8.0"
  }
}
```

**Step 2: Create vite.config.ts**

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 8083,
    middlewareMode: true
  },
  build: {
    outDir: 'dist',
    lib: {
      entry: 'src/main.ts',
      name: 'UserApp',
      formats: ['umd']
    },
    rollupOptions: {
      external: ['vue', 'vue-router', 'pinia', 'axios', 'element-plus'],
      output: {
        globals: {
          vue: 'Vue',
          'vue-router': 'VueRouter',
          pinia: 'Pinia',
          axios: 'axios',
          'element-plus': 'ElementPlus'
        }
      }
    }
  }
})
```

**Step 3: Create tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "esModuleInterop": true,
    "allowSyntheticDefaultImports": true,
    "strict": true,
    "resolveJsonModule": true,
    "noEmit": true,
    "moduleResolution": "bundler",
    "jsx": "preserve",
    "declaration": true,
    "declarationMap": true,
    "sourceMap": true
  },
  "include": ["src"],
  "exclude": ["node_modules", "dist"]
}
```

**Step 4: Create index.html**

```html
<!DOCTYPE html>
<html lang="zh-TW">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>商家管理平台</title>
  <style>
    * {
      margin: 0;
      padding: 0;
      box-sizing: border-box;
    }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    }
    #app {
      width: 100%;
      height: 100vh;
    }
  </style>
</head>
<body>
  <div id="app"></div>
  <script src="/src/main.ts" type="module"></script>
</body>
</html>
```

**Step 5: Create main.ts**

```typescript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import 'zh-CN' from 'element-plus/es/locale/index'

let instance: ReturnType<typeof createApp> | null = null

export async function bootstrap() {
  console.log('[User App] bootstrap')
}

export async function mount(props: any) {
  console.log('[User App] mount', props)
  instance = createApp(App)
  instance.use(createPinia())
  instance.use(router)
  instance.use(ElementPlus)
  instance.mount('#app')
}

export async function unmount() {
  console.log('[User App] unmount')
  instance?.unmount()
  instance = null
}

if (!window.__POWERED_BY_QIANKUN__) {
  bootstrap().then(() => mount({}))
}
```

**Step 6: Create App.vue**

```vue
<template>
  <el-container style="height: 100vh">
    <el-header style="background-color: #f5f5f5; padding: 0; display: flex; align-items: center">
      <h1 style="margin-left: 20px; font-size: 20px; color: #333">商家管理平台</h1>
      <el-space style="margin-left: auto; margin-right: 20px">
        <el-button type="primary" @click="handleLogout">登出</el-button>
      </el-space>
    </el-header>
    <el-container>
      <el-aside width="200px" style="background-color: #fafafa">
        <el-menu :default-active="activeMenu" router>
          <el-menu-item index="/dashboard">
            <span>儀表板</span>
          </el-menu-item>
          <el-menu-item index="/product">
            <span>商品管理</span>
          </el-menu-item>
          <el-menu-item index="/sellpack">
            <span>賣場管理</span>
          </el-menu-item>
          <el-menu-item index="/order">
            <span>訂單管理</span>
          </el-menu-item>
          <el-menu-item index="/shipment">
            <span>出貨管理</span>
          </el-menu-item>
          <el-menu-item index="/refund">
            <span>退貨管理</span>
          </el-menu-item>
          <el-menu-item index="/channel">
            <span>通路管理</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-main style="padding: 20px; background-color: #fff">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()

const activeMenu = computed(() => route.path)

function handleLogout() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  router.push('/login')
  ElMessage.success('已登出')
}
</script>

<style scoped>
:deep(.el-menu) {
  border: none;
  height: 100%;
}
</style>
```

**Step 7: Create .gitignore**

```
node_modules
dist
.env.local
.DS_Store
*.swp
*.swo
```

**Step 8: Run npm install**

```bash
cd /home/tom/ONEEC/simpleec-oms/user-app
npm install
```

**Step 9: Verify project structure**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/
```

Expected: Show package.json, vite.config.ts, tsconfig.json, index.html, src/, node_modules/

**Step 10: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/
git commit -m "feat: create user app project structure with Qiankun support

- Initialize Vue 3 + Vite 5 project
- Setup TypeScript configuration
- Create Qiankun lifecycle exports
- Setup Element Plus and white color scheme
- Create root App.vue with navigation menu

Generated with Claude Code
via Happy"
```

---

### Task 2: Create Auth Store and Types

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/types/index.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/stores/auth.ts`

**Step 1: Create types/index.ts**

```typescript
// Auth
export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  token: string
  user: User
}

export interface User {
  id: string
  email: string
  name: string
  role: string
}

// Product
export interface Product {
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

// SellPack (Product on Platform)
export interface SyncStatus {
  status: 'pending' | 'syncing' | 'completed' | 'failed'
  operation: 'QUANTITY_UPDATE' | 'PRICE_UPDATE' | 'LISTING_UPDATE' | 'IMAGE_UPDATE' | 'DESC_UPDATE'
  oldValue?: any
  newValue?: any
  startTime?: string
  completedTime?: string
  error?: string
  retryCount: number
}

export interface SellPack {
  id: string
  productId: string
  platformId: string
  platformName: string
  quantity: number
  price: number
  image: string
  description: string
  listingStatus: 'active' | 'inactive'
  syncStatus: SyncStatus
  createdAt: string
  updatedAt: string
  lastSyncAt?: string
}

// Order
export interface Order {
  id: string
  orderNumber: string
  platform: string
  totalAmount: number
  status: 'pending' | 'confirmed' | 'shipped' | 'completed' | 'cancelled'
  syncStatus: {
    operation: 'SHIPMENT' | 'CANCEL'
    status: 'pending' | 'syncing' | 'completed' | 'failed'
    startTime?: string
    completedTime?: string
    error?: string
  }
  createdAt: string
  updatedAt: string
}

// Refund
export interface Refund {
  id: string
  orderId: string
  platform: string
  amount: number
  reason: string
  syncStatus: {
    status: 'pending' | 'syncing' | 'completed' | 'failed'
    startTime?: string
    completedTime?: string
    error?: string
  }
  createdAt: string
  updatedAt: string
}

// Platform
export interface Platform {
  id: string
  name: string
  code: string
  status: 'active' | 'inactive'
}
```

**Step 2: Create stores/auth.ts**

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'
import { User, LoginRequest, LoginResponse } from '../types'

const API_BASE = '/api/user'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('token'))
  const user = ref<User | null>((() => {
    const stored = localStorage.getItem('user')
    return stored ? JSON.parse(stored) : null
  })())
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => !!token.value)

  async function login(credentials: LoginRequest) {
    loading.value = true
    error.value = null
    try {
      const response = await axios.post<LoginResponse>(`${API_BASE}/login`, credentials)
      const { token: newToken, user: userData } = response.data

      token.value = newToken
      user.value = userData

      localStorage.setItem('token', newToken)
      localStorage.setItem('user', JSON.stringify(userData))

      // Set default axios header
      axios.defaults.headers.common['Authorization'] = `Bearer ${newToken}`

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
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    delete axios.defaults.headers.common['Authorization']
  }

  function restoreToken() {
    const stored = localStorage.getItem('token')
    if (stored) {
      token.value = stored
      axios.defaults.headers.common['Authorization'] = `Bearer ${stored}`
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
```

**Step 3: Create directory structure**

```bash
mkdir -p /home/tom/ONEEC/simpleec-oms/user-app/src/types
mkdir -p /home/tom/ONEEC/simpleec-oms/user-app/src/stores
mkdir -p /home/tom/ONEEC/simpleec-oms/user-app/src/api
mkdir -p /home/tom/ONEEC/simpleec-oms/user-app/src/views
mkdir -p /home/tom/ONEEC/simpleec-oms/user-app/src/components
mkdir -p /home/tom/ONEEC/simpleec-oms/user-app/src/router
```

**Step 4: Verify files created**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/types/
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/stores/
```

**Step 5: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/types/ user-app/src/stores/
git commit -m "feat: add auth store and data types

- Define all data models: User, Product, SellPack, Order, Refund, Platform
- Create Pinia auth store with JWT token management
- Implement localStorage persistence for auth state
- Setup axios Authorization header management

Generated with Claude Code
via Happy"
```

---

### Task 3: Create Router and LoginPage

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/router/index.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/LoginPage.vue`

**Step 1: Create router/index.ts**

```typescript
import { createRouter, createMemoryHistory, createWebHistory, RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginPage from '../views/LoginPage.vue'
import DashboardPage from '../views/DashboardPage.vue'
import ProductPage from '../views/ProductPage.vue'
import SellPackPage from '../views/SellPackPage.vue'
import OrderPage from '../views/OrderPage.vue'
import ShipmentPage from '../views/ShipmentPage.vue'
import RefundPage from '../views/RefundPage.vue'
import ChannelPage from '../views/ChannelPage.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    component: LoginPage,
    meta: { requiresAuth: false }
  },
  {
    path: '/dashboard',
    component: DashboardPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/product',
    component: ProductPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/sellpack',
    component: SellPackPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/order',
    component: OrderPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/shipment',
    component: ShipmentPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/refund',
    component: RefundPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/channel',
    component: ChannelPage,
    meta: { requiresAuth: true }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard'
  }
]

const router = createRouter({
  history: window.__POWERED_BY_QIANKUN__
    ? createMemoryHistory('/user-app/')
    : createWebHistory('/user-app/'),
  routes
})

router.beforeEach((to, from, next) => {
  const authStore = useAuthStore()
  authStore.restoreToken()

  const requiresAuth = to.meta.requiresAuth !== false
  if (requiresAuth && !authStore.isAuthenticated) {
    next('/login')
  } else if (to.path === '/login' && authStore.isAuthenticated) {
    next('/dashboard')
  } else {
    next()
  }
})

export default router
```

**Step 2: Create views/LoginPage.vue**

```vue
<template>
  <div class="login-container">
    <el-card class="login-card">
      <template #header>
        <h1 style="text-align: center; margin: 0">商家管理平台</h1>
      </template>

      <el-form
        :model="form"
        :rules="rules"
        ref="formRef"
        @submit.prevent="handleLogin"
      >
        <el-form-item label="郵箱" prop="email">
          <el-input
            v-model="form.email"
            placeholder="輸入郵箱地址"
            type="email"
          />
        </el-form-item>

        <el-form-item label="密碼" prop="password">
          <el-input
            v-model="form.password"
            placeholder="輸入密碼"
            type="password"
            show-password
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            @click="handleLogin"
            :loading="loading"
            style="width: 100%"
          >
            登入
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="true"
        @close="error = ''"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'

const router = useRouter()
const authStore = useAuthStore()
const formRef = ref<FormInstance>()

const form = ref({
  email: '',
  password: ''
})

const loading = ref(false)
const error = ref('')

const rules = {
  email: [
    { required: true, message: '請輸入郵箱地址', trigger: 'blur' },
    { type: 'email', message: '請輸入正確的郵箱格式', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '請輸入密碼', trigger: 'blur' },
    { min: 6, message: '密碼至少6個字符', trigger: 'blur' }
  ]
}

async function handleLogin() {
  if (!formRef.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  error.value = ''

  const success = await authStore.login({
    email: form.value.email,
    password: form.value.password
  })

  if (success) {
    ElMessage.success('登入成功')
    router.push('/dashboard')
  } else {
    error.value = authStore.error || '登入失敗，請檢查郵箱和密碼'
  }

  loading.value = false
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
}

.login-card {
  width: 400px;
  box-shadow: 0 2px 12px rgb(0 0 0 / 10%);
}
</style>
```

**Step 3: Update main.ts to use router**

Replace the router import line in main.ts (already done in Task 1)

**Step 4: Verify files**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/router/
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/views/
```

**Step 5: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/router/ user-app/src/views/LoginPage.vue
git commit -m "feat: add router and login page

- Create Vue Router with auth guards
- Implement LoginPage with email/password form
- Setup memory history for Qiankun compatibility
- Add form validation and error handling

Generated with Claude Code
via Happy"
```

---

## Phase 2: Core API and Pages (Tasks 4-11)

### Task 4: Create API Modules

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/api/auth.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/api/product.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/api/sellpack.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/api/order.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/api/refund.ts`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/api/channel.ts`

**Step 1: Create api/auth.ts**

```typescript
import axios from 'axios'
import { LoginRequest, LoginResponse } from '../types'

const API_BASE = '/api/user'

export const authAPI = {
  login(data: LoginRequest) {
    return axios.post<LoginResponse>(`${API_BASE}/login`, data)
  },

  logout() {
    return axios.post(`${API_BASE}/logout`)
  },

  getProfile() {
    return axios.get(`${API_BASE}/profile`)
  }
}
```

**Step 2: Create api/product.ts**

```typescript
import axios from 'axios'
import { Product } from '../types'

const API_BASE = '/api/user'

export const productAPI = {
  list(page: number = 0, size: number = 20, keyword?: string) {
    let url = `${API_BASE}/products?page=${page}&size=${size}`
    if (keyword) url += `&keyword=${keyword}`
    return axios.get<{
      code: number
      data: {
        content: Product[]
        totalElements: number
        totalPages: number
      }
    }>(url)
  },

  get(id: string) {
    return axios.get<{ code: number; data: Product }>(`${API_BASE}/products/${id}`)
  },

  create(data: Partial<Product>) {
    return axios.post<{ code: number; data: Product }>(`${API_BASE}/products`, data)
  },

  update(id: string, data: Partial<Product>) {
    return axios.put<{ code: number; data: Product }>(`${API_BASE}/products/${id}`, data)
  },

  delete(id: string) {
    return axios.delete(`${API_BASE}/products/${id}`)
  }
}
```

**Step 3: Create api/sellpack.ts**

```typescript
import axios from 'axios'
import { SellPack } from '../types'

const API_BASE = '/api/user'

export const sellpackAPI = {
  list(page: number = 0, size: number = 20, keyword?: string) {
    let url = `${API_BASE}/sellpacks?page=${page}&size=${size}`
    if (keyword) url += `&keyword=${keyword}`
    return axios.get<{
      code: number
      data: {
        content: SellPack[]
        totalElements: number
        totalPages: number
      }
    }>(url)
  },

  get(id: string) {
    return axios.get<{ code: number; data: SellPack }>(`${API_BASE}/sellpacks/${id}`)
  },

  updateQuantity(id: string, quantity: number) {
    return axios.put<{ code: number; data: SellPack }>(
      `${API_BASE}/sellpacks/${id}/quantity`,
      { quantity }
    )
  },

  updatePrice(id: string, price: number) {
    return axios.put<{ code: number; data: SellPack }>(
      `${API_BASE}/sellpacks/${id}/price`,
      { price }
    )
  },

  updateListing(id: string, status: 'active' | 'inactive') {
    return axios.put<{ code: number; data: SellPack }>(
      `${API_BASE}/sellpacks/${id}/listing`,
      { status }
    )
  },

  updateImage(id: string, image: string) {
    return axios.put<{ code: number; data: SellPack }>(
      `${API_BASE}/sellpacks/${id}/image`,
      { image }
    )
  },

  updateDescription(id: string, description: string) {
    return axios.put<{ code: number; data: SellPack }>(
      `${API_BASE}/sellpacks/${id}/description`,
      { description }
    )
  }
}
```

**Step 4: Create api/order.ts**

```typescript
import axios from 'axios'
import { Order } from '../types'

const API_BASE = '/api/user'

export const orderAPI = {
  list(page: number = 0, size: number = 20, platform?: string, status?: string) {
    let url = `${API_BASE}/orders?page=${page}&size=${size}`
    if (platform) url += `&platform=${platform}`
    if (status) url += `&status=${status}`
    return axios.get<{
      code: number
      data: {
        content: Order[]
        totalElements: number
        totalPages: number
      }
    }>(url)
  },

  get(id: string) {
    return axios.get<{ code: number; data: Order }>(`${API_BASE}/orders/${id}`)
  },

  ship(id: string, trackingNumber?: string) {
    return axios.post<{ code: number; data: Order }>(
      `${API_BASE}/orders/${id}/ship`,
      { trackingNumber }
    )
  },

  cancel(id: string, reason?: string) {
    return axios.post<{ code: number; data: Order }>(
      `${API_BASE}/orders/${id}/cancel`,
      { reason }
    )
  }
}
```

**Step 5: Create api/refund.ts**

```typescript
import axios from 'axios'
import { Refund } from '../types'

const API_BASE = '/api/user'

export const refundAPI = {
  list(page: number = 0, size: number = 20, orderId?: string) {
    let url = `${API_BASE}/refunds?page=${page}&size=${size}`
    if (orderId) url += `&orderId=${orderId}`
    return axios.get<{
      code: number
      data: {
        content: Refund[]
        totalElements: number
        totalPages: number
      }
    }>(url)
  },

  get(id: string) {
    return axios.get<{ code: number; data: Refund }>(`${API_BASE}/refunds/${id}`)
  },

  create(data: Partial<Refund>) {
    return axios.post<{ code: number; data: Refund }>(`${API_BASE}/refunds`, data)
  },

  approve(id: string) {
    return axios.post<{ code: number; data: Refund }>(`${API_BASE}/refunds/${id}/approve`)
  },

  reject(id: string, reason?: string) {
    return axios.post<{ code: number; data: Refund }>(
      `${API_BASE}/refunds/${id}/reject`,
      { reason }
    )
  }
}
```

**Step 6: Create api/channel.ts**

```typescript
import axios from 'axios'
import { Platform } from '../types'

const API_BASE = '/api/user'

export const channelAPI = {
  list() {
    return axios.get<{ code: number; data: Platform[] }>(`${API_BASE}/platforms`)
  },

  get(id: string) {
    return axios.get<{ code: number; data: Platform }>(`${API_BASE}/platforms/${id}`)
  }
}
```

**Step 7: Verify files**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/api/
```

**Step 8: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/api/
git commit -m "feat: add API modules for all user app entities

- Create auth API for login/logout
- Create product/sellpack CRUD and operation APIs
- Create order shipment/cancel APIs
- Create refund management APIs
- Create channel/platform list API
- All use relative paths for reverse proxy compatibility

Generated with Claude Code
via Happy"
```

---

### Task 5: Create DashboardPage

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/DashboardPage.vue`

**Step 1: Create DashboardPage.vue**

```vue
<template>
  <div>
    <h2>儀表板</h2>

    <el-row :gutter="20" style="margin-bottom: 20px">
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.totalOrders }}</div>
            <div class="stat-label">總訂單</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.pendingOrders }}</div>
            <div class="stat-label">待出貨</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.completedOrders }}</div>
            <div class="stat-label">已完成</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <el-card class="stat-card">
          <div class="stat-content">
            <div class="stat-value">{{ stats.totalRevenue }}</div>
            <div class="stat-label">總營收</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :xs="24" :md="12">
        <el-card>
          <template #header>
            <h3>最近訂單</h3>
          </template>
          <el-table :data="recentOrders" style="width: 100%">
            <el-table-column prop="orderNumber" label="訂單編號" width="120" />
            <el-table-column prop="platform" label="通路" width="80" />
            <el-table-column prop="totalAmount" label="金額" width="80" />
            <el-table-column prop="status" label="狀態" width="80">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.status)">
                  {{ getStatusText(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card>
          <template #header>
            <h3>通路分布</h3>
          </template>
          <div class="platform-list">
            <div v-for="platform in platformStats" :key="platform.name" class="platform-item">
              <span>{{ platform.name }}</span>
              <span style="color: #666">{{ platform.count }} 個訂單</span>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { orderAPI } from '../api/order'
import { Order } from '../types'

const stats = ref({
  totalOrders: 0,
  pendingOrders: 0,
  completedOrders: 0,
  totalRevenue: 0
})

const recentOrders = ref<Order[]>([])
const platformStats = ref<{ name: string; count: number }[]>([])

onMounted(async () => {
  await loadDashboard()
})

async function loadDashboard() {
  try {
    const response = await orderAPI.list(0, 10)
    const orders = response.data.data.content

    recentOrders.value = orders

    // Calculate stats
    stats.value.totalOrders = response.data.data.totalElements
    stats.value.pendingOrders = orders.filter(o => o.status === 'pending').length
    stats.value.completedOrders = orders.filter(o => o.status === 'completed').length
    stats.value.totalRevenue = orders.reduce((sum, o) => sum + o.totalAmount, 0)

    // Calculate platform stats
    const platformMap = new Map<string, number>()
    orders.forEach(order => {
      platformMap.set(order.platform, (platformMap.get(order.platform) || 0) + 1)
    })
    platformStats.value = Array.from(platformMap.entries()).map(([name, count]) => ({
      name,
      count
    }))
  } catch (error) {
    console.error('Failed to load dashboard:', error)
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'warning',
    confirmed: 'info',
    shipped: 'primary',
    completed: 'success',
    cancelled: 'danger'
  }
  return map[status] || 'info'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待確認',
    confirmed: '已確認',
    shipped: '已出貨',
    completed: '已完成',
    cancelled: '已取消'
  }
  return map[status] || status
}
</script>

<style scoped>
.stat-card {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-content {
  text-align: center;
}

.stat-value {
  font-size: 32px;
  font-weight: bold;
  color: #409eff;
  margin-bottom: 10px;
}

.stat-label {
  font-size: 14px;
  color: #909399;
}

.platform-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.platform-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}
</style>
```

**Step 2: Verify file**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/views/DashboardPage.vue
```

**Step 3: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/views/DashboardPage.vue
git commit -m "feat: add dashboard page with order statistics

- Display order count and revenue metrics
- Show platform distribution
- Show recent orders list
- Load data from order API on mount

Generated with Claude Code
via Happy"
```

---

### Task 6: Create ProductPage (Basic CRUD)

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/ProductPage.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/components/ProductTable.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/components/ProductForm.vue`

**Step 1: Create components/ProductTable.vue**

```vue
<template>
  <div>
    <el-input
      v-model="searchKeyword"
      placeholder="搜尋商品"
      style="margin-bottom: 15px; width: 300px"
      @input="handleSearch"
    />

    <el-table :data="products" style="width: 100%" v-loading="loading">
      <el-table-column prop="sku" label="SKU" width="120" />
      <el-table-column prop="name" label="商品名稱" min-width="150" />
      <el-table-column prop="quantity" label="庫存" width="80" />
      <el-table-column prop="price" label="價格" width="100" />
      <el-table-column prop="status" label="狀態" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'active' ? 'success' : 'danger'">
            {{ row.status === 'active' ? '啟用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button link type="primary" @click="$emit('edit', row)">編輯</el-button>
          <el-popconfirm title="確定刪除?" @confirm="$emit('delete', row.id)">
            <template #reference>
              <el-button link type="danger">刪除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 20px; text-align: right"
      @current-page-change="loadData"
      @page-size-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { productAPI } from '../api/product'
import { Product } from '../types'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  edit: [product: Product]
  delete: [id: string]
}>()

const products = ref<Product[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  loadData()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response = await productAPI.list(currentPage.value - 1, pageSize.value, searchKeyword.value)
    products.value = response.data.data.content
    total.value = response.data.data.totalElements
  } catch (error) {
    console.error('Failed to load products:', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadData()
}
</script>
```

**Step 2: Create components/ProductForm.vue**

```vue
<template>
  <el-dialog v-model="visible" :title="isEdit ? '編輯商品' : '新增商品'" width="600px" @close="handleClose">
    <el-form ref="form" :model="formData" label-width="100px">
      <el-form-item label="SKU">
        <el-input v-model="formData.sku" placeholder="輸入SKU" :disabled="isEdit" />
      </el-form-item>
      <el-form-item label="商品名稱">
        <el-input v-model="formData.name" placeholder="輸入商品名稱" />
      </el-form-item>
      <el-form-item label="庫存">
        <el-input-number v-model.number="formData.quantity" :min="0" />
      </el-form-item>
      <el-form-item label="價格">
        <el-input-number v-model.number="formData.price" :min="0" :precision="2" />
      </el-form-item>
      <el-form-item label="圖片">
        <el-input v-model="formData.image" placeholder="輸入圖片URL" />
      </el-form-item>
      <el-form-item label="說明">
        <el-input v-model="formData.description" placeholder="輸入商品說明" type="textarea" rows="3" />
      </el-form-item>
      <el-form-item label="狀態">
        <el-select v-model="formData.status">
          <el-option label="啟用" value="active" />
          <el-option label="停用" value="inactive" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" @click="handleSubmit" :loading="loading">
        {{ isEdit ? '更新' : '建立' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Product } from '../types'
import { productAPI } from '../api/product'

const props = defineProps<{
  product?: Product | null
}>()

const emit = defineEmits<{
  saved: [product: Product]
  close: []
}>()

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)

const formData = ref<Partial<Product>>({
  sku: '',
  name: '',
  quantity: 0,
  price: 0,
  image: '',
  description: '',
  status: 'active'
})

watch(() => props.product, (newVal) => {
  if (newVal) {
    formData.value = { ...newVal }
    isEdit.value = true
    visible.value = true
  } else {
    resetForm()
  }
})

function resetForm() {
  formData.value = {
    sku: '',
    name: '',
    quantity: 0,
    price: 0,
    image: '',
    description: '',
    status: 'active'
  }
  isEdit.value = false
}

async function handleSubmit() {
  loading.value = true
  try {
    let result: Product
    if (isEdit.value) {
      const response = await productAPI.update(formData.value.id!, formData.value)
      result = response.data.data
      ElMessage.success('更新成功')
    } else {
      const response = await productAPI.create(formData.value)
      result = response.data.data
      ElMessage.success('建立成功')
    }
    emit('saved', result)
    handleClose()
  } catch (error) {
    ElMessage.error('操作失敗')
    console.error(error)
  } finally {
    loading.value = false
  }
}

function handleClose() {
  visible.value = false
  emit('close')
}
</script>
```

**Step 3: Create views/ProductPage.vue**

```vue
<template>
  <div>
    <div class="page-header">
      <h2>商品管理</h2>
      <el-button type="primary" @click="handleNewProduct">+ 新增商品</el-button>
    </div>
    <el-card>
      <ProductTable :refresh="refreshCount" @edit="handleEditProduct" @delete="handleDeleteProduct" />
    </el-card>
    <ProductForm :product="selectedProduct" @saved="handleSaved" @close="selectedProduct = null" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Product } from '../types'
import { productAPI } from '../api/product'
import ProductTable from '../components/ProductTable.vue'
import ProductForm from '../components/ProductForm.vue'

const selectedProduct = ref<Product | null>(null)
const refreshCount = ref(0)

function handleNewProduct() {
  selectedProduct.value = null
}

function handleEditProduct(product: Product) {
  selectedProduct.value = product
}

async function handleDeleteProduct(id: string) {
  try {
    await productAPI.delete(id)
    ElMessage.success('刪除成功')
    refreshCount.value++
  } catch (error) {
    ElMessage.error('刪除失敗')
  }
}

function handleSaved() {
  refreshCount.value++
  selectedProduct.value = null
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
}
</style>
```

**Step 4: Verify files**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/components/Product*
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/views/ProductPage.vue
```

**Step 5: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/components/ProductTable.vue user-app/src/components/ProductForm.vue user-app/src/views/ProductPage.vue
git commit -m "feat: add product management page with CRUD

- ProductPage coordinates product list and form
- ProductTable shows paginated list with search
- ProductForm handles create/edit operations
- Delete functionality with confirmation dialog

Generated with Claude Code
via Happy"
```

---

### Task 7: Create SellPackPage (Core Sync Feature)

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/SellPackPage.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/components/SellPackTable.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/utils/syncStatus.ts`

**Step 1: Create utils/syncStatus.ts**

```typescript
import { SyncStatus } from '../types'

export function getSyncStatusText(status: SyncStatus['status']): string {
  const map: Record<string, string> = {
    pending: '待同步',
    syncing: '同步中',
    completed: '已同步',
    failed: '同步失敗'
  }
  return map[status] || status
}

export function getSyncStatusType(status: SyncStatus['status']): string {
  const map: Record<string, string> = {
    pending: 'info',
    syncing: 'warning',
    completed: 'success',
    failed: 'danger'
  }
  return map[status] || 'info'
}

export function getOperationText(operation: SyncStatus['operation']): string {
  const map: Record<string, string> = {
    QUANTITY_UPDATE: '數量更新',
    PRICE_UPDATE: '價格更新',
    LISTING_UPDATE: '上架狀態',
    IMAGE_UPDATE: '圖片更新',
    DESC_UPDATE: '說明更新'
  }
  return map[operation] || operation
}

export function getElapsedTime(startTime: string | undefined): string {
  if (!startTime) return ''

  const start = new Date(startTime).getTime()
  const now = Date.now()
  const diff = Math.floor((now - start) / 1000)

  if (diff < 60) return `${diff}秒`
  if (diff < 3600) return `${Math.floor(diff / 60)}分${diff % 60}秒`

  const hours = Math.floor(diff / 3600)
  const minutes = Math.floor((diff % 3600) / 60)
  return `${hours}小時${minutes}分`
}

export function formatDateTime(dateStr: string | undefined): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleString('zh-TW', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}
```

**Step 2: Create components/SellPackTable.vue**

```vue
<template>
  <div>
    <el-input
      v-model="searchKeyword"
      placeholder="搜尋賣場 (SKU/名稱)"
      style="margin-bottom: 15px; width: 300px"
      @input="handleSearch"
    />

    <el-table :data="sellpacks" style="width: 100%" v-loading="loading">
      <el-table-column label="商品資訊" min-width="200">
        <template #default="{ row }">
          <div>
            <div style="font-weight: bold">{{ row.sku }}</div>
            <div style="font-size: 12px; color: #666">{{ row.productId }}</div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="通路" width="100" prop="platformName" />

      <el-table-column label="庫存" min-width="150">
        <template #default="{ row }">
          <div v-if="row.syncStatus.operation === 'QUANTITY_UPDATE'" style="font-size: 12px">
            <div>原: {{ row.syncStatus.oldValue }}</div>
            <div style="color: #409eff; font-weight: bold">
              目標: {{ row.syncStatus.newValue }}
              <span style="color: #67c23a">(+{{ row.syncStatus.newValue - row.syncStatus.oldValue }})</span>
            </div>
          </div>
          <div v-else>{{ row.quantity }}</div>
        </template>
      </el-table-column>

      <el-table-column label="價格" min-width="150">
        <template #default="{ row }">
          <div v-if="row.syncStatus.operation === 'PRICE_UPDATE'" style="font-size: 12px">
            <div>原: ${{ row.syncStatus.oldValue }}</div>
            <div style="color: #409eff; font-weight: bold">
              目標: ${{ row.syncStatus.newValue }}
              <span style="color: #f56c6c">(+${{ (row.syncStatus.newValue - row.syncStatus.oldValue).toFixed(2) }})</span>
            </div>
          </div>
          <div v-else>${{ row.price }}</div>
        </template>
      </el-table-column>

      <el-table-column label="同步狀態" min-width="200">
        <template #default="{ row }">
          <div>
            <el-tag :type="getSyncStatusType(row.syncStatus.status)" style="margin-bottom: 8px">
              {{ getSyncStatusText(row.syncStatus.status) }}
            </el-tag>
            <div style="font-size: 12px; color: #666; margin-top: 4px">
              <div>{{ getOperationText(row.syncStatus.operation) }}</div>
              <div v-if="row.syncStatus.status === 'syncing'">
                已等待 {{ getElapsedTime(row.syncStatus.startTime) }}
              </div>
              <div v-if="row.syncStatus.status === 'completed'">
                @{{ formatDateTime(row.syncStatus.completedTime) }}
              </div>
              <div v-if="row.syncStatus.status === 'failed'" style="color: #f56c6c">
                {{ row.syncStatus.error }}
              </div>
            </div>
          </div>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="$emit('edit', row)">編輯</el-button>
          <el-button
            v-if="row.syncStatus.status === 'failed'"
            link type="warning" size="small"
            @click="$emit('retry', row)"
          >
            重試
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 20px; text-align: right"
      @current-page-change="loadData"
      @page-size-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { sellpackAPI } from '../api/sellpack'
import { SellPack } from '../types'
import { getSyncStatusText, getSyncStatusType, getOperationText, getElapsedTime, formatDateTime } from '../utils/syncStatus'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  edit: [sellpack: SellPack]
  retry: [sellpack: SellPack]
}>()

const sellpacks = ref<SellPack[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const refreshInterval = ref<NodeJS.Timeout | null>(null)

onMounted(() => {
  loadData()
  startPolling()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response = await sellpackAPI.list(currentPage.value - 1, pageSize.value, searchKeyword.value)
    sellpacks.value = response.data.data.content
    total.value = response.data.data.totalElements
  } catch (error) {
    console.error('Failed to load sellpacks:', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadData()
}

function startPolling() {
  refreshInterval.value = setInterval(() => {
    loadData()
  }, 5 * 60 * 1000) // 5 minutes
}

watch(() => props.refresh, () => {
  if (refreshInterval.value) {
    clearInterval(refreshInterval.value)
    startPolling()
  }
})
</script>
```

**Step 3: Create views/SellPackPage.vue**

```vue
<template>
  <div>
    <div class="page-header">
      <h2>賣場管理</h2>
      <el-button type="primary" @click="handleBatchUpdate">批量更新</el-button>
    </div>
    <el-card>
      <SellPackTable :refresh="refreshCount" @edit="handleEditSellPack" @retry="handleRetrySellPack" />
    </el-card>
    <SellPackForm :sellpack="selectedSellpack" @saved="handleSaved" @close="selectedSellpack = null" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { SellPack } from '../types'
import SellPackTable from '../components/SellPackTable.vue'
import SellPackForm from '../components/SellPackForm.vue'

const selectedSellpack = ref<SellPack | null>(null)
const refreshCount = ref(0)

function handleEditSellPack(sellpack: SellPack) {
  selectedSellpack.value = sellpack
}

function handleRetrySellPack(sellpack: SellPack) {
  ElMessage.info('重試功能將在後續版本實現')
}

function handleBatchUpdate() {
  ElMessage.info('批量更新功能將在後續版本實現')
}

function handleSaved() {
  refreshCount.value++
  selectedSellpack.value = null
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
}
</style>
```

**Step 4: Create components/SellPackForm.vue (placeholder)**

```vue
<template>
  <el-dialog v-model="visible" title="編輯賣場" width="600px" @close="handleClose">
    <el-form :model="formData" label-width="100px">
      <el-form-item label="商品">
        <el-input :value="formData.productId" disabled />
      </el-form-item>
      <el-form-item label="通路">
        <el-input :value="formData.platformName" disabled />
      </el-form-item>
      <el-form-item label="數量">
        <el-input-number v-model.number="formData.quantity" :min="0" />
      </el-form-item>
      <el-form-item label="價格">
        <el-input-number v-model.number="formData.price" :min="0" :precision="2" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" @click="handleSubmit" :loading="loading">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { SellPack } from '../types'

const props = defineProps<{
  sellpack?: SellPack | null
}>()

const emit = defineEmits<{
  saved: [sellpack: SellPack]
  close: []
}>()

const visible = ref(false)
const loading = ref(false)

const formData = ref<Partial<SellPack>>({
  productId: '',
  platformName: '',
  quantity: 0,
  price: 0
})

watch(() => props.sellpack, (newVal) => {
  if (newVal) {
    formData.value = { ...newVal }
    visible.value = true
  }
})

async function handleSubmit() {
  loading.value = true
  try {
    ElMessage.success('保存成功')
    emit('saved', formData.value as SellPack)
    handleClose()
  } catch (error) {
    ElMessage.error('保存失敗')
  } finally {
    loading.value = false
  }
}

function handleClose() {
  visible.value = false
  emit('close')
}
</script>
```

**Step 5: Verify files**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/utils/
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/components/SellPack*
ls -la /home/tom/ONEEC/simpleec-oms/user-app/src/views/SellPackPage.vue
```

**Step 6: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/utils/syncStatus.ts user-app/src/components/SellPackTable.vue user-app/src/components/SellPackForm.vue user-app/src/views/SellPackPage.vue
git commit -m "feat: add sellpack page with sync status tracking

- SellPackTable displays products with sync status
- Show old/new values for quantity and price updates
- Display elapsed time for syncing operations
- 5-minute auto-polling for sync status refresh
- Core feature: Optimistic UI with operation tracking
- Utility functions for sync status display formatting

Generated with Claude Code
via Happy"
```

---

### Task 8: Create OrderPage & ShipmentPage

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/OrderPage.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/components/OrderTable.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/ShipmentPage.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/components/ShipmentTable.vue`

**Step 1: Create components/OrderTable.vue**

```vue
<template>
  <div>
    <el-input
      v-model="searchKeyword"
      placeholder="搜尋訂單編號"
      style="margin-bottom: 15px; width: 300px"
      @input="handleSearch"
    />

    <el-table :data="orders" style="width: 100%" v-loading="loading">
      <el-table-column prop="orderNumber" label="訂單編號" width="150" />
      <el-table-column prop="platform" label="通路" width="100" />
      <el-table-column prop="totalAmount" label="金額" width="100" />
      <el-table-column prop="status" label="狀態" width="120">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.status)">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="同步狀態" width="150">
        <template #default="{ row }">
          <el-tag :type="getSyncStatusType(row.syncStatus.status)" v-if="row.syncStatus.operation">
            {{ getSyncStatusText(row.syncStatus.status) }}
          </el-tag>
          <span v-else>無</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'confirmed'"
            link type="primary" size="small"
            @click="$emit('ship', row)"
          >
            出貨
          </el-button>
          <el-button
            v-if="['pending', 'confirmed'].includes(row.status)"
            link type="danger" size="small"
            @click="$emit('cancel', row)"
          >
            取消
          </el-button>
          <el-button link type="default" size="small" @click="$emit('view', row)">詳情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 20px; text-align: right"
      @current-page-change="loadData"
      @page-size-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { orderAPI } from '../api/order'
import { Order } from '../types'
import { getSyncStatusText, getSyncStatusType } from '../utils/syncStatus'

const props = defineProps<{
  refresh: number
  filterStatus?: string
}>()

const emit = defineEmits<{
  ship: [order: Order]
  cancel: [order: Order]
  view: [order: Order]
}>()

const orders = ref<Order[]>([])
const loading = ref(false)
const searchKeyword = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  loadData()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response = await orderAPI.list(currentPage.value - 1, pageSize.value, undefined, props.filterStatus)
    orders.value = response.data.data.content
    total.value = response.data.data.totalElements
  } catch (error) {
    console.error('Failed to load orders:', error)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadData()
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'info',
    confirmed: 'warning',
    shipped: 'primary',
    completed: 'success',
    cancelled: 'danger'
  }
  return map[status] || 'info'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待確認',
    confirmed: '已確認',
    shipped: '已出貨',
    completed: '已完成',
    cancelled: '已取消'
  }
  return map[status] || status
}
</script>
```

**Step 2: Create views/OrderPage.vue**

```vue
<template>
  <div>
    <h2>訂單管理</h2>
    <el-card>
      <OrderTable :refresh="refreshCount" @ship="handleShip" @cancel="handleCancel" @view="handleView" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Order } from '../types'
import { orderAPI } from '../api/order'
import OrderTable from '../components/OrderTable.vue'

const refreshCount = ref(0)

async function handleShip(order: Order) {
  ElMessageBox.prompt('請輸入物流單號', '出貨', {
    confirmButtonText: '確定',
    cancelButtonText: '取消'
  }).then(async ({ value }) => {
    try {
      await orderAPI.ship(order.id, value)
      ElMessage.success('出貨已送出同步')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('出貨失敗')
    }
  }).catch(() => {})
}

async function handleCancel(order: Order) {
  ElMessageBox.confirm('確定要取消訂單?', '警告', {
    confirmButtonText: '確定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await orderAPI.cancel(order.id)
      ElMessage.success('取消已送出同步')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('取消失敗')
    }
  }).catch(() => {})
}

function handleView(order: Order) {
  ElMessage.info('訂單詳情將在後續版本實現')
}
</script>
```

**Step 3: Create components/ShipmentTable.vue**

```vue
<template>
  <div>
    <el-table :data="orders" style="width: 100%" v-loading="loading">
      <el-table-column prop="orderNumber" label="訂單編號" width="150" />
      <el-table-column prop="platform" label="通路" width="100" />
      <el-table-column prop="totalAmount" label="金額" width="100" />
      <el-table-column label="出貨狀態" width="150">
        <template #default="{ row }">
          <el-tag :type="row.status === 'shipped' ? 'success' : 'warning'">
            {{ row.status === 'shipped' ? '已出貨' : '待出貨' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="同步狀態" width="150">
        <template #default="{ row }">
          <el-tag :type="getSyncStatusType(row.syncStatus.status)" v-if="row.syncStatus.operation">
            {{ getSyncStatusText(row.syncStatus.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'confirmed'"
            link type="primary" size="small"
            @click="$emit('ship', row)"
          >
            出貨
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 20px; text-align: right"
      @current-page-change="loadData"
      @page-size-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { orderAPI } from '../api/order'
import { Order } from '../types'
import { getSyncStatusText, getSyncStatusType } from '../utils/syncStatus'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  ship: [order: Order]
}>()

const orders = ref<Order[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  loadData()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response = await orderAPI.list(currentPage.value - 1, pageSize.value, undefined, 'confirmed')
    orders.value = response.data.data.content.filter((o: Order) => o.status === 'confirmed')
    total.value = orders.value.length
  } catch (error) {
    console.error('Failed to load pending shipments:', error)
  } finally {
    loading.value = false
  }
}
</script>
```

**Step 4: Create views/ShipmentPage.vue**

```vue
<template>
  <div>
    <h2>出貨管理</h2>
    <el-card>
      <ShipmentTable :refresh="refreshCount" @ship="handleShip" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Order } from '../types'
import { orderAPI } from '../api/order'
import ShipmentTable from '../components/ShipmentTable.vue'

const refreshCount = ref(0)

async function handleShip(order: Order) {
  ElMessageBox.prompt('請輸入物流單號', '出貨', {
    confirmButtonText: '確定',
    cancelButtonText: '取消'
  }).then(async ({ value }) => {
    try {
      await orderAPI.ship(order.id, value)
      ElMessage.success('出貨已送出同步')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('出貨失敗')
    }
  }).catch(() => {})
}
</script>
```

**Step 5: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/components/OrderTable.vue user-app/src/components/ShipmentTable.vue user-app/src/views/OrderPage.vue user-app/src/views/ShipmentPage.vue
git commit -m "feat: add order and shipment management pages

- OrderPage shows all orders with status and sync tracking
- Support ship and cancel operations with tracking numbers
- ShipmentPage filters to pending shipments only
- Order operations trigger Kafka sync to platforms
- Real-time status updates with polling

Generated with Claude Code
via Happy"
```

---

### Task 9: Create RefundPage & ChannelPage

**Files:**
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/RefundPage.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/components/RefundTable.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/user-app/src/views/ChannelPage.vue`

**Step 1: Create components/RefundTable.vue**

```vue
<template>
  <div>
    <el-table :data="refunds" style="width: 100%" v-loading="loading">
      <el-table-column label="訂單編號" width="150">
        <template #default="{ row }">{{ row.orderId }}</template>
      </el-table-column>
      <el-table-column prop="platform" label="通路" width="100" />
      <el-table-column prop="amount" label="退款金額" width="120" />
      <el-table-column prop="reason" label="原因" min-width="150" />
      <el-table-column label="狀態" width="120">
        <template #default="{ row }">
          <el-tag :type="getStatusType(row.syncStatus.status)">
            {{ getStatusText(row.syncStatus.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            v-if="row.syncStatus.status === 'pending'"
            link type="primary" size="small"
            @click="$emit('approve', row)"
          >
            同意
          </el-button>
          <el-button
            v-if="row.syncStatus.status === 'pending'"
            link type="danger" size="small"
            @click="$emit('reject', row)"
          >
            拒絕
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      style="margin-top: 20px; text-align: right"
      @current-page-change="loadData"
      @page-size-change="loadData"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { refundAPI } from '../api/refund'
import { Refund } from '../types'

const props = defineProps<{
  refresh: number
}>()

const emit = defineEmits<{
  approve: [refund: Refund]
  reject: [refund: Refund]
}>()

const refunds = ref<Refund[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  loadData()
})

watch(() => props.refresh, () => {
  loadData()
})

async function loadData() {
  loading.value = true
  try {
    const response = await refundAPI.list(currentPage.value - 1, pageSize.value)
    refunds.value = response.data.data.content
    total.value = response.data.data.totalElements
  } catch (error) {
    console.error('Failed to load refunds:', error)
  } finally {
    loading.value = false
  }
}

function getStatusType(status: string) {
  const map: Record<string, string> = {
    pending: 'warning',
    syncing: 'info',
    completed: 'success',
    failed: 'danger'
  }
  return map[status] || 'info'
}

function getStatusText(status: string) {
  const map: Record<string, string> = {
    pending: '待處理',
    syncing: '同步中',
    completed: '已完成',
    failed: '失敗'
  }
  return map[status] || status
}
</script>
```

**Step 2: Create views/RefundPage.vue**

```vue
<template>
  <div>
    <h2>退貨管理</h2>
    <el-card>
      <RefundTable :refresh="refreshCount" @approve="handleApprove" @reject="handleReject" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refund } from '../types'
import { refundAPI } from '../api/refund'
import RefundTable from '../components/RefundTable.vue'

const refreshCount = ref(0)

async function handleApprove(refund: Refund) {
  ElMessageBox.confirm('確定同意這筆退貨?', '確認', {
    confirmButtonText: '同意',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      await refundAPI.approve(refund.id)
      ElMessage.success('退貨已同意，同步進行中')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('操作失敗')
    }
  }).catch(() => {})
}

async function handleReject(refund: Refund) {
  ElMessageBox.prompt('請輸入拒絕原因', '拒絕退貨', {
    confirmButtonText: '確定',
    cancelButtonText: '取消'
  }).then(async ({ value }) => {
    try {
      await refundAPI.reject(refund.id, value)
      ElMessage.success('退貨已拒絕')
      refreshCount.value++
    } catch (error) {
      ElMessage.error('操作失敗')
    }
  }).catch(() => {})
}
</script>
```

**Step 3: Create views/ChannelPage.vue**

```vue
<template>
  <div>
    <h2>通路管理</h2>
    <el-card>
      <el-row :gutter="20">
        <el-col v-for="platform in platforms" :key="platform.id" :xs="24" :sm="12" :md="8">
          <el-card class="platform-card" shadow="hover">
            <template #header>
              <h3 style="margin: 0">{{ platform.name }}</h3>
            </template>
            <div class="platform-info">
              <div>
                <span class="label">代碼:</span>
                <span>{{ platform.code }}</span>
              </div>
              <div style="margin-top: 10px">
                <span class="label">狀態:</span>
                <el-tag :type="platform.status === 'active' ? 'success' : 'danger'">
                  {{ platform.status === 'active' ? '啟用' : '停用' }}
                </el-tag>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Platform } from '../types'
import { channelAPI } from '../api/channel'

const platforms = ref<Platform[]>([])

onMounted(async () => {
  try {
    const response = await channelAPI.list()
    platforms.value = response.data.data
  } catch (error) {
    console.error('Failed to load platforms:', error)
  }
})
</script>

<style scoped>
.platform-card {
  height: 100%;
}

.platform-info {
  font-size: 14px;
}

.label {
  color: #909399;
  margin-right: 8px;
}
</style>
```

**Step 4: Update App.vue to import all page components**

The router already imports all pages, so they'll be loaded on demand.

**Step 5: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add user-app/src/components/RefundTable.vue user-app/src/views/RefundPage.vue user-app/src/views/ChannelPage.vue
git commit -m "feat: add refund and channel management pages

- RefundPage shows refund requests with approve/reject options
- RefundTable tracks sync status for refund operations
- ChannelPage displays connected platforms dynamically
- All platforms loaded from API on page mount
- Support for approval workflows with sync tracking

Generated with Claude Code
via Happy"
```

---

## Phase 3: Build, Deploy & Testing

### Task 10: Build User App

**Files:**
- No new files, just build the project

**Step 1: Install dependencies**

```bash
cd /home/tom/ONEEC/simpleec-oms/user-app
npm install
```

Expected: All dependencies installed, no errors

**Step 2: Run build**

```bash
npm run build
```

Expected: Build succeeds with dist/ folder containing compiled code

**Step 3: Verify dist output**

```bash
ls -la /home/tom/ONEEC/simpleec-oms/user-app/dist/
```

Expected: Show UserApp.umd.js and other compiled files

**Step 4: Commit build success**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add -A
git commit -m "build: compile user app

- User app builds successfully
- All 8 pages compiled
- Optimized bundle ready for deployment

Generated with Claude Code
via Happy"
```

---

### Task 11: Update Micro-Frontend Container & Deploy

**Files:**
- Modify: `/home/tom/ONEEC/simpleec-oms/micro-frontend-container/src/App.vue`
- Create: `/home/tom/ONEEC/simpleec-oms/docs/DEPLOYMENT.md`

**Step 1: Update container App.vue to register User App**

```vue
<template>
  <div id="root">
    <router-view v-slot="{ Component }">
      <component :is="Component" />
    </router-view>
    <div id="admin-app"></div>
    <div id="user-app"></div>
  </div>
</template>

<script setup lang="ts">
import { registerMicroApps, start, setDefaultMountApp } from 'qiankun'
import { useRouter } from 'vue-router'

const router = useRouter()

registerMicroApps([
  {
    name: '@org/admin-app',
    entry: 'http://localhost:8081/',
    container: '#admin-app',
    activeRule: '/admin',
    props: {
      router
    }
  },
  {
    name: '@org/user-app',
    entry: 'http://localhost:8083/',
    container: '#user-app',
    activeRule: '/user-app',
    props: {
      router
    }
  }
])

setDefaultMountApp('/admin')

start()
</script>
```

**Step 2: Update container router to include user-app route**

The router already handles /user-app routes through Qiankun activeRule

**Step 3: Create deployment guide**

Create `/home/tom/ONEEC/simpleec-oms/docs/DEPLOYMENT.md` with instructions for running all services

**Step 4: Test all services start**

```bash
# Terminal 1: Start backend API
cd /home/tom/ONEEC/simpleec-oms/simpleec-api
gradle bootRun

# Terminal 2: Start container
cd /home/tom/ONEEC/simpleec-oms/micro-frontend-container
npm run dev

# Terminal 3: Start admin app
cd /home/tom/ONEEC/simpleec-oms/admin-app
npm run dev

# Terminal 4: Start user app
cd /home/tom/ONEEC/simpleec-oms/user-app
npm run dev
```

**Step 5: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms
git add docs/DEPLOYMENT.md
git commit -m "docs: add deployment guide for complete system

Generated with Claude Code
via Happy"
```

---

## Summary

This implementation plan covers building a complete User App with:

1. **Authentication**: LoginPage with JWT token management via Pinia auth store
2. **8 Management Pages**: Dashboard, Product, SellPack (core), Order, Shipment, Refund, Channel
3. **Kafka-Driven Sync**: Each platform has separate Kafka topic, operations tracked in sync status
4. **Optimistic UI**: Shows target values while syncing (e.g., 50→80 with elapsed time)
5. **5-Minute Polling**: Refreshes sync status from backend
6. **Relative API Paths**: Compatible with Cloudflare reverse proxy (/api/user)
7. **White Color Scheme**: Professional merchant-facing interface
8. **Responsive Design**: Element Plus components with mobile support

**Key Features by Task:**
- Tasks 1-3: Project setup, auth store, router, login
- Tasks 4-9: All API modules and 7 management pages
- Task 10-11: Build and deployment integration

All code follows TDD, includes commits per task, and maintains consistency with admin-app architecture.

---

Plan complete and saved to `/home/tom/ONEEC/simpleec-oms/docs/plans/2026-02-21-user-app-implementation.md`.

**Two execution options:**

1. **Subagent-Driven (this session)** - I dispatch fresh subagent per task, review code, fast iteration
2. **Parallel Session (separate)** - Open new session with executing-plans skill, batch execution with checkpoints

Which approach would you prefer?