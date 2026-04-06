<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2 class="login-title">SimpleEC OMS</h2>
      <p class="login-subtitle">平台管理後台</p>
      <el-form @submit.prevent="handleLogin">
        <el-form-item>
          <el-input
            v-model="email"
            placeholder="管理員帳號"
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
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api/index'

const router = useRouter()
const email = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  if (!email.value || !password.value) {
    error.value = '請輸入帳號和密碼'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await api.post('/admin/auth/login', {
      email: email.value,
      password: password.value,
    })
    localStorage.setItem('authToken', data.token)
    localStorage.setItem('adminUser', JSON.stringify(data.user))
    router.push('/')
  } catch (e: any) {
    error.value = e?.response?.data?.error || '帳號或密碼錯誤'
  } finally {
    loading.value = false
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

.login-btn {
  width: 100%;
}
</style>
