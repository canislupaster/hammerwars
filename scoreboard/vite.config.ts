import { defineConfig } from 'vite'
import preact from '@preact/preset-vite'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [preact()],
  base: "/scoreboard",
  server: {
    proxy: {
      "/scoreboard/ws": {
          target: "ws://localhost:8080",
          ws: true
      },
      "/main.css": "http://localhost:8080",
      "/favicon.png": "http://localhost:8080",
      "/scoreboard.svg": "http://localhost:8080"
    }
  }
})
