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
