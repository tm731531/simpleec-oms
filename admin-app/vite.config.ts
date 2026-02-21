import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 8081,
    host: '0.0.0.0',
    cors: true,
    headers: {
      'Access-Control-Allow-Origin': '*'
    },
    proxy: {
      '/api': {
        target: 'http://192.168.0.48:8083',
        changeOrigin: true,
        rewrite: (path) => path,
        secure: false
      }
    }
  },
  build: {
    outDir: 'dist',
    assetsDir: 'assets',
    library: {
      entry: 'src/main.ts',
      name: 'AdminApp',
      formats: ['umd']
    }
  }
})
