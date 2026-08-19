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
    /*
     * Fail rather than drift. Without this Vite quietly takes the next free port
     * when 5180 is busy — and 5180 is exactly where the docker `web` container
     * serves the last static BUILD. The result is a dev server running on a port
     * nobody opened while the browser shows a stale bundle that never hot-reloads.
     * An explicit "port is in use" is far better than that: stop the container
     * (`make dev-web` does) and the dev server owns the URL you already use.
     */
    strictPort: true,
    // Proxy keeps the browser on one origin in development, so cookies and
    // relative image URLs behave the same as in the deployed build.
    proxy: {
      '/v1': { target: 'http://localhost:8090', changeOrigin: true },
      '/healthz': { target: 'http://localhost:8090', changeOrigin: true },
    },
  },
})
