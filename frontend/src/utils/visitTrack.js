/*
 * 匿名访问统计（2026-11-14）。
 *
 * 游客没有账号、不发 token，服务端无法从请求里认出「一次浏览」。
 * 这里在浏览器本地生成匿名访客 ID 与会话 ID，进入时上报一次，
 * 之后每 30 秒续一次；切后台、关页面时把前台累计时长报给服务端。
 *
 * 口径与边界：
 * - 停留只算页面在前台的时间，切后台不计；
 * - 入口路径在模块加载时抓取：欢迎页停留在 /guest 期间不会改地址，
 *   这样扫码来源能稳定记为 qr；
 * - 不采集设备指纹、不读通讯录/相册，访客 ID 只是随机 UUID；
 * - 结束上报优先用 sendBeacon，浏览器关闭时才尽量不丢最后一段。
 */

const VISITOR_KEY = 'ys_visit_id'
const SESSION_KEY = 'ys_visit_session'
const ACTIVE_KEY = 'ys_visit_active'
const HEARTBEAT_MS = 30_000
const FIRST_PING_MS = 5_000
const MAX_ACTIVE_SECONDS = 4 * 3600

const ENTRY = readEntry()

function readEntry() {
  try {
    const url = new URL(window.location.href)
    return {
      path: (url.pathname || '/').slice(0, 64),
      from: (url.searchParams.get('from') || '').slice(0, 32),
    }
  } catch {
    return { path: '/', from: '' }
  }
}

function newId() {
  try {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID()
  } catch {
    /* 老浏览器没有 randomUUID，走下面的兜底 */
  }
  return `v-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function readStorage(store, key) {
  try {
    return store.getItem(key) || ''
  } catch {
    return ''
  }
}

function writeStorage(store, key, value) {
  try {
    store.setItem(key, value)
  } catch {
    /* 隐私模式禁用存储时忽略：本次浏览仍可统计，只是刷新后会换新 ID */
  }
}

function ensureId(store, key) {
  let value = readStorage(store, key)
  if (!value) {
    value = newId()
    writeStorage(store, key, value)
  }
  return value
}

function sourceOf(entry) {
  if (entry.from) return entry.from
  if (entry.path === '/guest') return 'qr'
  if (entry.path.startsWith('/login')) return 'guest_login'
  return 'direct'
}

function apiBase() {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return base.replace(/\/+$/, '')
}

function post(path, body, beacon = false) {
  const url = `${apiBase()}${path}`
  const data = JSON.stringify(body)
  if (beacon && navigator.sendBeacon) {
    try {
      const blob = new Blob([data], { type: 'application/json' })
      if (navigator.sendBeacon(url, blob)) return
    } catch {
      /* sendBeacon 被拦截时回落到 fetch keepalive */
    }
  }
  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: data,
    keepalive: true,
    credentials: 'omit',
  }).catch(() => {
    /* 统计失败不影响页面功能，静默忽略 */
  })
}

export function startVisitTracking({ isGuest }) {
  const visitorId = ensureId(window.localStorage, VISITOR_KEY)
  const sessionId = ensureId(window.sessionStorage, SESSION_KEY)

  let activeSeconds = Number(readStorage(window.sessionStorage, ACTIVE_KEY)) || 0
  if (!Number.isFinite(activeSeconds) || activeSeconds < 0) activeSeconds = 0

  let visibleSince = document.visibilityState === 'visible' ? Date.now() : 0
  let entered = false

  function totalActive() {
    const live = visibleSince ? (Date.now() - visibleSince) / 1000 : 0
    return Math.min(MAX_ACTIVE_SECONDS, Math.round(activeSeconds + live))
  }

  function pauseVisible() {
    if (visibleSince) {
      activeSeconds += (Date.now() - visibleSince) / 1000
      visibleSince = 0
    }
  }

  function resumeVisible() {
    if (!visibleSince && document.visibilityState === 'visible') {
      visibleSince = Date.now()
    }
  }

  function persistActive() {
    writeStorage(window.sessionStorage, ACTIVE_KEY, String(Math.floor(totalActive())))
  }

  function payload(ended) {
    return {
      visitorId,
      sessionId,
      /*
       * 入口是 /guest 就算游客：欢迎页本身不写游客标记，只有点「跳过 /
       * 进入首页」才置位；只看 isGuest() 会让首次 enter 漏标游客
       * （虽然 5 秒后的心跳会补上，但只停留几秒的访客就漏了）。
       */
      guest: ENTRY.path === '/guest' || !!isGuest(),
      entryPath: ENTRY.path,
      source: sourceOf(ENTRY),
      activeSeconds: totalActive(),
      ended,
    }
  }

  function ping(ended = false) {
    post('/visit/ping', payload(ended), ended)
  }

  function enter() {
    if (entered) return
    entered = true
    post('/visit/enter', payload(false))
    // 5 秒补一枪：只停留几秒就关页面的访客，即使结束上报丢了也能留下时长
    window.setTimeout(() => ping(false), FIRST_PING_MS)
  }

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      pauseVisible()
      persistActive()
      ping(false)
      return
    }
    resumeVisible()
    ping(false)
  })

  window.addEventListener('pagehide', () => {
    pauseVisible()
    persistActive()
    ping(true)
  })

  // 从 bfcache 返回时页面内存还在，继续计时，并把会话重新标为未结束
  window.addEventListener('pageshow', (event) => {
    if (!event.persisted) return
    resumeVisible()
    ping(false)
  })

  window.setInterval(() => {
    if (document.visibilityState !== 'visible') return
    persistActive()
    ping(false)
  }, HEARTBEAT_MS)

  enter()
}