import path from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The client portal. Its own build and its own dev server, so it can be
// deployed to its own subdomain — dependencies stay in the one package.json
// a level up, which is why there is no node_modules beside this file.
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
      "@shared": path.resolve(import.meta.dirname, "../shared/src"),
    },
  },
  server: {
    port: 5174,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
});
