import { resolve } from 'node:path'
import { defineConfig } from 'vite'
import { devReader } from './plugin'

/**
 * `npm run dev` only. The production build keeps using ../vite.config.ts: nothing under dev/ is reachable from it.
 * The root is reader-web, not src, so the dev page lives outside the sources the app ships.
 */
export default defineConfig({
  root: resolve(import.meta.dirname, '..'),
  publicDir: false,
  plugins: [devReader()],
  optimizeDeps: { noDiscovery: true, include: [] },
  server: { port: 5173, watch: { ignored: ['**/dist/**', '**/test/**'] } },
})
