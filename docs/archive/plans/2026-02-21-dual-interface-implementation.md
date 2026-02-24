# SimpleEC OMS Dual-Interface Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a production-ready Admin + User dual microapp system using qiankun, Vue3, and backend API extensions.

**Architecture:** Qiankun container hosts two isolated Vue3 SPAs (Admin and User) that communicate via event bus and share the same PostgreSQL database through extended Spring Boot REST APIs. Admin manages merchants/accounts/platforms; User provides account login and full CRUD of business data with 5-minute polling refresh.

**Tech Stack:** qiankun 2.8, Vue3, Vite5, Element Plus, Pinia, TypeScript, axios, Spring Boot (backend), PostgreSQL

**Timeline Estimate:**
- Phase 1 (Container): 2-3 days
- Phase 2 (Admin): 3-4 days
- Phase 3 (User): 5-7 days
- Phase 4 (API): 3-4 days
- Phase 5 (Testing): 2-3 days
- **Total: 15-21 days**

---

## Phase 1: Qiankun Container Setup

### Task 1.1: Initialize Qiankun Container Project

**Files:**
- Create: `micro-frontend-container/package.json`
- Create: `micro-frontend-container/vite.config.ts`
- Create: `micro-frontend-container/src/main.ts`
- Create: `micro-frontend-container/src/App.vue`
- Create: `micro-frontend-container/index.html`

**Step 1: Create container package.json**

```bash
mkdir -p /home/tom/ONEEC/simpleec-oms/micro-frontend-container
cd /home/tom/ONEEC/simpleec-oms/micro-frontend-container
npm init -y
```

Then edit `package.json`:

```json
{
  "name": "micro-frontend-container",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite --host 0.0.0.0",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "qiankun": "^2.8.0",
    "pinia": "^2.1.0",
    "axios": "^1.6.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.0.0",
    "typescript": "^5.3.0",
    "@vue/tsconfig": "^0.5.0"
  }
}
```

**Step 2: Install dependencies**

```bash
npm install
```

Expected: All packages installed, node_modules created.

**Step 3: Create vite.config.ts**

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 8080,
    cors: true,
    headers: {
      'Access-Control-Allow-Origin': '*'
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets'
  }
})
```

**Step 4: Create index.html**

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SimpleEC OMS</title>
</head>
<body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
</body>
</html>
```

**Step 5: Create src/main.ts**

```typescript
import { createApp } from 'vue'
import App from './App.vue'

const app = createApp(App)
app.mount('#app')
```

**Step 6: Create src/App.vue**

```vue
<template>
  <div class="container">
    <header>
      <h1>SimpleEC OMS</h1>
      <nav>
        <a href="#/admin/">Admin</a>
        <a href="#/app/">User Portal</a>
      </nav>
    </header>
    <main id="qiankun-container"></main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { registerMicroApps, start } from 'qiankun'

onMounted(() => {
  registerMicroApps([
    {
      name: '@simpleec/admin',
      entry: 'http://localhost:8081',
      container: '#qiankun-container',
      activeRule: '#/admin/'
    },
    {
      name: '@simpleec/user',
      entry: 'http://localhost:8082',
      container: '#qiankun-container',
      activeRule: '#/app/'
    }
  ])
  start()
})
</script>

<style scoped>
.container {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

header {
  background: #333;
  color: white;
  padding: 1rem;
}

header nav a {
  color: white;
  margin-right: 1rem;
  text-decoration: none;
}

main {
  flex: 1;
  overflow: auto;
}
</style>
```

**Step 7: Test container runs**

```bash
npm run dev
```

Expected: Server running on http://localhost:8080, shows header with Admin/User links.

**Step 8: Commit**

```bash
cd /home/tom/ONEEC/simpleec-oms/micro-frontend-container
git init
git add .
git commit -m "feat: initialize qiankun container with basic structure"
```

---

### Task 1.2: Create Shared State Management

