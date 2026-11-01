/**
 * 「刚注册成功」的一次性提示标记。
 *
 * 用 sessionStorage 而不是 localStorage：只在当前标签页本次会话生效，
 * 刷新后不会重复弹；用户忽略 4 秒提示后也不再打扰。
 */
const KEY = 'ys:pwd-hint-pending'

export function markPasswordHintPending() {
  try {
    sessionStorage.setItem(KEY, '1')
  } catch {
    // 隐私模式禁用存储时静默跳过，不影响登录
  }
}

export function consumePasswordHintPending() {
  try {
    if (sessionStorage.getItem(KEY) !== '1') return false
    sessionStorage.removeItem(KEY)
    return true
  } catch {
    return false
  }
}
