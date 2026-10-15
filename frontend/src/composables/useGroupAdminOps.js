/**
 * 群管理写操作(2026-10-15 从 useChatCore.js 拆出)。
 * ------------------------------------------------------------------
 * 收拢群信息头上的九个动作:拉人/踢人/改名/公告/转让/退群/禁言成员/禁言全员,
 * 以及它们共用的刷新 refreshAfterGroupOp。
 *
 * 共同点:**调一个写接口 + 刷新群列表或成员名单 + 给一句人话提示**,
 * 与会话数据(列表/历史/WS)无关,所以能从 useChatCore 整块搬出来。
 * 错误码分支(1026/1027/1045)逐个保留 —— 那些是后端契约给的具体原因,
 * 笼统说「操作失败」用户不知道该找谁。
 *
 * 对外行为不变:useChatCore 仍以同名函数返回这些操作(GroupHead 通过
 * Chat.vue 拿到的还是同一批 handler)。
 */
import { ElMessage } from '@/utils/feedback'
import {
  addGroupMembers as groupAddMembersApi,
  kickMember as groupKickApi,
  renameGroup as groupRenameApi,
  setGroupAnnouncement as groupAnnouncementApi,
  transferGroupOwner as groupTransferApi,
  leaveGroup as groupLeaveApi,
  muteMember as groupMuteApi,
  muteAllMembers as groupMuteAllApi,
} from '@/api/group'

export function useGroupAdminOps({
  currentGroup, currentKind, groupInfo, messages, groups, loadGroups, loadGroupMembers,
}) {
  /**
   * 刷新左侧群列表 + 当前群信息头。拉人/踢人/改名/公告/转让后调用。
   * 群已解散则清当前群跳列表。
   */
  async function refreshAfterGroupOp() {
    await loadGroups()
    // 若我在左侧列表里已找不到这个群(被解散/我退群),清当前群
    if (currentGroup.value && !groups.value.some((x) => x.id === currentGroup.value.id)) {
      currentGroup.value = null
      currentKind.value = 'dm'
      groupInfo.value = null
      messages.value = []
      return
    }
    await loadGroupMembers()
  }

  /** 拉人:成功后刷新名单 + 人数;满员(1045)给文案 */
  async function onAddMembers(userIds) {
    if (!currentGroup.value || !userIds?.length) return
    try {
      await groupAddMembersApi(currentGroup.value.id, userIds)
      ElMessage.success('已拉入')
      await refreshAfterGroupOp()
    } catch (e) {
      ElMessage.error(e?.code === 1045 ? '群已满员,无法继续拉人' : '拉人失败,再试一次')
    }
  }

  /** 踢人:成功后刷新 */
  async function onKick(userId) {
    if (!currentGroup.value) return
    try {
      await groupKickApi(currentGroup.value.id, userId)
      ElMessage.success('已移出群聊')
      await refreshAfterGroupOp()
    } catch (e) {
      ElMessage.error(e?.code === 1027 ? '仅群主可操作' : '移出失败,再试一次')
    }
  }

  /** 改名:成功后刷新名单(群信息头带群名) */
  async function onRename(name) {
    if (!currentGroup.value || !name?.trim()) return
    try {
      await groupRenameApi(currentGroup.value.id, name.trim())
      currentGroup.value = { ...currentGroup.value, name: name.trim() }
      ElMessage.success('已改名')
      await refreshAfterGroupOp()
    } catch {
      ElMessage.error('改名失败,再试一次')
    }
  }

  /** 设公告:空=清除;成功后刷新 */
  async function onSetAnnouncement(text) {
    if (!currentGroup.value) return
    try {
      await groupAnnouncementApi(currentGroup.value.id, text)
      await loadGroupMembers()
      ElMessage.success(text?.trim() ? '公告已更新' : '公告已清除')
    } catch {
      ElMessage.error('设置公告失败,再试一次')
    }
  }

  /** 转让:成功后我降为普通成员,刷新(名单里 isOwner 标自动变) */
  async function onTransfer(newOwnerId) {
    if (!currentGroup.value) return
    try {
      await groupTransferApi(currentGroup.value.id, newOwnerId)
      ElMessage.success('群主已转让')
      await refreshAfterGroupOp()
    } catch (e) {
      ElMessage.error(e?.code === 1026 ? '对方不是该群成员' : '转让失败,再试一次')
    }
  }

  /** 退群:非群主直接退;群主退则自动转让或解散。跳列表 */
  async function onLeaveGroup() {
    if (!currentGroup.value) return
    const gid = currentGroup.value.id
    try {
      await groupLeaveApi(gid)
      ElMessage.success('已退出群聊')
      await loadGroups()
      currentGroup.value = null
      currentKind.value = 'dm'
      groupInfo.value = null
      messages.value = []
    } catch (e) {
      ElMessage.error(e?.code === 1026 ? '你不是该群成员' : '退出群聊失败,再试一次')
    }
  }

  /* ---------------- 群禁言(群主) ---------------- */

  /** 禁言某成员:成功后刷新名单(muted 标随之变) */
  async function onMuteMember(userId, muted = true) {
    if (!currentGroup.value) return
    try {
      await groupMuteApi(currentGroup.value.id, userId, muted)
      ElMessage.success(muted ? '已禁言' : '已解除禁言')
      await loadGroupMembers()
    } catch (e) {
      ElMessage.error(e?.code === 1027 ? '仅群主可操作' : '操作失败,再试一次')
    }
  }

  /** 禁言全员:成功后刷新名单(全员 muted 标随之变) */
  async function onMuteAll(muted = true) {
    if (!currentGroup.value) return
    try {
      await groupMuteAllApi(currentGroup.value.id, muted)
      ElMessage.success(muted ? '已全员禁言' : '已解除全员禁言')
      await loadGroupMembers()
    } catch (e) {
      ElMessage.error(e?.code === 1027 ? '仅群主可操作' : '操作失败,再试一次')
    }
  }

  return {
    refreshAfterGroupOp,
    onAddMembers, onKick, onRename, onSetAnnouncement, onTransfer, onLeaveGroup,
    onMuteMember, onMuteAll,
  }
}
