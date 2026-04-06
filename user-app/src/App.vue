<template>
  <!-- Login page: no layout -->
  <RouterView v-if="isLoginPage" />

  <!-- Main layout -->
  <div v-else class="user-layout">
    <el-container style="height: 100vh;">
      <el-aside width="200px" class="sidebar">
        <div class="logo">SimpleEC OMS</div>
        <el-menu
          router
          :default-active="activeMenu"
          background-color="#001529"
          text-color="#ffffffa6"
          active-text-color="#ffffff"
        >
          <el-menu-item index="/" @click="navigate('/')">
            <span>儀表板</span>
          </el-menu-item>
          <el-menu-item index="/order" @click="navigate('/order')">
            <span>訂單管理</span>
          </el-menu-item>
          <el-menu-item index="/channel" @click="navigate('/channel')">
            <span>通路設定</span>
          </el-menu-item>
          <el-menu-item index="/product" @click="navigate('/product')">
            <span>商品管理</span>
          </el-menu-item>
          <el-menu-item index="/sellpack" @click="navigate('/sellpack')">
            <span>賣場商品</span>
          </el-menu-item>
          <el-menu-item index="/shipment" @click="navigate('/shipment')">
            <span>出貨管理</span>
          </el-menu-item>
          <el-menu-item index="/inventory" @click="navigate('/inventory')">
            <span>庫存管理</span>
          </el-menu-item>
          <el-menu-item index="/refund" @click="navigate('/refund')">
            <span>退貨管理</span>
          </el-menu-item>
          <el-menu-item index="/reports" @click="navigate('/reports')">
            <span>銷售報表</span>
          </el-menu-item>
          <el-menu-item index="/settings" @click="navigate('/settings')">
            <span>商家設定</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="header">
          <h2>商家後台</h2>
          <el-button text size="small" @click="handleLogout">登出</el-button>
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
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

const isLoginPage = computed(() => route.name === 'Login')

const activeMenu = computed(() => {
  return route.path || '/'
})

function navigate(path: string) {
  router.push(path)
}

function handleLogout() {
  localStorage.removeItem('authToken')
  localStorage.removeItem('user')
  router.push('/login')
}
</script>

<style scoped>
.user-layout {
  height: 100vh;
  display: flex;
}

.sidebar {
  background: #001529;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
  font-weight: bold;
  border-bottom: 1px solid #ffffff1a;
}

.header {
  background: #fff;
  border-bottom: 1px solid #eee;
  display: flex;
  align-items: center;
  padding: 0 20px;
}

.header h2 {
  margin: 0;
  flex: 1;
  font-size: 18px;
  color: #333;
}

.main-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #f5f5f5;
}
</style>
