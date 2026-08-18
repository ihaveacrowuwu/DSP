import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5180,
    // Proxy keeps the browser on one origin in development, so cookies and
    // relative image URLs behave the same as in the deployed build.
    proxy: {
      '/v1': { target: 'http://localhost:8090', changeOrigin: true },
      '/healthz': { target: 'http://localhost:8090', changeOrigin: true },
    },
  },
})
