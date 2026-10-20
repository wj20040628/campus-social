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

/**
 * 学号认证申诉(2026-10-19):公开端点,无需登录 token。
 * 提交方是「被六层校验挡下、注册没成功」的人(无账号 / 无 token),
 * 所以走 /auth/appeal-student-no 而非需登录的 /feedback。
 * silent:true → 全局拦截器不弹 toast,成功/失败回执全在弹层内联处理。
 * @param {{ studentNo:string, email:string, note?:string }} payload
 */
export function appealStudentNo({ studentNo, email, note }) {
  return request.post('/auth/appeal-student-no', { studentNo, email, note }, { silent: true })
}

/** 绑定 / 修改安全凭证邮箱(需登录):邮箱 + 绑定验证码 */
export function bindEmail(email, code) {
  return request.post('/user/bind-email', { email, code })
}

/** 修改密码(需登录):原密码 + 新密码。silent:错误由弹层内联提示,不弹全局 toast */
export function changePassword(oldPassword, newPassword) {
  return request.put('/user/password', { oldPassword, newPassword }, { silent: true })
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

/**
 * 设置 / 清除个人主页封面(2026-10-20)。
 *
 * <p>传 null 或 '' = 恢复默认渐变封面。**与 updateMe 的 null 语义相反**:
 * 那边 null 是「本次不改」,这边 null 是「清空」。后端为此单独开了
 * PUT /user/cover,不要把这两个合起来用。
 */
export function setCover(coverUrl) {
  return request.put('/user/cover', { coverUrl: coverUrl || null })
}

/**
 * 更新个人资料(昵称/头像/学号/介绍)。
 * 只传要改的字段:null = 本次不改(与 setCover 语义相反,见上)。
 */
export function updateMe(patch) {
  return request.put('/user/me', patch)
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

/** 读取通知偏好:返回 JSON 字符串或 null(=全开) */
export function getNotifyPref() {
  return request.get('/user/notify-pref')
}

/** 写入通知偏好:pref 为 JSON 字符串,如 {"disabled":[2,3]};传 null/''=恢复全开 */
export function setNotifyPref(pref) {
  return request.put('/user/notify-pref', pref, {
    headers: { 'Content-Type': 'application/json' },
  })
}

export default {
  login, loginByCode, register, sendCode, forgotPassword, resetPassword, appealStudentNo,
  bindEmail, changePassword, refresh, logout, me, userCard, updateMe, setCover,
  mentionCandidates, getNotifyPref, setNotifyPref,
}
