/**
 * 操作日志动作枚举(与后端 audit 写入的真实 action 值一一对应)。
 *
 * 后端约 50 种 action,之前前端只认 12 种,剩下的会以原始英文代码显示在列表里。
 * 这里集中维护,useAuditLog.js 直接引用,避免散落多处且值与后端脱节。
 *
 * t 字段是标签颜色:success / danger / primary / warning / info
 * 颜色只是辅助通道 —— 文字本身(动作名)才是主信息,色觉障碍也能读懂。
 */
export const AUDIT_ACTIONS = [
  // 账号
  { v: 'register', l: '注册', t: 'primary' },
  { v: 'login', l: '登录', t: 'primary' },
  { v: 'logout', l: '退出', t: 'info' },
  { v: 'login_fail', l: '登录失败', t: 'danger' },
  { v: 'forgot_password', l: '找回密码申请', t: 'info' },
  { v: 'reset_password', l: '重置密码', t: 'info' },
  { v: 'change_password', l: '修改密码', t: 'info' },
  { v: 'bind_email', l: '绑定邮箱', t: 'info' },
  { v: 'update_profile', l: '改资料', t: 'info' },
  { v: 'set_stranger_msg', l: '陌生人私信设置', t: 'info' },
  // 圈子
  { v: 'circle_top_post', l: '圈内置顶', t: 'info' },
  { v: 'circle_untop_post', l: '取消置顶', t: 'info' },
  { v: 'circle_takedown_post', l: '圈管下架', t: 'warning' },
  { v: 'circle_move_post', l: '圈内移动', t: 'info' },
  { v: 'circle_ban_member', l: '禁言成员', t: 'warning' },
  { v: 'circle_unban_member', l: '解禁成员', t: 'info' },
  { v: 'circle_appoint_admin', l: '任命圈管', t: 'info' },
  { v: 'circle_revoke_admin', l: '撤销圈管', t: 'warning' },
  { v: 'circle_transfer_owner', l: '转让圈主', t: 'warning' },
  { v: 'circle_ban', l: '封禁圈子', t: 'danger' },
  { v: 'circle_unban', l: '解封圈子', t: 'info' },
  { v: 'circle_disband', l: '解散圈子', t: 'danger' },
  { v: 'circle_restore', l: '恢复圈子', t: 'info' },
  { v: 'reserved_name_add', l: '添加保留圈名', t: 'info' },
  { v: 'reserved_name_remove', l: '删除保留圈名', t: 'warning' },
  // 帖子
  { v: 'create_post', l: '发帖', t: 'primary' },
  { v: 'edit_post', l: '编辑帖', t: 'info' },
  { v: 'delete_post', l: '删帖', t: 'danger' },
  { v: 'review_approve', l: '帖子审核通过', t: 'success' },
  { v: 'review_reject', l: '帖子审核驳回', t: 'danger' },
  { v: 'review_ai_approve', l: 'AI过审', t: 'success' },
  { v: 'review_ai_reject', l: 'AI驳回', t: 'danger' },
  { v: 'review_ai_manual', l: 'AI转人工', t: 'warning' },
  // 活动
  { v: 'signup', l: '活动报名', t: 'primary' },
  { v: 'cancel_signup', l: '取消报名', t: 'warning' },
  // 关系
  { v: 'blacklist', l: '拉黑', t: 'danger' },
  // 校园信息
  { v: 'verify_approve', l: '校园信息已登记', t: 'success' },
  { v: 'verify_reject', l: '校园信息需修改', t: 'danger' },
  { v: 'verify_student_no_submit', l: '提交学号资料', t: 'info' },
  { v: 'verify_auto_approve', l: '学号与归属一致，自动登记', t: 'success' },
  // 举报
  { v: 'report_ban', l: '举报封禁', t: 'danger' },
  { v: 'report_reject', l: '举报驳回', t: 'info' },
  // 反馈
  { v: 'feedback_adopt', l: '采纳反馈', t: 'success' },
  { v: 'feedback_ignore', l: '忽略反馈', t: 'info' },
  // 私信
  { v: 'chat_block_sensitive', l: '私信敏感词拦截', t: 'warning' },
  // 公告
  { v: 'announcement_create', l: '新建公告', t: 'primary' },
  { v: 'announcement_update', l: '编辑公告', t: 'info' },
  { v: 'announcement_remind', l: '公告催读', t: 'info' },
  // 账号封禁(解封走到期任务,记 account_unban)
  { v: 'account_unban', l: '解封账号', t: 'info' },
]

export default AUDIT_ACTIONS
