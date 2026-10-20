import request from '@/utils/request'

/** 操作日志(admin):按 动作/操作人/关键字/昵称/时间范围 过滤,游标分页 */
export function logs(lastId, size = 30, filters = {}) {
  const q = [`size=${size}`]
  if (lastId) q.push(`lastId=${lastId}`)
  if (filters.action) q.push(`action=${encodeURIComponent(filters.action)}`)
  if (filters.userId) q.push(`userId=${filters.userId}`)
  if (filters.keyword) q.push(`keyword=${encodeURIComponent(filters.keyword)}`)
  if (filters.nickname) q.push(`nickname=${encodeURIComponent(filters.nickname)}`)
  if (filters.start) q.push(`start=${encodeURIComponent(filters.start)}`)
  if (filters.end) q.push(`end=${encodeURIComponent(filters.end)}`)
  return request.get(`/audit/logs?${q.join('&')}`)
}

export default { logs }
