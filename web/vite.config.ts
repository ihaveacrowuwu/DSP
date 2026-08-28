/// <reference types="vitest/config" />
// The triple-slash reference, not `import { defineConfig } from 'vitest/config'`.
// Both make the `test` key type-check, but the import pulls in vitest's own nested
// copy of Vite and then `plugins` is two structurally identical but nominally
// different types, which produces forty lines of unassignable-Plugin errors. The
// reference just augments Vite's UserConfig in place.
//
// Without either, `vue-tsc --noEmit` rejects the config outright - which is how
// `make test-web` caught this while `npm test` was perfectly happy.
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    /*
     * Two projects, because the suite genuinely needs two environments.
     *
     * The component tests mount small presentational components and assert on
     * classes, text and inline styles, so they need a DOM - happy-dom rather than
     * jsdom, which is faster and sufficient; nothing here renders MapLibre, which
     * would need a WebGL context no headless DOM provides.
     *
     * The `src/lib` tests must run in **node**, and that is not a preference. The
     * basemap test reads `public/basemap/maldives.json` off disk through
     * `fileURLToPath(new URL(..., import.meta.url))`, and under happy-dom
     * `import.meta.url` is an http URL, so that call fails with "The URL must be of
     * scheme file". Running the whole suite in happy-dom was tried and broke exactly
     * that test.
     *
     * `projects` rather than `environmentMatchGlobs`, which does the same thing and is
     * deprecated in Vitest 3.
     */
    projects: [
      {
        extends: true,
        test: {
          name: 'logic',
          environment: 'node',
          include: ['src/**/*.test.ts'],
          exclude: ['src/**/*.dom.test.ts'],
        },
      },
      {
        extends: true,
        test: {
          name: 'components',
          environment: 'happy-dom',
          include: ['src/**/*.dom.test.ts'],
        },
      },
    ],
  },
  server: {
    port: 5180,
    /*
     * Fail rather than drift. Without this Vite quietly takes the next free port
     * when 5180 is busy - and 5180 is exactly where the docker `web` container
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