**Files:**
- Create: `micro-frontend-container/src/stores/shared.ts`
- Create: `micro-frontend-container/src/sharedState.ts`

**Step 1: Create Pinia shared store**

```typescript
// src/stores/shared.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSharedStore = defineStore('shared', () => {
  const currentUser = ref<any>(null)
  const pollingSettings = ref({
    enabled: true,
    interval: 300000 // 5 minutes
  })

  function setCurrentUser(user: any) {
    currentUser.value = user
  }

  function updatePollingSettings(settings: any) {
    pollingSettings.value = { ...pollingSettings.value, ...settings }
  }

  return {
    currentUser,
    pollingSettings,
    setCurrentUser,
    updatePollingSettings
  }
})
```

**Step 2: Create event bus**

```typescript
// src/sharedState.ts
type EventListener = (...args: any[]) => void
type EventMap = Record<string, EventListener[]>

class EventBus {
  private events: EventMap = {}

  on(event: string, listener: EventListener) {
    if (!this.events[event]) {
      this.events[event] = []
    }
    this.events[event].push(listener)
  }

  off(event: string, listener: EventListener) {
    if (!this.events[event]) return
    this.events[event] = this.events[event].filter(l => l !== listener)
  }

  emit(event: string, ...args: any[]) {
    if (!this.events[event]) return
    this.events[event].forEach(listener => listener(...args))
  }
}

export const eventBus = new EventBus()
```

**Step 3: Commit**

```bash
git add src/stores/shared.ts src/sharedState.ts
git commit -m "feat: add shared state management with Pinia and event bus"
```

---

## Phase 2: Admin Application Implementation

### Task 2.1: Create Admin App Skeleton

**Files:**
- Create: `admin-app/package.json`
- Create: `admin-app/vite.config.ts`
- Create: `admin-app/src/main.ts`
- Create: `admin-app/src/App.vue`
- Create: `admin-app/src/router/index.ts`

**Step 1: Create admin app directory and init**

```bash
mkdir -p /home/tom/ONEEC/simpleec-oms/admin-app/src/router
mkdir -p /home/tom/ONEEC/simpleec-oms/admin-app/src/views
mkdir -p /home/tom/ONEEC/simpleec-oms/admin-app/src/api
mkdir -p /home/tom/ONEEC/simpleec-oms/admin-app/src/stores
mkdir -p /home/tom/ONEEC/simpleec-oms/admin-app/src/components
cd /home/tom/ONEEC/simpleec-oms/admin-app
npm init -y
```

**Step 2: Edit package.json**

```json
{
  "name": "@simpleec/admin",
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite --port 8081 --host 0.0.0.0",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "^3.4.0",
    "vue-router": "^4.2.0",
    "pinia": "^2.1.0",
    "axios": "^1.6.0",
    "element-plus": "^2.4.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.0.0",
    "vite": "^5.0.0",
    "typescript": "^5.3.0"
  }
}
```

**Step 3: Install dependencies**

```bash
npm install
```

**Step 4: Create vite.config.ts**

```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 8081,
    cors: true
  },
  build: {
    outDir: 'dist',
    library: {
      entry: 'src/main.ts',
      name: 'AdminApp',
      formats: ['umd']
    }
  }
})
```

**Step 5: Create src/router/index.ts**

```typescript
import { createRouter, createWebHashHistory, RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('../views/DashboardPage.vue')
  },
  {
    path: '/merchant',
    name: 'Merchant',
    component: () => import('../views/MerchantPage.vue')
  },
  {
    path: '/account',
    name: 'Account',
    component: () => import('../views/AccountPage.vue')
  },
  {
    path: '/platform',
    name: 'Platform',
    component: () => import('../views/PlatformPage.vue')
  },
  {
    path: '/monitor',
    name: 'Monitor',
    component: () => import('../views/MonitorPage.vue')
  }
]

export const router = createRouter({
  history: createWebHashHistory('/admin/'),
  routes
})
```

