// 用户名展示规则:
// 邮箱 3420232712@qq.com → 只显示 3420232712(不要 @、不要域名)
// 手机号 13800008888 → 138****8888
// 普通用户名 → 原样
export function maskUsername(username) {
  if (!username) return ''
  if (username.includes('@')) {
    const local = username.split('@')[0]
    return local || username
  }
  if (/^1\d{10}$/.test(username)) {
    return username.slice(0, 3) + '****' + username.slice(7)
  }
  return username
}

// 显示名:优先昵称,无昵称则用裁剪后的 username
export function displayName(u) {
  if (!u) return ''
  if (u.nickname) return u.nickname
  return maskUsername(u.username)
}

export default { maskUsername, displayName }
