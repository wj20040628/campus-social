/**
 * 个人主页的关系动作(2026-10-15 从 Profile.vue 拆出)。
 * ------------------------------------------------------------------
 * 收拢三件事:关注(带本地计数联动)、解除拉黑、发私信。
 * 共同点是「作用在 targetId 上,且要联动本页状态」,与身份头渲染、
 * 三个 Tab 的编排无关。底层 toggleFollow / loadRelation 仍在
 * useProfileRelations 里,这里只做页面侧的联动与跳转。
 */
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { isIdentityVerified } from '@/utils/mask'
import { unblacklist } from '@/api/relation'

export function useProfileActions({
  targetId, user, relStatus, toggleFollow, loadRelation, onNeedVerify,
}) {
  const router = useRouter()
  const userStore = useUserStore()

  /**
   * 关注按钮 + 粉丝数联动。
   *
   * 计数在前端同步改而不是等下一次往返:用户看到「关注成功了但粉丝数没变」
   * 会比慢 300ms 更困惑。
   *
   * 只有拿到明确的三态结果才动计数 —— 失败(null)时不动,
   * 否则一次网络失败会让粉丝数凭空少 1。
   */
  async function toggleFollowAndCount() {
    const result = await toggleFollow()
    if (!result || !user.value) return
    user.value.followerCount = Math.max(
      0,
      (user.value.followerCount || 0) + (result === 'followed' ? 1 : -1),
    )
  }

  /** 解除拉黑。失败时请求层已弹错,关系状态维持原样 */
  async function unblock() {
    try {
      await unblacklist(targetId.value)
      relStatus.value.blacklisted = false
      await loadRelation().catch(() => {})
    } catch {
      /* 留在原地,用户可再点一次 */
    }
  }

  /** AC-11:未认证不跳私信,给说明与出口 */
  function onChatClick() {
    if (!isIdentityVerified(userStore.userInfo)) {
      onNeedVerify()
      return
    }
    router.push(`/chat?to=${targetId.value}`)
  }

  return { toggleFollowAndCount, unblock, onChatClick }
}
