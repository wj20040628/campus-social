import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/feedback'

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
    /*
     * 跨连接去重表：dedupKey -> 首次收到的时刻。
     * 见 socket.onmessage 里的说明 —— 同一个用户开着 /ws/chat 与 /ws/notify
     * 两条连接，而后端按 userId 广播、不区分端点，同一条消息会到两次。
     */
    this._recentMsgs = new Map()
  }

  /**
   * 为一条 WS 消息算去重键（同一条 JSON 在两条连接上算出来必然相同）。
   *
   * 字段选择：能唯一标识「这次事件」的那些。
   *   chat / group_msg       → msgId
   *   chat_read / *_recalled → msgId 或 convId 组合
   *   notify                 → 后端下发的 id
   * 都没有时返回 null（不去重，宁可重复也不误吞）。
   *
   * 必须把 msg.ts 也算进去：消息内容相同的两条新消息（比如连发两个「好」）
   * 有各自的 ts 与 msgId，不会因此被误判成重复。
   */
  _dedupKey(msg) {
    const p = msg?.payload
    if (!p) return null
    const id = p.msgId ?? p.id ?? p.notifyId
    if (id == null) return null
    return `${msg.type}:${id}:${msg.ts ?? ''}`
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
      /*
       * 跨连接去重（2026-10-21 修「消息显示两次」）。
       *
       * 【为什么会重复】前端开着两条 WS：/ws/chat 与 /ws/notify；
       * 而后端 WsSessionManager.broadcast(userId, ...) 遍历的是
       * 「该 userId 的所有 session」，**不区分端点**（见 broadcast 实现）。
       * 于是同一条 chat 推送会到达两条连接，各自的 socket.onmessage 都会执行。
       * 而 subs 是全局共享的 Map<type, Set<fn>>，同一个 handler 就被调用了两次
       * —— 接收方 messages.value.push() 两次，界面出现两条一模一样的消息。
       *
       * 【怎么去】按「消息身份」做短窗口去重：
       *   key = type + 该消息里能唯一标识它的字段 + payload.ts
       * 两条连接收到的是同一条 JSON，key 相同；窗口 2 秒后自然过期，
       * 不会影响后续真正的新消息（即使内容完全一样，ts 也不同）。
       */
      const key = this._dedupKey(msg)
      if (key) {
        const now = Date.now()
        if (this._recentMsgs.has(key) && now - this._recentMsgs.get(key) < 2000) {
          return
        }
        this._recentMsgs.set(key, now)
        // 顺手清理过期项，避免这张表无限增长（只在写入时扫，开销可忽略）
        if (this._recentMsgs.size > 200) {
          for (const [k, t] of this._recentMsgs) {
            if (now - t > 2000) this._recentMsgs.delete(k)
          }
        }
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
