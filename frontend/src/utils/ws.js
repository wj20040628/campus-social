import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'

/**
 * WebSocket 单例封装
 * - 连接:ws(s)://location.host/ws/{chat|notify}?access_token=xxx
 * - 断线重连:指数退避 1s → 2s → 4s → 8s → 30s(上限)
 * - 心跳:每 30s 发 ping,60s 没 pong 视为断连
 * - 鉴权:浏览器 WS 不能自定义 header,只能 query 带 token
 * - 4401(token 失效)→ 调 /auth/refresh → 重连
 * - visibilitychange:后台暂停心跳,回前台补一次
 *
 * 用法:
 *   const ws = getWsClient()
 *   ws.connect('/ws/chat')
 *   ws.on('chat', handler)
 *   ws.on('notify', handler)
 *   ws.close()
 */

let instance = null

export function getWsClient() {
  if (!instance) instance = new WsClient()
  return instance
}

class WsClient {
  constructor() {
    this.socket = null
    this.path = null
    this.subs = {} // type -> Set<fn>
    this.connecting = false
    this.reconnectTimer = null
    this.heartbeatTimer = null
    this.lastPong = 0
    this.backoff = 1000
    this.closedByUser = false
  }

  /** 连接指定 endpoint(如 /ws/chat) */
  connect(path) {
    if (this.socket && this.socket.readyState === 1) {
      // 已连接,切路径需先关
      this.close()
    }
    this.path = path
    this.closedByUser = false
    this._doConnect()
  }

  _wsBaseUrl() {
    const host = window.location.host
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const base = import.meta.env.VITE_WS_BASE
    if (base) return `${base}`.replace(/^\/$/, '')
    // dev 走 Vite 同源(需代理),生产走 location
    return `${proto}://${host}`
  }

  _doConnect() {
    const user = useUserStore()
    if (!user.accessToken || !this.path) return
    const url = `${this._wsBaseUrl()}${this.path}?access_token=${encodeURIComponent(user.accessToken)}`

    this.socket = new WebSocket(url)

    this.socket.onopen = () => {
      this.backoff = 1000 // 重置退避
      this.lastPong = Date.now()
      this._startHeartbeat()
      console.log(`[ws] connected: ${this.path}`)
    }

    this.socket.onmessage = (evt) => {
      let msg
      try {
        msg = JSON.parse(evt.data)
      } catch {
        return
      }
      if (msg.type === 'pong') {
        this.lastPong = Date.now()
        return
      }
      const set = this.subs[msg.type]
      if (set) set.forEach((fn) => fn(msg.payload, msg))
    }

    this.socket.onclose = (evt) => {
      this._stopHeartbeat()
      if (this.closedByUser) return
      // 4401/401 → refresh 后重连;其余 → 退避重连
      if (evt.code === 4401 || evt.code === 401 || evt.code === 1008) {
        this._refreshAndReconnect()
      } else {
        this._scheduleReconnect()
      }
    }

    this.socket.onerror = () => {
      // onclose 会触发重连逻辑
    }
  }

  _scheduleReconnect() {
    if (this.closedByUser) return
    this._stopHeartbeat()
    const delay = this.backoff
    this.backoff = Math.min(this.backoff * 2, 30000)
    this.reconnectTimer = setTimeout(() => this._doConnect(), delay)
  }

  async _refreshAndReconnect() {
    const user = useUserStore()
    try {
      await user.refresh()
      this._doConnect()
    } catch (e) {
      ElMessage.warning('连接已断开,请重新登录')
      this._scheduleReconnect()
    }
  }

  _startHeartbeat() {
    this._stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      if (this.socket && this.socket.readyState === 1) {
        this.socket.send(JSON.stringify({ type: 'ping', ts: Date.now() }))
        // 60s 没 pong → 视为断连
        if (Date.now() - this.lastPong > 60000) {
          this.socket.close()
        }
      }
    }, 30000)

    // visibilitychange:后台暂停,回前台补一次
    this._onVisChange = () => {
      if (document.visibilityState === 'visible') {
        if (this.socket && this.socket.readyState === 1) {
          this.socket.send(JSON.stringify({ type: 'ping', ts: Date.now() }))
          this.lastPong = Date.now()
        }
      }
    }
    document.addEventListener('visibilitychange', this._onVisChange)
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
    if (this._onVisChange) {
      document.removeEventListener('visibilitychange', this._onVisChange)
      this._onVisChange = null
    }
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }

  /** 订阅某 type 的消息 */
  on(type, fn) {
    if (!this.subs[type]) this.subs[type] = new Set()
    this.subs[type].add(fn)
  }

  off(type, fn) {
    this.subs[type]?.delete(fn)
  }

  /** 主动关闭(不重连) */
  close() {
    this.closedByUser = true
    this._stopHeartbeat()
    if (this.socket) {
      this.socket.close()
      this.socket = null
    }
  }
}
