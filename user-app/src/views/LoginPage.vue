<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2 class="login-title">SimpleEC OMS</h2>
      <p class="login-subtitle">商家後台</p>
      <el-form @submit.prevent="handleLogin">
        <el-form-item>
          <el-input
            v-model="email"
            placeholder="帳號 (Email)"
            prefix-icon="User"
            size="large"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="password"
            type="password"
            placeholder="密碼"
            prefix-icon="Lock"
            size="large"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" class="login-error" />
        <el-alert type="info" :closable="false" class="demo-hint">
          <template #default>
            <div style="font-size: 12px; line-height: 1.8">
              <strong>測試帳號</strong><br>
              帳號：wang@health-food.com.tw<br>
              密碼：password
            </div>
          </template>
        </el-alert>
        <el-button
          type="primary"
          size="large"
          class="login-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登入
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const authStore = useAuthStore()
const email = ref('')
const password = ref('')
const error = ref('')
const loading = computed(() => authStore.loading)

async function handleLogin() {
  if (!email.value || !password.value) {
    error.value = '請輸入帳號和密碼'
    return
  }
  error.value = ''
  const ok = await authStore.login({ email: email.value, password: password.value })
  if (ok) {
    router.push('/')
  } else {
    error.value = authStore.error || '帳號或密碼錯誤'
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f2f5;
}

.login-card {
  width: 380px;
  padding: 20px;
}

.login-title {
  text-align: center;
  margin: 0 0 4px;
  font-size: 22px;
  color: #333;
}

.login-subtitle {
  text-align: center;
  color: #999;
  margin: 0 0 24px;
  font-size: 14px;
}

.login-error {
  margin-bottom: 16px;
}

.demo-hint {
  margin-bottom: 16px;
}

.login-btn {
  width: 100%;
}
</style>
