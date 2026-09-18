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
    // **strictPort, because without it this app served itself on the EXPERT portal's port.** Vite
    // silently increments past a busy port, so a second start of the client took 5175 -- which is
    // the expert portal's -- and the expert app then looked broken while nothing about it was.
    // The two are separate origins on separate subdomains; a collision has to stop rather than
    // resolve itself into the wrong app answering on the right port.
    strictPort: true,
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
});
