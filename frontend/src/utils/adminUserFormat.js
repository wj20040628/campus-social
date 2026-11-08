import dayjs from 'dayjs'

/**
 * 用户处置列表与详情抽屉共用的展示口径（2026-11-08）。
 *
 * <p>抽出来不是为了复用几行代码，而是防止两处对「禁言中」判断不一致：
 * 列表把已过期 mute_until 当正常，详情抽屉如果只看字段非空就会显示「禁言中」，
 * 管理员会据此做出错误处置。
 */

export function ageText(u) {
  if (!u?.createTime) return '注册时间未知'
  const days = Math.max(0, dayjs().diff(dayjs(u.createTime), 'day'))
  return `注册 ${dayjs(u.createTime).format('YYYY-MM-DD')}（${days} 天）`
}

/** 禁言中的唯一判定：截止时间在未来，且账号不是封禁/注销态 */
export function isMuted(u) {
  return u?.status !== 1 && u?.status !== 2 && !!u?.muteUntil && dayjs(u.muteUntil).isAfter(dayjs())
}

export function statusText(u) {
  if (!u) return ''
  if (u.status === 2) return '已注销'
  if (u.status === 1) {
    return u.bannedUntil ? `封禁中 · 至 ${dayjs(u.bannedUntil).format('MM-DD HH:mm')}` : '永久封禁'
  }
  if (isMuted(u)) return `禁言中 · 至 ${dayjs(u.muteUntil).format('MM-DD HH:mm')}`
  return '正常'
}

export function verifyText(u) {
  switch (u?.verifyStatus) {
    case 1: return '校园信息已登记'
    case 2: return '校园信息核对中'
    case 3: return '校园信息需修改'
    default: return ''
  }
}

export const REPORT_STATUS = { 0: '待处理', 1: '成立', 2: '驳回' }
export const HANDLE_ACTION = {
  ban: '封禁', mute: '禁言', takedown: '下架', reject: '驳回', ignore: '忽略',
}
export const ACCOUNT_ACTION = {
  account_ban: '封禁',
  account_unban: '解封',
  account_mute: '禁言',
  account_unmute: '解除禁言',
  account_cancel: '注销',
  account_purge: '注销留存清理',
  report_upheld: '举报成立',
}