**Step 6: Create src/main.ts**

```typescript
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'

let app: any

export async function bootstrap() {
  console.log('Admin app bootstrap')
}

export async function mount(props: any) {
  console.log('Admin app mount', props)
  app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.use(ElementPlus)
  app.mount(props.container || '#app')
}

export async function unmount() {
  console.log('Admin app unmount')
  app?.unmount()
  app = null
}

// Standalone mode for local development
if (!window.__POWERED_BY_QIANKUN__) {
  bootstrap().then(() => mount({ container: '#app' }))
}
```

**Step 7: Create src/App.vue**

```vue
<template>
  <div class="admin-layout">
    <el-container>
      <el-aside width="200px" class="sidebar">
        <el-menu router :default-active="activeMenu">
          <el-menu-item index="/" @click="navigate('/')">
            <span>仪表板</span>
          </el-menu-item>
          <el-menu-item index="/merchant" @click="navigate('/merchant')">
            <span>商家管理</span>
          </el-menu-item>
          <el-menu-item index="/account" @click="navigate('/account')">
            <span>账户管理</span>
          </el-menu-item>
          <el-menu-item index="/platform" @click="navigate('/platform')">
            <span>通路管理</span>
          </el-menu-item>
          <el-menu-item index="/monitor" @click="navigate('/monitor')">
            <span>系统监控</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="header">
          <h2>SimpleEC OMS - Admin</h2>
        </el-header>
        <el-main class="main-content">
          <RouterView />
        </el-main>
      </el-container>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const activeMenu = computed(() => {
  return router.currentRoute.value.path || '/'
})

function navigate(path: string) {
  router.push(path)
}
</script>

<style scoped>
.admin-layout {
  height: 100vh;
  display: flex;
}

.sidebar {
  background: #f0f2f5;
  border-right: 1px solid #ddd;
}

.header {
  background: #fff;
  border-bottom: 1px solid #ddd;
  display: flex;
  align-items: center;
  padding: 0 20px;
}

.main-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
</style>
```

**Step 8: Create index.html for standalone development**

```bash
cat > /home/tom/ONEEC/simpleec-oms/admin-app/index.html << 'EOF'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Admin - SimpleEC OMS</title>
</head>
<body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
</body>
</html>
EOF
```

**Step 9: Test admin app runs**

```bash
npm run dev
```

Expected: Admin app running on http://localhost:8081, shows sidebar menu.

**Step 10: Commit**

```bash
git init
git add .
git commit -m "feat: scaffold admin app with Vue3, router, Element Plus"
```

---

### Task 2.2: Implement Merchant Management Page

**Files:**
- Create: `admin-app/src/views/MerchantPage.vue`
- Create: `admin-app/src/components/MerchantTable.vue`
- Create: `admin-app/src/components/MerchantForm.vue`
- Create: `admin-app/src/api/merchant.ts`
- Create: `admin-app/src/types.ts`

**Step 1: Define TypeScript types**

```typescript
// src/types.ts
export interface Merchant {
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

export interface PaginatedResponse<T> {
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

**Step 2: Create merchant API service**

```typescript
// src/api/merchant.ts
import axios from 'axios'
import { Merchant, PaginatedResponse } from '../types'

const API_BASE = 'http://localhost:8082/api/admin'

