import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'
import { resolve } from 'node:path'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入:扫模板里的 <el-xxx> 自动 import 组件 + 其样式,
    // 替代 main.js 的整包 app.use(ElementPlus),首屏体积大幅下降。
    // 同时 AutoImport 自动引入 ElMessage/ElMessageBox 等 API(免手写 import)。
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'components.d.ts',
    }),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: 'auto-imports.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        // 把第三方大依赖单独成 chunk,让浏览器缓存复用、避免全塞进 index.js
        manualChunks: {
          'element-plus': ['element-plus'],
          vue: ['vue', 'vue-router', 'pinia', 'axios', 'dayjs'],
        },
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      // 开发期:前端 /api → 后端 8080,避免 CORS
      // 生产期:同域或 Nginx 反向代理
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/api/, ''),
      },
      // 上传文件静态资源也代理
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket 代理:dev 下浏览器连 ws://localhost:5173/ws/*,转发到后端 8080
      // 必须开 ws: true 才能代理 WebSocket 升级请求
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
})
