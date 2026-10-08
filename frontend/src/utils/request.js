import axios from 'axios'
import { ElMessage } from '@/utils/feedback'
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

function flushQueue(newAccessToken, failed = false) {
  pendingQueue.forEach((cb) => cb(newAccessToken, failed))
  pendingQueue = []
}

// 请求拦截器:注入 token
request.interceptors.request.use((config) => {
  const user = useUserStore()
  if (user.accessToken) {
    config.headers.Authorization = `Bearer ${user.accessToken}`
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
    // 业务错误
    ElMessage.error(message || '操作失败')
    return Promise.reject(new Error(message))
  },
  (error) => {
    const status = error.response?.status
    const original = error.config

    // 401:尝试刷新
    if (status === 401 && !original._retried) {
      original._retried = true
      const user = useUserStore()
      if (!user.refreshToken) {
        handleLogout(error.response?.data?.message || '登录已过期')
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

    // 其他错误
    ElMessage.error(error.response?.data?.message || error.message || '网络错误')
    return Promise.reject(error)
  }
)

function handleLogout(msg) {
  const user = useUserStore()
  user.clear()
  if (msg) ElMessage.warning(msg)
  const returnUrl = window.location.pathname + window.location.search
  router.push({ path: '/login', query: { returnUrl } })
}

export default request