export const merchantAPI = {
  list(page = 1, pageSize = 20) {
    return axios.get<PaginatedResponse<Merchant>>(
      `${API_BASE}/merchant?page=${page}&pageSize=${pageSize}`
    )
  },

  get(id: string) {
    return axios.get<{ code: number; data: Merchant }>(
      `${API_BASE}/merchant/${id}`
    )
  },

  create(data: Partial<Merchant>) {
    return axios.post<{ code: number; data: Merchant }>(
      `${API_BASE}/merchant`,
      data
    )
  },

  update(id: string, data: Partial<Merchant>) {
    return axios.put<{ code: number; data: Merchant }>(
      `${API_BASE}/merchant/${id}`,
      data
    )
  },

  delete(id: string) {
    return axios.delete<{ code: number }>(
      `${API_BASE}/merchant/${id}`
    )
  }
}
```

**Step 3: Create MerchantTable component**

```vue
<!-- src/components/MerchantTable.vue -->
<template>
  <el-table :data="merchants" stripe border>
    <el-table-column prop="id" label="ID" width="120" />
    <el-table-column prop="merchant_name" label="商家名称" />
    <el-table-column prop="merchant_email" label="邮箱" />
    <el-table-column prop="vip_level" label="VIP等级" width="80" />
    <el-table-column prop="status" label="状态" width="80" />
    <el-table-column label="操作" width="150">
      <template #default="{ row }">
        <el-button type="primary" size="small" @click="handleEdit(row)">
          编辑
        </el-button>
        <el-button type="danger" size="small" @click="handleDelete(row)">
          删除
        </el-button>
      </template>
    </el-table-column>
  </el-table>

  <div class="pagination-container">
    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      @change="handlePageChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Merchant } from '../types'
import { merchantAPI } from '../api/merchant'

const props = defineProps<{
  refresh?: number
}>()

const emit = defineEmits<{
  edit: [merchant: Merchant]
}>()

const merchants = ref<Merchant[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)

async function loadMerchants() {
  loading.value = true
  try {
    const res = await merchantAPI.list(currentPage.value, pageSize.value)
    merchants.value = res.data.data.items
    total.value = res.data.data.total
  } catch (err) {
    ElMessage.error('加载商家列表失败')
    console.error(err)
  } finally {
    loading.value = false
  }
}

function handlePageChange() {
  loadMerchants()
}

function handleEdit(row: Merchant) {
  emit('edit', row)
}

async function handleDelete(row: Merchant) {
  ElMessageBox.confirm(
    `确认删除商家 ${row.merchant_name}?`,
    'Warning',
    { type: 'warning' }
  )
    .then(async () => {
      await merchantAPI.delete(row.id)
      ElMessage.success('删除成功')
      loadMerchants()
    })
    .catch(() => {})
}

onMounted(() => {
  loadMerchants()
})

// Watch for refresh prop
watch(() => props.refresh, () => {
  loadMerchants()
})
</script>

<style scoped>
.pagination-container {
  margin-top: 20px;
  text-align: right;
}
</style>
```

**Step 4: Create MerchantForm component**

```vue
<!-- src/components/MerchantForm.vue -->
<template>
  <el-dialog v-model="visible" title="商家信息" width="600px" @close="handleClose">
    <el-form ref="form" :model="formData" label-width="120px">
      <el-form-item label="ID" v-if="!isEdit">
        <el-input v-model="formData.id" />
      </el-form-item>
      <el-form-item label="商家名称">
        <el-input v-model="formData.merchant_name" />
      </el-form-item>
      <el-form-item label="邮箱">
        <el-input v-model="formData.merchant_email" type="email" />
      </el-form-item>
      <el-form-item label="电话">
        <el-input v-model="formData.merchant_phone_number" />
      </el-form-item>
      <el-form-item label="VIP等级">
        <el-input-number v-model="formData.vip_level" :min="0" />
      </el-form-item>
      <el-form-item label="城市">
        <el-input v-model="formData.address_city" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="formData.status">
          <el-option label="Active" value="active" />
          <el-option label="Inactive" value="inactive" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleClose">取消</el-button>
      <el-button type="primary" @click="handleSubmit">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Merchant } from '../types'
import { merchantAPI } from '../api/merchant'

const props = defineProps<{
  merchant?: Merchant | null
}>()

const emit = defineEmits<{
  saved: [merchant: Merchant]
  close: []
}>()

const visible = ref(false)
const isEdit = ref(false)
const loading = ref(false)

