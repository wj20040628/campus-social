import { defineStore } from 'pinia'
import request from '@/utils/request'

/**
 * refresh 的**模块级单飞**（in-flight 共享）。
 *
 * 【为什么必须在这一层做，而不是各自调用方】
 * 后端 /auth/refresh 是**一次性轮换**：Lua CAS 比对旧 jti，成功即换成新 jti；
 * 任何用旧 jti 再来的请求都被判「token 重用」并把整个 Redis key DEL 掉。
 * 也就是说——**并发刷新不是「多刷几次」，而是会自我吊销、直接把人踢下线**。
 *
 * 而本项目的刷新有三条互不知情的入口：
 *   1. utils/request.js 的 401 拦截器（自带 isRefreshing 队列）
 *   2. utils/ws.js 的 _refreshAndReconnect（每条 WS 连接各调一次，绕过队列）
 *   3. 本 store 的主动续期定时器
 * access token 只有 15 分钟，到期那一刻 /ws/chat 与 /ws/notify **几乎同时**
 * 收到 4401，两次并发 refresh 用同一个旧 jti —— 一次成功、一次被判重用，
 * 服务端随即吊销，用户表现为「刚刚还好好的，突然被踢去登录页」。
 *
 * 把单飞下沉到 store：无论多少调用方、多少条通道，同一时刻只发一次刷新请求，
 * 所有调用方共享同一个 Promise 的结果。这是唯一能根治并发吊销的位置。
 */
let refreshInflight = null

/**
 * 游客模式(2026-10-30)。
 *
 * 游客不创建账号、不签发 token：前端只记一个本地标记，浏览走公开 GET。
 * 这样避免为每个访客在 user 表里造一行孤儿数据，也不会让游客出现在在线列表里。
 * 标记只存 localStorage，登录成功(setTokens)或登出(clear)时清掉。
 */
const GUEST_KEY = 'ys_guest'
function readGuest() {
  try {
    return localStorage.getItem(GUEST_KEY) === '1'
  } catch {
    return false
  }
}

/** access token 的到期时刻（epoch 秒）。三路写入：setTokens / refresh / 从 localStorage 恢复 */
let accessExpireAt = (() => {
  /*
   * 启动时恢复。access token 本身存 localStorage、刷新页面后仍有效，
   * 若到期时刻不恢复，新页面就不知道「还剩多久」——
   * 主动续期会退化成「不续期等 401」，等于白做。
   */
  const raw = localStorage.getItem('access_expire_at')
  const n = raw ? Number(raw) : 0
  return Number.isFinite(n) && n > 0 ? n : 0
})()

