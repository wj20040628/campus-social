import { ElMessage, ElMessageBox } from '@/utils/feedback'
import * as circle from '@/api/circle'

/**
 * 圈主/圈管理(role<=2)对圈内帖子 + 成员的吧主级操作。
 * ------------------------------------------------------------------
 * 仿 useMktOwnerActions:每个动作自带确认弹窗 + request 失败兜底(toast)。
 * 权限由后端把守(403 NOT_CIRCLE_OWNER),前端只按 myRole 决定「显不显示按钮」。
 *
 * 约定:成功回调 `onPostsChanged`(刷新圈内帖子)、`onMembersChanged`(刷新成员面板)。
 * 圈管理(role=2)不能任命/取消圈管理——由面板按 myRole 自行隐藏入口,这里不挡。
 */
export function useCircleAdminActions(circleId, onPostsChanged, onMembersChanged) {
  /** 置顶(同圈仅留 1 个置顶)。 */
  async function top(post) {
    try {
      await circle.topPost(circleId.value, post.id)
      ElMessage.success('已置顶')
      await onPostsChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  async function untop(post) {
    try {
      await circle.untopPost(circleId.value, post.id)
      ElMessage.success('已取消置顶')
      await onPostsChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  /** 下架(软:status=2,作者可自查,不物理删)。 */
  async function takedown(post) {
    try {
      await ElMessageBox.confirm(
        `确定下架「${post.content?.slice(0, 24) || '该帖'}」?作者会看到「已下架」。`,
        '下架帖子',
        { type: 'warning', confirmButtonText: '下架', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    try {
      await circle.takedownPost(circleId.value, post.id)
      ElMessage.success('已下架')
      await onPostsChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  /** 移帖:toCircleId=null 移出该圈、变成无圈帖子(回到首页 Feed「全部」)。 */
  async function move(post, toCircleId) {
    if (!toCircleId) {
      try {
        await ElMessageBox.confirm(
          `把这条帖子移出当前圈子、变成无圈帖子?之后它会出现在首页 Feed(不再属于任何圈子)。`,
          '移出圈子',
          { type: 'warning', confirmButtonText: '移出', cancelButtonText: '取消' },
        )
      } catch {
        return
      }
    }
    try {
      await circle.movePost(circleId.value, post.id, toCircleId)
      ElMessage.success(toCircleId ? '已移帖' : '已移出该圈,回到首页 Feed')
      await onPostsChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  // ---------------- 成员处置 ----------------

  /** 封禁圈内成员(until=null 长期)。 */
  async function ban(member, until) {
    const label = member.nickname || '该成员'
    try {
      await ElMessageBox.confirm(
        until ? `封禁「${label}」至 ${until}` : `封禁「${label}」(长期),禁发帖/评论/点赞`,
        '封禁成员',
        { type: 'warning', confirmButtonText: '封禁', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    try {
      await circle.banMember(circleId.value, member.userId, until)
      ElMessage.success('已封禁')
      await onMembersChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  async function unban(member) {
    try {
      await circle.unbanMember(circleId.value, member.userId)
      ElMessage.success('已解禁')
      await onMembersChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  /** 任命圈管理(仅圈主;面板按 myRole 隐藏 role=2 的此入口)。 */
  async function appoint(member) {
    const label = member.nickname || '该成员'
    try {
      await ElMessageBox.confirm(`任命「${label}」为圈管理?圈管理可管理帖子但不能再任命别人。`, '任命圈管理', {
        type: 'warning', confirmButtonText: '任命', cancelButtonText: '取消',
      })
    } catch {
      return
    }
    try {
      await circle.appointAdmin(circleId.value, member.userId)
      ElMessage.success('已任命圈管理')
      await onMembersChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  async function revoke(member) {
    const label = member.nickname || '该成员'
    try {
      await circle.revokeAdmin(circleId.value, member.userId)
      ElMessage.success('已取消圈管理')
      await onMembersChanged?.()
    } catch {
      /* request.js 已弹错 */
    }
  }

  return { top, untop, takedown, move, ban, unban, appoint, revoke }
}

export default useCircleAdminActions