const formData = ref<Partial<Merchant>>({
  id: '',
  merchant_name: '',
  merchant_email: '',
  merchant_phone_number: '',
  address_city: '',
  vip_level: 0,
  status: 'active'
})

watch(() => props.merchant, (newVal) => {
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
    id: '',
    merchant_name: '',
    merchant_email: '',
    merchant_phone_number: '',
    address_city: '',
    vip_level: 0,
    status: 'active'
  }
  isEdit.value = false
}

async function handleSubmit() {
  loading.value = true
  try {
    let result
    if (isEdit.value) {
      await merchantAPI.update(formData.value.id!, formData.value)
      result = formData.value as Merchant
    } else {
      const res = await merchantAPI.create(formData.value)
      result = res.data.data
    }
    ElMessage.success(isEdit.value ? '更新成功' : '创建成功')
    emit('saved', result)
    handleClose()
  } catch (err) {
    ElMessage.error('操作失败')
    console.error(err)
  } finally {
    loading.value = false
  }
}

function handleClose() {
  visible.value = false
  emit('close')
}

export function open() {
  resetForm()
  visible.value = true
}
</script>
```

**Step 5: Create MerchantPage view**

```vue
<!-- src/views/MerchantPage.vue -->
<template>
  <div>
    <div class="toolbar">
      <el-button type="primary" @click="handleNewMerchant">
        新增商家
      </el-button>
    </div>
    <MerchantTable :refresh="refreshCount" @edit="handleEditMerchant" />
    <MerchantForm :merchant="selectedMerchant" @saved="handleSaved" @close="selectedMerchant = null" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Merchant } from '../types'
import MerchantTable from '../components/MerchantTable.vue'
import MerchantForm from '../components/MerchantForm.vue'

const selectedMerchant = ref<Merchant | null>(null)
const refreshCount = ref(0)

function handleNewMerchant() {
  selectedMerchant.value = null
}

function handleEditMerchant(merchant: Merchant) {
  selectedMerchant.value = merchant
}

function handleSaved() {
  refreshCount.value++
  selectedMerchant.value = null
}
</script>

<style scoped>
.toolbar {
  margin-bottom: 20px;
}
</style>
```

**Step 6: Test merchant page**

```bash
npm run dev
```

Navigate to http://localhost:8081/#/merchant - should show table with loading state (API not ready yet).

**Step 7: Commit**

```bash
git add src/types.ts src/api/merchant.ts src/components/MerchantTable.vue src/components/MerchantForm.vue src/views/MerchantPage.vue
git commit -m "feat: implement merchant management page with CRUD operations"
```

---

### Task 2.3: Implement Account Management Page

**Files:**
- Create: `admin-app/src/views/AccountPage.vue`
- Create: `admin-app/src/components/AccountTable.vue`
- Create: `admin-app/src/components/AccountForm.vue`
- Create: `admin-app/src/api/account.ts`

**Step 1: Create account API service** (similar to merchant.ts)

```typescript
// src/api/account.ts
import axios from 'axios'
import { PaginatedResponse } from '../types'

