import request from '@/utils/request'

export function login(username, password) {
  return request.post('/auth/login', { username, password })
}

export function loginByCode(email, code) {
  return request.post('/auth/login-code', { email, code })
}

export function register(username, password, studentNo, verifyCode, nickname) {
  return request.post('/auth/register', { username, password, studentNo, verifyCode, nickname })
}

export function sendCode(target, type = 'register') {
  return request.post('/auth/send-code', { target, type })
}

export function refresh(refreshToken) {
  return request.post('/auth/refresh', { refreshToken })
}

export function logout() {
  return request.post('/auth/logout')
}

export function me() {
  return request.get('/user/me')
}

export function userCard(id) {
  return request.get(`/user/${id}`)
}

/** @ 提及候选:昵称/用户名前缀匹配,返回普通可用用户(排除自己),限 20 */
export function mentionCandidates(kw = '') {
  const q = kw ? `?kw=${encodeURIComponent(kw)}` : ''
  return request.get(`/user/mention/candidates${q}`)
}

export default { login, loginByCode, register, sendCode, refresh, logout, me, userCard, mentionCandidates }
