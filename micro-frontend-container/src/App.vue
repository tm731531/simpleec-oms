<template>
  <div class="container">
    <header class="header">
      <h1>SimpleEC OMS - Micro-Frontend Container</h1>
      <nav class="nav">
        <a
          href="#/admin/"
          class="nav-link"
          :class="{ active: currentRoute === 'admin' }"
          @click="navigateToAdmin"
        >
          Admin Portal
        </a>
        <a
          href="#/app/"
          class="nav-link"
          :class="{ active: currentRoute === 'app' }"
          @click="navigateToApp"
        >
          User Portal
        </a>
      </nav>
    </header>

    <main class="main">
      <div v-if="loading" class="loading">
        <p>Loading microapp...</p>
      </div>
      <div v-if="error" class="error">
        <p>⚠️ Failed to load microapp: {{ error }}</p>
        <p class="error-hint">Please check that the microapp is running on the correct port.</p>
      </div>
      <div
        id="qiankun-container"
        class="qiankun-container"
        v-show="!loading && !error"
      ></div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { registerMicroApps, start } from 'qiankun'
import type { RegistrableApp } from 'qiankun'

const currentRoute = ref<string>('admin')
const loading = ref<boolean>(false)
const error = ref<string | null>(null)

const microApps: RegistrableApp<Record<string, any>>[] = [
  {
    name: 'simpleec-oms-admin',
    entry: 'http://localhost:8081',
    container: '#qiankun-container',
    activeRule: '/admin/',
    props: {
      routerBase: '/admin/'
    }
  },
  {
    name: 'simpleec-oms-user',
    entry: 'http://localhost:8082',
    container: '#qiankun-container',
    activeRule: '/app/',
    props: {
      routerBase: '/app/'
    }
  }
]

const handleBeforeLoad = async (): Promise<void> => {
  loading.value = true
  error.value = null
}

const handleAfterMount = async (): Promise<void> => {
  loading.value = false
}

function navigateToAdmin(e: Event): void {
  e.preventDefault()
  currentRoute.value = 'admin'
  window.location.hash = '#/admin/'
}

function navigateToApp(e: Event): void {
  e.preventDefault()
  currentRoute.value = 'app'
  window.location.hash = '#/app/'
}

watch(
  () => window.location.hash,
  (newHash) => {
    if (newHash.includes('/admin/')) {
      currentRoute.value = 'admin'
    } else if (newHash.includes('/app/')) {
      currentRoute.value = 'app'
    }
  }
)

onMounted(async () => {
  try {
    // Register microapps
    registerMicroApps(microApps, {
      beforeLoad: [handleBeforeLoad],
      afterMount: [handleAfterMount]
    })

    // Start qiankun
    await start({
      singular: true,
      sandbox: {
        strictStyleIsolation: false,
        experimentalStyleIsolation: true
      }
    })

    console.log('Qiankun started successfully')
  } catch (err) {
    console.error('Failed to initialize qiankun:', err)
    error.value = 'Failed to initialize container'
  }
})
</script>

<style scoped>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

.container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background-color: #f5f5f5;
}

.header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.header h1 {
  font-size: 24px;
  margin-bottom: 16px;
  font-weight: 600;
}

.nav {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.nav-link {
  padding: 10px 20px;
  background-color: rgba(255, 255, 255, 0.2);
  color: white;
  text-decoration: none;
  border-radius: 4px;
  transition: all 0.3s ease;
  border: 2px solid transparent;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}

.nav-link:hover {
  background-color: rgba(255, 255, 255, 0.3);
  transform: translateY(-2px);
}

.nav-link.active {
  background-color: white;
  color: #667eea;
  border-color: white;
}

.main {
  flex: 1;
  padding: 20px;
  max-width: 1200px;
  width: 100%;
  margin: 0 auto;
}

.qiankun-container {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  min-height: 400px;
}

.loading,
.error {
  background: white;
  border-radius: 8px;
  padding: 40px 20px;
  text-align: center;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.loading p {
  color: #667eea;
  font-size: 16px;
  font-weight: 500;
}

.error {
  border-left: 4px solid #ef4444;
}

.error p {
  color: #dc2626;
  margin: 8px 0;
  font-size: 14px;
}

.error p:first-child {
  font-weight: 600;
  font-size: 16px;
}

.error-hint {
  color: #92400e !important;
  background-color: #fef3c7;
  padding: 8px 12px;
  border-radius: 4px;
  margin-top: 12px;
  font-size: 13px !important;
}

@media (max-width: 768px) {
  .header h1 {
    font-size: 20px;
  }

  .nav {
    flex-direction: column;
    gap: 8px;
  }

  .nav-link {
    width: 100%;
    text-align: center;
  }

  .main {
    padding: 16px;
  }
}
</style>