export interface Account {
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

const API_BASE = 'http://localhost:8082/api/admin'

export const accountAPI = {
  list(page = 1, pageSize = 20, merchantId?: string) {
    const query = merchantId ? `&merchant_id=${merchantId}` : ''
    return axios.get<PaginatedResponse<Account>>(
      `${API_BASE}/account?page=${page}&pageSize=${pageSize}${query}`
    )
  },

  get(id: string) {
    return axios.get<{ code: number; data: Account }>(
      `${API_BASE}/account/${id}`
    )
  },

  create(data: Partial<Account>) {
    return axios.post<{ code: number; data: Account }>(
      `${API_BASE}/account`,
      data
    )
  },

  update(id: string, data: Partial<Account>) {
    return axios.put<{ code: number; data: Account }>(
      `${API_BASE}/account/${id}`,
      data
    )
  },

  resetPassword(id: string, newPassword: string) {
    return axios.post<{ code: number }>(
      `${API_BASE}/account/${id}/reset-password`,
      { password: newPassword }
    )
  },

  delete(id: string) {
    return axios.delete<{ code: number }>(
      `${API_BASE}/account/${id}`
    )
  }
}
```

**Step 2-5: Create AccountTable, AccountForm components and AccountPage view**

(Follow same pattern as MerchantTable/Form - implement table with pagination, form with edit/create dialogs, page to tie them together)

**Step 6: Commit**

```bash
git add src/api/account.ts src/components/AccountTable.vue src/components/AccountForm.vue src/views/AccountPage.vue
git commit -m "feat: implement account management page"
```

---

### Task 2.4: Implement Platform & Monitor Pages

**Files:**
- Create: `admin-app/src/views/PlatformPage.vue`
- Create: `admin-app/src/views/MonitorPage.vue`
- Create: `admin-app/src/components/PlatformTable.vue`
- Create: `admin-app/src/api/platform.ts`
- Create: `admin-app/src/api/monitor.ts`

(Follow same pattern as merchant/account implementation)

**Commit:**

```bash
git add src/views/PlatformPage.vue src/views/MonitorPage.vue src/api/platform.ts src/api/monitor.ts
git commit -m "feat: add platform and monitor management pages"
```

---

### Task 2.5: Implement Admin Dashboard Page

**Files:**
- Create: `admin-app/src/views/DashboardPage.vue`

```vue
<!-- src/views/DashboardPage.vue -->
<template>
  <div>
    <h2>系统仪表板</h2>
    <el-row :gutter="20">
      <el-col :xs="24" :sm="12" :md="6">
        <StatCard title="商家总数" :value="stats.merchantCount" color="#409eff" />
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <StatCard title="账户总数" :value="stats.accountCount" color="#67c23a" />
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <StatCard title="通路数量" :value="stats.platformCount" color="#e6a23c" />
      </el-col>
      <el-col :xs="24" :sm="12" :md="6">
        <StatCard title="订单总数" :value="stats.orderCount" color="#f56c6c" />
      </el-col>
    </el-row>

    <el-card style="margin-top: 20px">
      <h3>最近操作</h3>
      <!-- Activity log component -->
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import StatCard from '../components/StatCard.vue'

const stats = ref({
  merchantCount: 0,
  accountCount: 0,
  platformCount: 0,
  orderCount: 0
})

onMounted(() => {
  // Load stats from API
})
</script>
```

**Commit:**

```bash
git add src/views/DashboardPage.vue src/components/StatCard.vue
git commit -m "feat: add admin dashboard with statistics"
```

---

## Phase 3: User Application Implementation

(Similar structure to Admin, but with more pages and login functionality)

### Task 3.1: Create User App Skeleton with Login

**Files:**
- `user-app/src/main.ts` - qiankun lifecycle
- `user-app/src/router/index.ts` - routes with login guard
- `user-app/src/views/LoginPage.vue` - login form
- `user-app/src/api/auth.ts` - JWT auth
- `user-app/src/stores/auth.ts` - auth state

**Key Implementation:**

```typescript
// src/api/auth.ts
import axios from 'axios'

const API_BASE = 'http://localhost:8082/api/user'

export const authAPI = {
  login(email: string, password: string) {
    return axios.post<{ code: number; data: { token: string } }>(
      `${API_BASE}/login`,
      { email, password }
    )
  }
}
```

```typescript
// src/stores/auth.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authAPI } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('token') || '')
  const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))

  async function login(email: string, password: string) {
    const res = await authAPI.login(email, password)
    token.value = res.data.data.token
    localStorage.setItem('token', token.value)
    return token.value
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  return { token, user, login, logout }
})
```

**Commit:**

```bash
git add src/main.ts src/router/index.ts src/views/LoginPage.vue src/api/auth.ts src/stores/auth.ts
git commit -m "feat: implement user app with JWT authentication"
```

---

### Task 3.2-3.8: Implement User Data Pages

(Product, Channel, SellPack, Order, Shipment, Refund pages - follow merchant/account pattern with polling mechanism)

Each page should include:
- Table component with pagination
- Form/Modal component for CRUD
- Custom hook `usePolling()` for 5-minute refresh

---

## Phase 4: Backend API Extensions

### Task 4.1: Extend Spring Boot with Admin Endpoints

**Files:**
- Create: `src/main/java/com/simpleec/api/controller/AdminMerchantController.java`
- Create: `src/main/java/com/simpleec/api/controller/AdminAccountController.java`
- Create: `src/main/java/com/simpleec/api/controller/AdminPlatformController.java`

**Step 1: Create AdminMerchantController**

```java
@RestController
@RequestMapping("/api/admin/merchant")
@CrossOrigin(origins = "*")
public class AdminMerchantController {

