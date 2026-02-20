import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router'

let app: any

export async function bootstrap() {
  console.log('[Admin App] bootstrap')
}

export async function mount(props: any) {
  console.log('[Admin App] mount', props)
  app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.use(ElementPlus)
  app.mount(props.container || '#app')
}

export async function unmount() {
  console.log('[Admin App] unmount')
  app?.unmount()
  app = null
}

// 獨立開發模式（非 qiankun 環境）
if (!window.__POWERED_BY_QIANKUN__) {
  bootstrap().then(() => mount({ container: '#app' }))
}
