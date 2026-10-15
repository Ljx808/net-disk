import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    // 配置 @ 指向 src，import 时不用写一长串 ../../../
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 浏览器请求 /api/xxx -> 开发服务器转发到 http://localhost:8080/api/xxx
      // 后端接口路径本身就带 /api 前缀，所以不需要 rewrite 去掉前缀
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
