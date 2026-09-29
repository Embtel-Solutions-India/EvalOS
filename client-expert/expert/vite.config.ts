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
      "@evalos/chat": path.resolve(import.meta.dirname, "../../packages/evalos-chat/src"),
    },
    // Same as the client portal: the chat package's react and ably must be THIS app's copies.
    dedupe: ["react", "react-dom", "ably"],
  },
  // Only the lazy /case route imports it (via @shared), so the dep scan misses it and Vite
  // re-optimizes on first visit — the page then dies on "504 Outdated Optimize Dep".
  optimizeDeps: { include: ["@radix-ui/react-checkbox"] },
  server: {
    port: 5175,
    // **strictPort, because 5175 was answering with the CLIENT portal.** Vite silently increments
    // past a busy port, and the client's dev server is 5174 -- so a second `npm run dev` (which
    // was an alias for the client) took 5175 and served the client app on the expert portal's
    // port, looking exactly like the expert app being broken. The two are separate origins on
    // separate subdomains; a collision has to stop rather than resolve itself into the wrong app
    // answering on the right port.
    strictPort: true,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
});
