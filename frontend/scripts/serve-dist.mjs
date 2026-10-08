/**
 * SPA 静态服务(带 history fallback),用于 SPEC §12.6 的真实渲染验证。
 * python -m http.server 不做 fallback,`/login` 这类前端路由会 404,
 * 页面拿不到 index.html,量出来的「无横向滚动」是**假绿**。
 *
 * 用法:node scripts/serve-dist.mjs [port]
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = path.resolve(process.argv[2] || 'dist')
const PORT = Number(process.argv[3] || 5175)
/** 后端直连地址。前端 VITE_API_BASE_URL=/api 是相对路径,静态服务下要代理过去 */
const API_TARGET = { host: '127.0.0.1', port: 18080 }

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.json': 'application/json; charset=utf-8',
  '.woff2': 'font/woff2',
}

/**
 * 把 /api/* 转到后端。
 * 注意后端**没有** /api/v1 前缀(契约 openapi.yaml:170 写明「本地直连需去掉
 * /api/v1 前缀」),所以这里剥掉 /api 就直接打后端,不多剥一层。
 */
function proxy(req, res) {
  const p = http.request(
    { ...API_TARGET, path: req.url.replace(/^\/api/, ''), method: req.method, headers: { ...req.headers, host: `${API_TARGET.host}:${API_TARGET.port}` } },
    (up) => { res.writeHead(up.statusCode, up.headers); up.pipe(res) },
  )
  p.on('error', (e) => { res.writeHead(502).end('proxy error: ' + e.message) })
  req.pipe(p)
}

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x')
  if (url.pathname.startsWith('/api/')) { proxy(req, res); return }
  // /assets/xxx 命中文件则发文件,否则回落到 index.html
  let file = path.join(ROOT, url.pathname)
  if (!file.startsWith(ROOT)) { res.writeHead(403).end(); return }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    if (url.pathname.startsWith('/assets/')) { res.writeHead(404).end('no such asset'); return }
    file = path.join(ROOT, 'index.html')
  }
  const type = MIME[path.extname(file)] || 'application/octet-stream'
  res.writeHead(200, { 'content-type': type })
  fs.createReadStream(file).pipe(res)
}).listen(PORT, '127.0.0.1', () => {
  console.log(`dist 静态服务已启动: http://127.0.0.1:${PORT}  (root=${ROOT})`)
  console.log(`API 代理: /api/* -> http://${API_TARGET.host}:${API_TARGET.port}/*`)
})