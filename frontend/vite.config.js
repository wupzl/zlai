import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: "jsdom",
    globals: true,
    clearMocks: true
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes("node_modules")) {
            if (id.includes("katex") || id.includes("marked") || id.includes("dompurify")) {
              return "chat-markdown";
            }
            if (id.includes("vue")) {
              return "vue-vendor";
            }
            return "vendor";
          }
          if (id.includes("/src/views/admin/")) {
            return "admin";
          }
          if (id.includes("/src/views/ChatView.vue") || id.includes("/src/components/chat/")) {
            return "chat";
          }
        }
      }
    }
  },
  resolve: {
    alias: {
      vue: "vue/dist/vue.esm-browser.js"
    }
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.VITE_PROXY_TARGET || "http://localhost:8080",
        changeOrigin: true
      }
    }
  }
});