export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: localStorage.getItem('access_token') || '',
    refreshToken: localStorage.getItem('refresh_token') || '',
    userInfo: (() => {
      /*
       * 裸 JSON.parse 会让整站白屏（2026-10-21 修）。
       * localStorage 被旧版本写入 / 扩展篡改 / 写入中断 后值不是合法 JSON，
       * useUserStore() 第一次被调用（router 守卫里就调）即抛 SyntaxError，
       * main.js 之后全部不执行 —— 用户看到纯白页且没有任何提示，
       * 只能手动清站点数据。脏数据一律当作「没有登录信息」并清掉。
       */
      try {
        return JSON.parse(localStorage.getItem('user_info') || 'null')
      } catch {
        localStorage.removeItem('user_info')
        return null
      }
    })(),
    guest: readGuest(),
  }),

  getters: {
    hasToken: (s) => !!s.accessToken,
    isGuest: (s) => s.guest === true,
    nickname: (s) => s.userInfo?.nickname || s.userInfo?.username || '未登录',
    /** 是否管理员(role=1) */
    isAdmin: (s) => s.userInfo?.role === 1,
  },

  actions: {
    setTokens({ accessToken, refreshToken, accessExpireAt: expireAt }) {
      this.accessToken = accessToken
      this.refreshToken = refreshToken
      // 登录成功即退出游客态，避免标记残留到下一次匿名浏览
      this.guest = false
      try { localStorage.removeItem(GUEST_KEY) } catch { /* 隐私模式忽略 */ }
      localStorage.setItem('access_token', accessToken)
      localStorage.setItem('refresh_token', refreshToken)
      /*
       * 记录 access 到期时刻（epoch 秒）。
       * 后端 TokenPairVO 一直有下发这个值，但**此前的实现完全没用它** ——
       * localStorage 里存了、内存里存了、然后从不读。于是 15 分钟一到必然
       * 先吃一个 401 才被动刷新，页面会闪一下。现在它有了用处：
       * 见 /utils/tokenRenew.js 的主动续期。
       */
      if (expireAt) {
        accessExpireAt = Number(expireAt)
        // 存 localStorage 是为了刷新页面后仍能判断「该不该立刻续期」
        try { localStorage.setItem('access_expire_at', String(accessExpireAt)) } catch { /* 隐私模式忽略 */ }
      }
    },

    setUserInfo(info) {
      this.userInfo = info
      localStorage.setItem('user_info', JSON.stringify(info))
    },

    async login(username, password, rememberMe = false) {
      const data = await request.post('/auth/login', { username, password, rememberMe }, { silent: true })
      this.setTokens(data)
      return data
    },

    async loginByCode(email, code) {
      const data = await request.post('/auth/login-code', { email, code }, { silent: true })
      this.setTokens(data)
      return data
    },

    async register(payload) {
      /*
       * 2026-10-21 账号体系调整：邮箱必填并带验证码，学号选填。
       *
       * 这里用白名单重组请求体，所以**每个要发给后端的字段都必须显式列出**。
       * 加 emailCode 时如果漏了这行，前端看着填了码、请求里却没有它，
       * 后端会回「请填写邮箱验证码」，而请求体看起来完全正常 —— 排查毫无线索。
       */
      const { studentNo, password, nickname, email, emailCode, identity } = payload
      const body = { password, nickname }
      if (email && email.trim()) body.email = email.trim()
      if (emailCode && emailCode.trim()) body.emailCode = emailCode.trim()
      // 学号选填：有值才带。空串等于「不填」，不如干脆不发。
      if (studentNo && studentNo.trim()) body.studentNo = studentNo.trim()
      if (identity) {
        body.schoolId = identity.schoolId
        body.deptId = identity.deptId
        body.gradeId = identity.gradeId
        body.majorId = identity.majorId
      }
      const data = await request.post('/auth/register', body, { silent: true })
      this.setTokens(data)
      return data
    },

    async bindEmail(email, code) {
      const data = await request.post('/user/bind-email', { email, code })
      // 绑定后刷新 userInfo(含 emailBound / 脱敏 email)
      this.setUserInfo(data)
      return data
    },

    /**
     * 刷新 token。
     *
     * 【单飞】并发调用共享同一个请求，见文件顶部 refreshInflight 的注释。
     * 没有这个保护时，/ws/chat + /ws/notify + HTTP 401 三路并发会用同一个
     * 旧 jti 打三次，后端判重用并吊销 → 用户莫名被踢。
     *
     * 【超时】裸 axios 此前**没有传 timeout**（request.js 实例是 30s，这里没有）。
     * 移动网络切换 / TCP 半开时请求会永久 pending，而 request.js 的 isRefreshing
     * 一旦置 true 只有 then/catch 会复位 —— 永久挂起就意味着此后所有 401 请求
     * 全部排队、永不返回，表现为「点了没反应也没报错」。所以这里必须给超时。
     */
    async refresh() {
      // 已有刷新在途：直接复用，不再发第二个请求
      if (refreshInflight) return refreshInflight

      refreshInflight = (async () => {
        // 直接用原始 axios(不走拦截器,避免循环)
        const axios = (await import('axios')).default
        const { data } = await axios.post(
          (import.meta.env.VITE_API_BASE_URL || '/api') + '/auth/refresh',
          { refreshToken: this.refreshToken },
          { timeout: 8000 }, // 与下面 request.js 的兜底超时配合，避免永久挂起
        )
        if (data.code !== 200) {
          throw new Error(data.message)
        }
        this.setTokens(data.data)
        return data.data
      })()

      try {
        return await refreshInflight
      } finally {
        // 无论成功失败都要清空，否则一次失败会让后续刷新永远拿到旧的 rejected Promise
        refreshInflight = null
      }
    },

    /**
     * 距离 access token 过期还有多少秒（负数=已过期）。
     * 供主动续期判断用；未记录到期时刻时返回 null（含义是「不知道」，
     * 调用方应退化成「不主动续期、等 401」而不是按 0 处理）。
     */
    accessSecondsLeft() {
      if (!accessExpireAt) return null
      return Math.round(accessExpireAt - Date.now() / 1000)
    },

    async fetchMe() {
      const data = await request.get('/user/me')
      this.setUserInfo(data)
      return data
    },

    /** 游客登录：清掉可能残留的登录态，只留本地游客标记，不发任何请求 */
    enterGuest() {
      this.accessToken = ''
      this.refreshToken = ''
      this.userInfo = null
      localStorage.removeItem('access_token')
      localStorage.removeItem('refresh_token')
      localStorage.removeItem('user_info')
      localStorage.removeItem('access_expire_at')
      accessExpireAt = 0
      refreshInflight = null
      this.guest = true
      try { localStorage.setItem(GUEST_KEY, '1') } catch { /* 隐私模式忽略 */ }
    },

    async logout() {
      try {
        await request.post('/auth/logout')
      } finally {
        this.clear()
      }
    },

    clear() {
      this.accessToken = ''
      this.refreshToken = ''
      this.userInfo = null
      this.guest = false
      localStorage.removeItem('access_token')
      localStorage.removeItem('refresh_token')
      localStorage.removeItem('user_info')
      localStorage.removeItem('access_expire_at')
      localStorage.removeItem(GUEST_KEY)
      // 内存态也要复位，否则登出后残留的到期时刻会影响下一次登录的续期判断
      accessExpireAt = 0
      refreshInflight = null
    },
  },
})
