import axios from 'axios'
import { ElMessage } from '@/utils/feedback'
import { AUTH_ERR } from '@/utils/authError'
import { useUserStore } from '@/stores/user'
import router from '@/router'

/**
 * Axios 封装
 * - baseURL 走 import.meta.env.VITE_API_BASE_URL(默认 /api)
 * - 请求拦截器:注入 Authorization: Bearer <access>
 * - 响应拦截器:code!=200 弹 toast;401 触发刷新队列(单例+重放)
 */
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 30 * 1000, // 30s
})

let isRefreshing = false
let pendingQueue = []
/**
 * 「登录过期」是否已经提示过（2026-10-28）。
 *
 * 为什么要这个开关：放一天再打开，首屏十几条并发请求会同时 401。
 * 原来每条都走一次 handleLogout，用户会被「token 无效或已过期」刷满屏，
 * 还夹着未登录请求的「缺少 Authorization header」。现在整段登录态失效
 * 只提示一次、只跳一次登录页；拿到新 token 后自动复位。
 */
let authExpiredNotified = false

function flushQueue(newAccessToken, failed = false) {
  pendingQueue.forEach((cb) => cb(newAccessToken, failed))
  pendingQueue = []
}

// 请求拦截器:注入 token
request.interceptors.request.use((config) => {
  const user = useUserStore()
  if (user.accessToken) {
    config.headers.Authorization = `Bearer ${user.accessToken}`
    // 重新登录/刷新拿到 token 后，允许下一次过期再提示一次
    authExpiredNotified = false
  }
  return config
})