  @Autowired
  private MerchantService merchantService;

  @GetMapping
  public ApiResponse<PageResponse<MerchantDTO>> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize
  ) {
    PageResponse<MerchantDTO> data = merchantService.listPage(page, pageSize);
    return ApiResponse.success(data);
  }

  @PostMapping
  public ApiResponse<MerchantDTO> create(@RequestBody MerchantDTO dto) {
    MerchantDTO result = merchantService.create(dto);
    return ApiResponse.success(result);
  }

  @PutMapping("/{id}")
  public ApiResponse<MerchantDTO> update(
      @PathVariable String id,
      @RequestBody MerchantDTO dto
  ) {
    MerchantDTO result = merchantService.update(id, dto);
    return ApiResponse.success(result);
  }

  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable String id) {
    merchantService.delete(id);
    return ApiResponse.success();
  }
}
```

**Step 2: Implement other admin controllers similarly**

**Commit:**

```bash
git add src/main/java/com/simpleec/api/controller/Admin*.java
git commit -m "feat: add admin API endpoints for merchant/account/platform CRUD"
```

---

### Task 4.2: Extend Spring Boot with User Endpoints

**Files:**
- Create: `src/main/java/com/simpleec/api/controller/UserAuthController.java`
- Create: `src/main/java/com/simpleec/api/controller/UserProductController.java`
- Create: `src/main/java/com/simpleec/api/controller/UserOrderController.java`
- Create: `src/main/java/com/simpleec/api/security/JwtFilter.java`

**Key Implementation:**

```java
@RestController
@RequestMapping("/api/user/login")
public class UserAuthController {

  @PostMapping
  public ApiResponse<TokenResponse> login(@RequestBody LoginRequest request) {
    String token = jwtService.generateToken(request.getEmail());
    return ApiResponse.success(new TokenResponse(token));
  }
}

@Component
public class JwtFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
    String token = request.getHeader("Authorization");
    if (token != null && token.startsWith("Bearer ")) {
      String actualToken = token.substring(7);
      if (jwtService.validateToken(actualToken)) {
        // Set security context
        chain.doFilter(request, response);
        return;
      }
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }
}
```

**Commit:**

```bash
git add src/main/java/com/simpleec/api/controller/User*.java src/main/java/com/simpleec/api/security/JwtFilter.java
git commit -m "feat: add user API endpoints with JWT authentication"
```

---

## Phase 5: Testing & Deployment

### Task 5.1: Write API Integration Tests

**Files:**
- Create: `src/test/java/com/simpleec/api/controller/AdminControllerTest.java`
- Create: `src/test/java/com/simpleec/api/controller/UserControllerTest.java`

```java
@SpringBootTest
@AutoConfigureMockMvc
public class AdminMerchantControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  public void testListMerchants() throws Exception {
    mockMvc.perform(get("/api/admin/merchant?page=1&pageSize=20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));
  }

  @Test
  public void testCreateMerchant() throws Exception {
    String json = """
        {
          "id": "test001",
          "merchant_name": "Test Merchant",
          "merchant_email": "test@example.com",
          "merchant_phone_number": "0912345678",
          "tax_id_number": "12345678",
          "address_city": "Taipei",
          "address_region": "Taipei",
          "address_country": "Taiwan",
          "address_zip": "10000",
          "address_line1": "123 Main St",
          "address_line2": "",
          "address_phone_number": "0912345678",
          "vip_level": 0,
          "user_local_time_zone": "Asia/Taipei",
          "payer_name": "Test",
          "payer_email": "payer@example.com",
          "payer_phone_number": "0912345678",
          "status": "active"
        }
        """;

    mockMvc.perform(post("/api/admin/merchant")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));
  }
}
```

**Commit:**

```bash
git add src/test/java/com/simpleec/api/controller/
git commit -m "test: add integration tests for admin and user API endpoints"
```

---

### Task 5.2: Build & Deploy Container

```bash
# Build container
cd /home/tom/ONEEC/simpleec-oms/micro-frontend-container
npm run build

