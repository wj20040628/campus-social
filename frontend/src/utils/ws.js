import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/feedback'
import dayjs from 'dayjs'

/**
 * WebSocket 单例封装（支持同时维护多条连接，每条对应一个 endpoint）
 *
 * <p>为什么改成「多连接」：原先 connect(path) 会先关掉已存在的连接，
 * 导致 /ws/chat 与 /ws/notify 互斥——连上聊天就断了通知，连上通知就断了聊天。
 * 结果顶栏「通知红点」只有整页刷新才更新，新通知来了不亮。
 *
 * <p>现在的模型：每个 path 一条独立连接，互不干扰。派发按消息 type 走全局
 * subs（与具体哪条连接收到解耦），所以 chat 事件只在 /ws/chat 上收到、
 * notify 事件只在 /ws/notify 上收到，但都统一派发给对应订阅者。
 *
 * 用法：
 *   const ws = getWsClient()
 *   ws.connect('/ws/chat')
 *   ws.on('chat', handler)
 *   ws.on('notify', handler)
 *   ws.close()            // 关全部（登出用）
 *   ws.close('/ws/chat')  // 只关某条
 */
export function getWsClient() {
  if (!instance) instance = new WsClient()
  return instance
}

let instance = null

class WsClient {
  constructor() {
    // path -> 连接态：{ socket, reconnectTimer, heartbeatTimer, lastPong, backoff, closedByUser, onVis }
    this.conns = {}
    // type -> Set<fn>：全局派发表（与具体连接解耦）
    this.subs = {}
  }

  /** 确保某 endpoint 已连接（已连则幂等返回，不重连） */
  connect(path) {
    const c = this.conns[path]
    if (c && c.socket && (c.socket.readyState === 0 || c.socket.readyState === 1)) {
      return
    }
    this._ensureConn(path)
  }

  _ensureConn(path) {
    const c =
      this.conns[path] ||
      (this.conns[path] = {
        socket: null,
        reconnectTimer: null,
        heartbeatTimer: null,
        lastPong: 0,
        backoff: 1000,
        closedByUser: false,
        onVis: null,
      })
    c.closedByUser = false
    this._doConnect(path)
  }

  _wsBaseUrl() {
    const host = window.location.host
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const base = import.meta.env.VITE_WS_BASE
    if (base) return `${base}`.replace(/^\/$/, '')
    // dev 走 Vite 同源(需代理),生产走 location
    return `${proto}://${host}`
  }

  _doConnect(path) {
    const c = this.conns[path]
    if (!c || c.closedByUser) return
    const user = useUserStore()
    if (!user.accessToken || !path) return
    const url = `${this._wsBaseUrl()}${path}?access_token=${encodeURIComponent(user.accessToken)}`

    const socket = new WebSocket(url)
    c.socket = socket

    socket.onopen = () => {
      c.backoff = 1000 // 重置退避
      c.lastPong = Date.now()
      this._startHeartbeat(path)
      console.log(`[ws] connected: ${path}`)
    }

    socket.onmessage = (evt) => {
      let msg
      try {
        msg = JSON.parse(evt.data)
      } catch {
        return
      }
      if (msg.type === 'pong') {
        c.lastPong = Date.now()
        return
      }
      const set = this.subs[msg.type]
      if (set) set.forEach((fn) => fn(msg.payload, msg))
    }

    socket.onclose = (evt) => {
      this._stopHeartbeat(path)
      if (c.closedByUser) return
      // 4401/401 → refresh 后重连;其余 → 退避重连
      if (evt.code === 4401 || evt.code === 401 || evt.code === 1008) {
        this._refreshAndReconnect(path)
      } else {
        this._scheduleReconnect(path)
      }
    }

    socket.onerror = () => {
      // onclose 会触发重连逻辑
    }
  }

  _scheduleReconnect(path) {
    const c = this.conns[path]
    if (!c || c.closedByUser) return
    this._stopHeartbeat(path)
    const delay = c.backoff
    c.backoff = Math.min(c.backoff * 2, 30000)
    c.reconnectTimer = setTimeout(() => this._doConnect(path), delay)
  }

  async _refreshAndReconnect(path) {
    const user = useUserStore()
    try {
      await user.refresh()
      this._doConnect(path)
    } catch (e) {
      ElMessage.warning('连接已断开,请重新登录')
      this._scheduleReconnect(path)
    }
  }

  _startHeartbeat(path) {
    const c = this.conns[path]
    this._stopHeartbeat(path)
    c.heartbeatTimer = setInterval(() => {
      const s = c.socket
      if (s && s.readyState === 1) {
        s.send(JSON.stringify({ type: 'ping', ts: Date.now() }))
        // 60s 没 pong → 视为断连
        if (Date.now() - c.lastPong > 60000) s.close()
      }
    }, 30000)

    // visibilitychange:后台暂停,回前台补一次(每条连接独立监听,关闭时移除)
    c.onVis = () => {
      if (document.visibilityState === 'visible') {
        const s = c.socket
        if (s && s.readyState === 1) {
          s.send(JSON.stringify({ type: 'ping', ts: Date.now() }))
          c.lastPong = Date.now()
        }
      }
    }
    document.addEventListener('visibilitychange', c.onVis)
  }

  _stopHeartbeat(path) {
    const c = this.conns[path]
    if (!c) return
    if (c.heartbeatTimer) {
      clearInterval(c.heartbeatTimer)
      c.heartbeatTimer = null
    }
    if (c.onVis) {
      document.removeEventListener('visibilitychange', c.onVis)
      c.onVis = null
    }
    if (c.reconnectTimer) {
      clearTimeout(c.reconnectTimer)
      c.reconnectTimer = null
    }
  }

  /** 订阅某 type 的消息（全局派发，与具体连接无关） */
  on(type, fn) {
    if (!this.subs[type]) this.subs[type] = new Set()
    this.subs[type].add(fn)
  }

  off(type, fn) {
    this.subs[type]?.delete(fn)
  }

  /** 主动关闭（不重连）。不传 path 关全部（登出用），传 path 只关某条 */
  close(path) {
    if (path) {
      const c = this.conns[path]
      if (c) {
        c.closedByUser = true
        this._stopHeartbeat(path)
        c.socket?.close()
        c.socket = null
      }
      return
    }
    for (const p of Object.keys(this.conns)) this.close(p)
  }
}