// 响应拦截器
request.interceptors.response.use(
  (resp) => {
    // 后端统一 {code, message, data}
    const { code, message, data } = resp.data
    if (code === 200) {
      return data
    }
    // 业务错误:默认弹 toast;调用方传 silent 时由调用方自行兜底(如引导页吸底栏)
    const err = new Error(message || '操作失败')
    err.isBusiness = true
    err.businessCode = code
    if (!resp.config?.silent) ElMessage.error(message || '操作失败')
    return Promise.reject(err)
  },
  (error) => {
    const status = error.response?.status
    const original = error.config

    /*
     * 登录/注册类端点的 401 不是「登录过期」，而是「这次凭据不对」。
     *
     * 2026-10-26 修：此前 /auth/login 密码错误也走刷新队列分支，
     * 未登录时没有 refreshToken → handleLogout 清 token + 弹 toast + 跳 /login，
     * 页面被重挂载，用户看不到字段旁的内联错误，只看到一闪而过的
     * 「用户名或密码错误」和一个「网络好像断了」的重试条——两句话互相矛盾。
     *
     * 这些端点必须把 401 原样交给调用方（useLoginSubmit / useRegisterSubmit），
     * 由它们做字段级提示。refresh 端点也排除，避免刷新失败时递归刷新。
     */
    const authEndpoint = /\/auth\/(login|login-code|register|send-code|forgot-password|reset-password|refresh)(\?|$)/.test(original?.url || '')

    /*
     * 登录态中途被封（2026-10-26）：
     * AuthInterceptor 对冻结/注销账号统一回 403 + code 1001/1002。
     * 此前只弹一句 toast，用户留在原页面反复点、却没有任何申诉出口。
     * 这里清掉本地登录态并带 code 跳登录页，由 Login.vue 渲染封禁整页态
     * + 申诉按钮（登录端点本身除外，它由 useLoginSubmit 自己分类）。
     */
    const bizCode = error.response?.data?.code
    if (
      !authEndpoint &&
      status === 403 &&
      (bizCode === AUTH_ERR.USER_FROZEN || bizCode === AUTH_ERR.USER_CANCELLED)
    ) {
      const user = useUserStore()
      user.clear()
      if (router.currentRoute.value.name !== 'login') {
        // msg 带上后端说明（封禁截止时间等），封禁整页态不必只剩一句「账号已冻结」
        const msg = error.response?.data?.message
        router.push({ path: '/login', query: msg ? { blocked: bizCode, msg } : { blocked: bizCode } })
      }
      return Promise.reject(error)
    }

    /*
     * 401（2026-10-28 收敛）。
     *
     * 原来只在「首次 401」时进刷新分支，重放后仍 401 的请求会落到下面的
     * 通用错误 toast —— 于是过期后一次开机就弹出多条「token 无效或已过期」；
     * 没有 token 的游客请求（首屏未读/会话等）也会弹「缺少 Authorization header」。
     * 现在 401 一律不逐条弹：
     *   - 已经重放过、或本地本来就没有 token → 静默失败，交给页面自身兜底
     *   - 有 session 但没 refreshToken / 刷新失败 → handleLogout 只提示一次
     */
    if (status === 401 && !authEndpoint) {
      const user = useUserStore()
      if (original._retried || !user.hasToken) {
        return Promise.reject(error)
      }
      original._retried = true
      if (!user.refreshToken) {
        handleLogout('登录已过期,请重新登录')
        return Promise.reject(error)
      }
      if (isRefreshing) {
        // 已有刷新在途:挂起等待
        return new Promise((resolve, reject) => {
          pendingQueue.push((newToken, failed) => {
            if (failed) return reject(error)
            original.headers.Authorization = `Bearer ${newToken}`
            resolve(request(original))
          })
        })
      }
      isRefreshing = true
      return user
        .refresh()
        .then((tokens) => {
          flushQueue(tokens.accessToken)
          isRefreshing = false
          original.headers.Authorization = `Bearer ${tokens.accessToken}`
          return request(original)
        })
        .catch(() => {
          flushQueue(null, true)
          isRefreshing = false
          handleLogout('登录已过期,请重新登录')
          return Promise.reject(error)
        })
    }

    /**
     * 其他错误。
     *
     * `suppressStatuses` 是给调用方的定向静默开关(axios 会把自定义 config
     * 原样带到 error.config 上)。用法:
     *   request.post(url, body, { suppressStatuses: [404] })
     *
     * 为什么需要:校园信息核对里 404 VERIFY_NOT_FOUND(1044) 的含义是
     * 「这条已经被别的设备处置过了」,它不是故障,处理动作是「从列表里去掉」。
     * 而全局这条红色 toast 会先弹一遍,调用方再弹一次「该申请已被处理」,
     * 管理员看到两条互相矛盾的提示。
     *
     * 为什么按 status 精确关而不是整个请求静音:同一个端点仍然可能 500/网络断,
     * 那两种必须出红色 toast。只关 404,其余照旧走全局提示。
     */
    // silent:调用方自行兜底提示(如引导页吸底栏),全局不弹
    if (!error.config?.silent) {
      const suppress = error.config?.suppressStatuses
      if (!Array.isArray(suppress) || !suppress.includes(status)) {
        ElMessage.error(error.response?.data?.message || error.message || '网络错误')
      }
    }
    return Promise.reject(error)
  }
)

function handleLogout(msg) {
  const user = useUserStore()
  const hadSession = !!user.accessToken || !!user.refreshToken
  user.clear()
  /*
   * 必须用 failed=true 唤醒并清空挂起队列（2026-10-21 修）：
   * 残留回调会在换号登录后带着**新账号的 token** 重放**上个账号的请求**（串号），
   * 或在页面跳走后继续 request() → 又一批 401。
   */
  flushQueue(null, true)
  isRefreshing = false
  /*
   * 幂等（2026-10-28）：并发 401 只有第一条会产生提示与跳转。
   * 本地本来就没有 session（游客请求 401、或已经被前面那条清掉）
   * 也不再提示——用户这次打开就是未登录状态，没什么可提醒的。
   */
  if (authExpiredNotified || !hadSession) return
  authExpiredNotified = true
  if (msg) ElMessage.warning(msg)
  const returnUrl = window.location.pathname + window.location.search
  if (router.currentRoute.value.name !== 'login') {
    router.push({ path: '/login', query: { returnUrl } })
  }
}

export default request
