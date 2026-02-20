<template>
  <div class="admin-layout">
    <el-container>
      <el-aside width="200px" class="sidebar">
        <el-menu
          router
          :default-active="activeMenu"
          background-color="#f0f2f5"
          text-color="#333"
          active-text-color="#409eff"
        >
          <el-menu-item index="/" @click="navigate('/')">
            <span>儀表板</span>
          </el-menu-item>
          <el-menu-item index="/merchant" @click="navigate('/merchant')">
            <span>商家管理</span>
          </el-menu-item>
          <el-menu-item index="/account" @click="navigate('/account')">
            <span>帳戶管理</span>
          </el-menu-item>
          <el-menu-item index="/platform" @click="navigate('/platform')">
            <span>通路管理</span>
          </el-menu-item>
          <el-menu-item index="/monitor" @click="navigate('/monitor')">
            <span>系統監控</span>
          </el-menu-item>
        </el-menu>
      </el-aside>
      <el-container>
        <el-header class="header">
          <h2>SimpleEC OMS - 管理後台</h2>
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

.header h2 {
  margin: 0;
  font-size: 18px;
  color: #333;
}

.main-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #fafafa;
}
</style>