# Build admin app
cd ../admin-app
npm run build

# Build user app
cd ../user-app
npm run build

# Deploy (copy dist to web server or Docker)
```

**Commit:**

```bash
git add docker-compose.yml Dockerfile
git commit -m "chore: add Docker configuration for deployment"
```

---

### Task 5.3: End-to-End Testing

**Test Scenarios:**

```
Admin Workflow:
1. Navigate to /admin/merchant
2. Create new merchant
3. Edit merchant info
4. View merchant list with pagination
5. Delete merchant

User Workflow:
1. Navigate to /app/login
2. Login with email/password
3. View dashboard with 5-minute auto-refresh
4. Create new product
5. Create sell pack linking product to channel
6. View orders with pagination
7. Update order status
```

---

## 附录：常见任务清单

以下是需要完成但未在上述任务中详细展开的内容：

### Admin页面待完成
- [ ] DashboardPage - 系统统计、操作日志
- [ ] AccountPage - 账户CRUD、权限管理、密码重置
- [ ] PlatformPage - 通路CRUD、API配置
- [ ] MonitorPage - 统计数据、同步日志、失败日志

### User页面待完成
- [ ] DashboardPage - 订单统计、营收、通路分布图表
- [ ] ProductPage - 商品CRUD、SKU管理、条码管理
- [ ] ChannelPage - 通路列表、账户配置、同步日志
- [ ] SellPackPage - 赛场CRUD、批量操作
- [ ] OrderPage - 订单详情、状态更新、备注、批量导出
- [ ] ShipmentPage - 物流记录、追踪、状态日志
- [ ] RefundPage - 退货CRUD、退款处理、统计

### 共享库components待实现
- [ ] DataTable - 通用表格组件（排序、筛选）
- [ ] Pagination - 通用分页组件
- [ ] RefreshBar - 刷新/暂停控件
- [ ] Modal - 通用弹窗

### 共享Hook待实现
- [ ] usePolling - 5分钟轮询自定义Hook
- [ ] usePagination - 分页逻辑
- [ ] useAsyncData - 异步数据加载

### 后端API待完成
- [ ] 分页响应格式标准化
- [ ] JWT token验证中间件
- [ ] 全局错误处理
- [ ] 请求日志记录
- [ ] 权限验证（ACL）

---

## 执行建议

**推荐执行顺序：**
1. 完成Phase 1（容器基础）- 必须先跑起来
2. 完成Phase 2（Admin完整版本）- 内部平台，快速验证
3. 完成Phase 4（API扩展）- User依赖这些API
4. 完成Phase 3（User完整版本）- 客户门户
5. 完成Phase 5（测试部署）

**并行工作：**
- 前端多人：一人做Admin，一人做User，另一人做shared-lib
- 后端多人：一人做Admin端点，一人做User端点，一人做测试

---

**计划完成日期**：2026-02-21
**预计总工作量**：15-21天（4人全职）
