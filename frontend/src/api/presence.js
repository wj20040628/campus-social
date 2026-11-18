import request from '@/utils/request'

/** 批量查询在线状态：只返回在线且未隐身的 userId。 */
export function status(ids) {
  return request.get('/presence/status', { params: { ids: ids.join(',') } })
}

/** 首页公共统计：在册用户数 + 在线且未隐身人数。 */
export function stats() {
  return request.get('/presence/stats')
}

/** 设置隐身：hidden=true 表示在线但对他人显示离线。 */
export function setHidden(hidden) {
  return request.put('/user/presence', { hidden })
}

export default { status, stats, setHidden }
