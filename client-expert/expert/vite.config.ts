import path from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The expert portal. Same shape as the client's config, one port apart, so the
// two run side by side in development and deploy to two origins.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
      "@shared": path.resolve(import.meta.dirname, "../shared/src"),
    },
  },
  server: {
    port: 5175,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
});
