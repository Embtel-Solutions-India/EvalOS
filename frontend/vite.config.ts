import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { fileURLToPath } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      '@evalos/chat': fileURLToPath(new URL('../packages/evalos-chat/src', import.meta.url)),
    },
    // The chat package has no node_modules: its react and ably imports must resolve to this
    // app's copies, or React would run twice (Unit 57 phase 2).
    dedupe: ['react', 'react-dom', 'ably'],
  },
  server: {
    port: 5173,
    proxy: {
      // Forward API calls to the Spring Boot server so the browser stays
      // same-origin in dev and CORS never comes up.
      '/api': {
        // API_TARGET points a second dev server at a backend on another port; unset, it is 8080.
        target: process.env.API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
