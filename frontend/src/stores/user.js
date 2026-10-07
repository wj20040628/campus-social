import { defineStore } from 'pinia'
import request from '@/utils/request'

/**
 * user store:唯一持 token 的地方
 * accessToken / refreshToken 持久化到 localStorage
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    accessToken: localStorage.getItem('access_token') || '',
    refreshToken: localStorage.getItem('refresh_token') || '',
    userInfo: JSON.parse(localStorage.getItem('user_info') || 'null'),
  }),

  getters: {
    hasToken: (s) => !!s.accessToken,
    nickname: (s) => s.userInfo?.nickname || s.userInfo?.username || '未登录',
    /** 是否管理员(role=1) */
    isAdmin: (s) => s.userInfo?.role === 1,
  },

  actions: {
    setTokens({ accessToken, refreshToken, accessExpireAt }) {
      this.accessToken = accessToken
      this.refreshToken = refreshToken
      localStorage.setItem('access_token', accessToken)
      localStorage.setItem('refresh_token', refreshToken)
    },

    setUserInfo(info) {
      this.userInfo = info
      localStorage.setItem('user_info', JSON.stringify(info))
    },

    async login(username, password) {
      const data = await request.post('/auth/login', { username, password })
      this.setTokens(data)
      return data
    },

    async loginByCode(email, code) {
      const data = await request.post('/auth/login-code', { email, code })
      this.setTokens(data)
      return data
    },

    async register(username, password, studentNo, verifyCode, nickname) {
      const data = await request.post('/auth/register', {
        username, password, studentNo, verifyCode, nickname,
      })
      this.setTokens(data)
      return data
    },

    async refresh() {
      // 直接用原始 axios(不走拦截器,避免循环)
      const axios = (await import('axios')).default
      const { data } = await axios.post(
        (import.meta.env.VITE_API_BASE_URL || '/api') + '/auth/refresh',
        { refreshToken: this.refreshToken }
      )
      if (data.code !== 200) {
        throw new Error(data.message)
      }
      this.setTokens(data.data)
      return data.data
    },

    async fetchMe() {
      const data = await request.get('/user/me')
      this.setUserInfo(data)
      return data
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
      localStorage.removeItem('access_token')
      localStorage.removeItem('refresh_token')
      localStorage.removeItem('user_info')
    },
  },
})
