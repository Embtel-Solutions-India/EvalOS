import path from 'node:path'
import { defineConfig } from 'vitest/config'

// Tests run once for the whole repo rather than per app: what is tested today
// lives in shared/, which both portals import.
export default defineConfig({
  resolve: {
    alias: {
      '@shared': path.resolve(import.meta.dirname, './shared/src'),
    },
  },
  test: {
    include: ['{shared,client,expert}/src/**/*.test.{ts,tsx}'],
  },
})
