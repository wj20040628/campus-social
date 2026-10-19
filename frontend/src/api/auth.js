import request from '@/utils/request'

export function login(username, password, rememberMe = false) {
  return request.post('/auth/login', { username, password, rememberMe })
}

export function loginByCode(email, code) {
  return request.post('/auth/login-code', { email, code })
}

/**
 * 注册(账号体系重构:学号主账号 + 邮箱安全凭证选填)。
 * @param {{ studentNo:string, password:string, nickname:string, email?:string, identity?:object }} payload
 */
export function register(payload) {
  const { studentNo, password, nickname, email, identity } = payload
  const body = { studentNo, password, nickname }
  // 邮箱选填:有值才带(后端存库前转小写)
  if (email && email.trim()) body.email = email.trim()
  // 注册即认证:填了 13/14 位学号才把学校归属一并提交;否则只注册不认证
  if (identity && identity.schoolId !== undefined) {
    body.schoolId = identity.schoolId
    body.deptId = identity.deptId
    body.gradeId = identity.gradeId
    body.majorId = identity.majorId
  }
  return request.post('/auth/register', body)
}

export function sendCode(target, type = 'register') {
  return request.post('/auth/send-code', { target, type })
}

/** 找回密码(第一步):提交学号,后端向绑定的安全凭证邮箱发重置验证码 */
export function forgotPassword(studentNo) {
  return request.post('/auth/forgot-password', { studentNo })
}

/** 找回密码(第二步):学号 + 邮箱重置验证码 + 新密码 */
export function resetPassword(studentNo, code, newPassword) {
  return request.post('/auth/reset-password', { studentNo, code, newPassword })
}

/** 绑定 / 修改安全凭证邮箱(需登录):邮箱 + 绑定验证码 */
export function bindEmail(email, code) {
  return request.post('/user/bind-email', { email, code })
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

/** 设置「允许陌生人给我发消息」开关(enabled=true 开 / false 关) */
export function setStrangerMsg(enabled) {
  return request.put('/user/stranger-msg', {}, { params: { enabled } })
}

/** @ 提及候选:昵称/用户名前缀匹配,返回普通可用用户(排除自己),限 20 */
export function mentionCandidates(kw = '') {
  const q = kw ? `?kw=${encodeURIComponent(kw)}` : ''
  return request.get(`/user/mention/candidates${q}`)
}

export default {
  login, loginByCode, register, sendCode, forgotPassword, resetPassword, bindEmail,
  refresh, logout, me, userCard, mentionCandidates,
}
