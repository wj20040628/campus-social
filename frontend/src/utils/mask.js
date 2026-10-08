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

/**
 * 这个人完成了校内身份认证吗(AC-11 / AC-12 的唯一判据)。
 *
 * 为什么不用 `user.verified`:后端 UserVO 里**没有** verified 字段,
 * 写了 `v-if="user.verified"` 的地方永远渲染不出来 —— 徽章全站消失,
 * 而 AC-11 的「未认证不许发帖/搭话」也永远不会触发。
 *
 * 为什么用 id 判而不是名称判:后端 UserVO 的注释写明,
 * 字典化之前注册的用户 deptId/gradeId 为 NULL 但名称有值。
 * 用 `deptId === null` 判定会把这些人错误地推进引导页。
 * 所以判据是**id 与名称同时为空**才算没填。
 *
 * 之所以抽成函数而不是散在各页 inline:Publish / PostDetail / Circles
 * 都要用同一个判据,三处各写一遍必然漂移(上一轮就漂移过:
 * Publish 用了不存在的 verified,Profile 用了正确的 id 判据)。
 *
 * @param {object|null|undefined} user 后端 UserVO
 * @returns {boolean}
 */
export function isIdentityVerified(user) {
  if (!user) return false
  const hasId = !!(user.schoolId && user.deptId && user.gradeId)
  const hasName = !!(user.dept || user.grade)
  return hasId || hasName
}

export default { maskUsername, displayName, isIdentityVerified }
