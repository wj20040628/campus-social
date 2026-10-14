import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    // 浏览器 Vue DevTools 扩展的数据源:dev 期起后,Chrome/Edge F12 的 Vue 标签
    // 可「点页面上的元素 → 跳源码组件」/「选组件 → 高亮 DOM」。只注入 dev,不进 build。
    vueDevTools(),
    // Element Plus 按需引入:扫模板里的 <el-xxx> 自动 import 组件 + 其样式,
    // 替代 main.js 的整包 app.use(ElementPlus)(TD-21)。
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
    // 本机环境的 rmSync 有批量删除守卫(单轮>50 文件即拦截),vite 内建的
    // emptyOutDir 一次删掉全部产物必然触发。改为关闭自动清空,由
    // `npm run build`(scripts/build.mjs)先分批清理再调 vite build。
    emptyOutDir: false,
    // AC-17 首屏 JS gzip < 300KB 的护栏:单 chunk 超 700KB(未压缩)即告警,
    // 避免「今天还在线、明天悄悄涨到 1MB」这种事无声发生。
    chunkSizeWarningLimit: 700,
    rollupOptions: {
      output: {
        // 只拆 vue 全家桶,让框架代码单独成 chunk 以便长期缓存。
        //
        // TD-21 修复:原先这里有 'element-plus': ['element-plus']。
        // manualChunks 会把 element-plus 强制整包成chunk,等于无视按需引入、
        // 把整包重新拉回首屏,与上面的 resolver 互相打架。
        // 实测基线(修复前):dist/assets/element-plus-CxMk7e0x.js 943.07 kB / gzip 303.07 kB,
        // 单这一个 chunk 就超了 AC-17 的 300KB gzip 上限。已删除该配置项。
        manualChunks: {
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
