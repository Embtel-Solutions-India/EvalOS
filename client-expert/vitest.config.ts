import path from 'node:path'
import { defineConfig } from 'vitest/config'

const chat = path.resolve(import.meta.dirname, '../packages/evalos-chat/src')

// Tests run once for the whole repo rather than per app: what is tested today
// lives in shared/, which both portals import — and, since Unit 58, the chat package.
export default defineConfig({
  resolve: {
    alias: {
      '@shared': path.resolve(import.meta.dirname, './shared/src'),
      '@evalos/chat': chat,
    },
  },
  test: {
    include: ['{shared,client,expert}/src/**/*.test.{ts,tsx}', `${chat.replaceAll('\\', '/')}/**/*.test.ts`],
  },
})
