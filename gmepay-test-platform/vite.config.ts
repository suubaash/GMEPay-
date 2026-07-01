import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dashboard (web/) is a standalone SPA. In dev it proxies /api to the
// Fastify test-engine server on :4000 so the browser only ever talks to one origin.
export default defineConfig({
  root: "web",
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:4000",
    },
  },
  build: {
    outDir: "../dist-web",
    emptyOutDir: true,
  },
});
