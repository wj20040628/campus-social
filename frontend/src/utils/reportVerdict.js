/**
 * 举报处置结论文案（2026-10-31 从 useReportQueue 移出，便于单独维护）。
 *
 * status=1 不能写死「成立并封禁」：同是成立，可能是封禁、禁言、仅下架。
 * 优先按 handleAction/handleDays 说明实际后果；历史行没有这两个字段，
 * 回退到按 targetType 的保守文案。
 *
 * 关键分支：status=0 但 handlerId 非空 = 已忽略（「忽略」按契约不改 status）。
 */
export function verdictText(r) {
  if (!r) return ''
  if (r.status === 1) {
    if (r.handleAction === 'ban') {
      const span = r.handleDays === 0 ? '永久封禁' : `封禁 ${r.handleDays} 天`
      if (r.targetType === 1) return `举报成立，已${span}`
      if (r.targetType === 9) return `举报成立，圈主已被${span}，圈子已解散`
      return `举报成立，发布者已被${span}，内容已下架`
    }
    if (r.handleAction === 'mute') {
      return r.targetType === 1
        ? `举报成立，已禁言 ${r.handleDays} 天`
        : `举报成立，已禁言 ${r.handleDays} 天，内容已下架`
    }
    if (r.handleAction === 'takedown') {
      return r.targetType === 9 ? '举报成立，该圈子已解散' : '举报成立，内容已下架'
    }
    if (r.targetType === 1) return '举报成立，已处置该用户'
    if (r.targetType === 9) return '举报成立，该圈子已解散'
    return '举报成立，内容已下架'
  }
  if (r.status === 2) return '驳回'
  return r.handlerId ? '忽略' : '待处理'
}
