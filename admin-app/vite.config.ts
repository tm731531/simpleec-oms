import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 8084,
    host: '0.0.0.0',
    cors: true,
    allowedHosts: ['oms-admin.tomting.com', 'localhost', '127.0.0.1', '192.168.0.48'],
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
    assetsDir: 'assets'
  }
})
