import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 8084,
    host: '0.0.0.0',
    cors: true,
    allowedHosts: ['oms-admin.tomting.com', 'localhost', '127.0.0.1', '192.168.0.48'],
    proxy: {
      '/api': {
        target: process.env.VITE_API_BASE_URL?.replace('/api', '') || 'http://localhost:8082',
        changeOrigin: true,
        rewrite: (path) => path,
        secure: false
      }
    }
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
    assetsDir: 'assets'
  }
})
