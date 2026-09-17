import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// The SPA is no longer served by a backend process. In dev, Vite proxies the
// API to the gateway; in prod, nginx does (frontend/nginx.conf).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/.well-known": "http://localhost:8080",
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
  },
});
