import request from '@/utils/request'

/** 操作日志(admin):按 动作/操作人/关键字 过滤,游标分页 */
export function logs(lastId, size = 30, filters = {}) {
  const q = [`size=${size}`]
  if (lastId) q.push(`lastId=${lastId}`)
  if (filters.action) q.push(`action=${encodeURIComponent(filters.action)}`)
  if (filters.userId) q.push(`userId=${filters.userId}`)
  if (filters.keyword) q.push(`keyword=${encodeURIComponent(filters.keyword)}`)
  return request.get(`/audit/logs?${q.join('&')}`)
}

export default { logs }
