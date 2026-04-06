import { createRouter, createWebHashHistory, RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginPage.vue'),
    meta: { requiresAuth: false },
  },
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('../views/DashboardPage.vue'),
  },
  {
    path: '/order',
    name: 'Order',
    component: () => import('../views/OrderPage.vue'),
  },
  {
    path: '/channel',
    name: 'Channel',
    component: () => import('../views/ChannelPage.vue'),
  },
  {
    path: '/product',
    name: 'Product',
    component: () => import('../views/ProductPage.vue'),
  },
  {
    path: '/sellpack',
    name: 'SellPack',
    component: () => import('../views/SellPackPage.vue'),
  },
  {
    path: '/shipment',
    name: 'Shipment',
    component: () => import('../views/ShipmentPage.vue'),
  },
  {
    path: '/inventory',
    name: 'Inventory',
    component: () => import('../views/InventoryPage.vue'),
  },
  {
    path: '/refund',
    name: 'Refund',
    component: () => import('../views/RefundPage.vue'),
  },
  {
    path: '/reports',
    name: 'Reports',
    component: () => import('../views/ReportPage.vue'),
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('../views/SettingsPage.vue'),
  },
]

export const router = createRouter({
  history: createWebHashHistory('/'),
  routes,
})

router.beforeEach((to) => {
  const isLoginPage = to.meta.requiresAuth === false
  const token = localStorage.getItem('authToken')
  if (!isLoginPage && !token) {
    return { name: 'Login' }
  }
})
