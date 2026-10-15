<template>
  <!--
    群信息头(ChatThread 顶部,仅群聊时渲染)。
    显示:群名 · N 人;若有公告,一条公告条(可点编辑,群主)。
    右侧「成员」入口:弹成员名单(群主置顶带"群主"标、自己带"我")。
    群主才见的操作:拉人/改名/公告/转让/踢人(在名单里点成员);成员见「退出群聊」。
    所有操作事件透传给 useChatCore 的 handler。
  -->
  <div class="gh" role="region" aria-label="群信息头">
    <div class="gh__main">
      <div class="gh__title">
        <el-icon :size="18" class="gh__icon"><Avatar /></el-icon>
        <span class="gh__name" :title="group.name">{{ group.name }}</span>
        <span class="gh__count ys-num">{{ group.memberCount || info?.memberCount || 0 }} 人</span>
      </div>

      <!-- 公告条:有公告才显示;群主可点改 -->
      <button
        v-if="info?.announcement"
        type="button"
        class="gh__announce"
        :class="{ 'gh__announce--edit': isOwner }"
        :aria-label="isOwner ? '编辑群公告' : '查看群公告'"
        @click="onAnnounceTap"
      >
        <el-icon :size="14" class="gh__announce-icon"><Bell /></el-icon>
        <span class="gh__announce-text">{{ info.announcement }}</span>
        <span v-if="isOwner" class="gh__announce-edit">编辑</span>
      </button>
    </div>

    <!-- 右侧操作:成员 / 退出群聊(成员) 或 更多(群主) -->
    <div class="gh__actions">
      <!--
        拉人对所有群成员开放(后端 assertMember),不再仅群主。
        群管理(改名/公告/全员禁言/转让/退出)仅群主可见 —— 普通成员
        点成员按钮走「拉人 + 退出群聊」(见成员名单底部),不再有管理项。
      -->
      <button
        type="button"
        class="gh__btn"
        title="拉人入群"
        aria-label="拉人入群"
        @click="addSheetOpen = true"
      >＋</button>
      <button
        v-if="isOwner"
        type="button"
        class="gh__btn"
        title="群管理"
        aria-label="群管理"
        @click="menuOpen = !menuOpen"
      ><el-icon :size="18"><Setting /></el-icon></button>
      <button
        type="button"
        class="gh__btn"
        :title="isOwner ? '成员' : '成员 / 退出群聊'"
        @click="membersOpen = true"
      >成员</button>
    </div>

    <!-- 群主「群管理」小弹层:改名 / 公告 / 全员禁言 / 转让 / 退出群聊 -->
    <div v-if="menuOpen" class="gh__menu" role="menu" @click="menuOpen = false">
      <button type="button" class="gh__menu-item" @click.stop="renameOpen = true">
        <el-icon :size="16"><Edit /></el-icon> 改名
      </button>
      <button type="button" class="gh__menu-item" @click.stop="announceOpen = true">
        <el-icon :size="16"><Bell /></el-icon> 公告
      </button>
      <button
        type="button"
        class="gh__menu-item"
        :class="{ 'is-on': anyMuted }"
        @click.stop="toggleMuteAll"
      >
        <el-icon :size="16"><Mute /></el-icon> {{ anyMuted ? '解除全员禁言' : '全员禁言' }}
      </button>
      <button type="button" class="gh__menu-item" @click.stop="transferOpen = true">
        <el-icon :size="16"><Switch /></el-icon> 转让群主
      </button>
      <button type="button" class="gh__menu-item gh__menu-item--danger" @click.stop="onLeaveGroup">
        <el-icon :size="16"><Back /></el-icon> 退出群聊
      </button>
    </div>

    <!-- 成员名单弹层(2026-10-15 拆去 GroupMembersSheet.vue:独立关注点,
        全屏列表 + 自己的操作/底部动作;开合仍由本页持有) -->
    <GroupMembersSheet
      v-model="membersOpen"
      :group="group"
      :info="info"
      :is-owner="isOwner"
      :on-kick="onKick"
      :on-mute-member="onMuteMember"
      :on-leave-group="onLeaveGroup"
      @add="addSheetOpen = true"
      @transfer="transferOpen = true"
    />

    <!-- 拉人弹层:传 info.members,让 sheet 剔除已在群里的人和我自己 -->
    <AddMembersSheet
      v-if="addSheetOpen"
      v-model="addSheetOpen"
      :group-id="groupId"
      :existing-member-ids="info?.members?.map((m) => m.user?.id).filter(Boolean) || []"
      @added="onAdd"
    />

    <!-- 改名对话框 -->
    <el-dialog v-model="renameOpen" title="改群名" width="360px" append-to-body>
      <el-input v-model="renameVal" maxlength="20" show-word-limit placeholder="群名称" />
      <template #footer>
        <el-button @click="renameOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!renameVal.trim()" @click="submitRename">确定</el-button>
      </template>
    </el-dialog>

    <!-- 公告对话框 -->
    <el-dialog v-model="announceOpen" title="群公告" width="360px" append-to-body>
      <el-input
        v-model="announceVal"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="群公告(留空=清除)"
      />
      <template #footer>
        <el-button @click="announceOpen = false">取消</el-button>
        <el-button type="primary" @click="submitAnnounce">确定</el-button>
      </template>
    </el-dialog>

    <!-- 转让群主:弹成员名单挑一个 -->
    <el-dialog v-model="transferOpen" title="转让群主" width="400px" append-to-body>
      <p class="gh__transfer-hint">把群主位传给一位成员,你降为普通成员留在群里。</p>
      <ul class="gh__transfer-list">
        <li v-for="mm in transferTargets" :key="mm.user?.id" class="gh__transfer-row">
          <el-avatar :size="36" :src="resolveUrl(mm.user?.avatarUrl)" />
          <span class="gh__transfer-name">{{ mm.user?.nickname || mm.user?.username || '成员' }}</span>
          <button type="button" class="gh__transfer-go" @click="doTransfer(mm.user.id)">转让</button>
        </li>
      </ul>
      <template #footer>
        <el-button @click="transferOpen = false">取消</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 群信息头:群名/人数/公告 + 群主/成员操作入口 + 三个对话框(改名/公告/转让)。
 * 仿微信/QQ 群顶栏,按角色显隐:群主见管理项,成员见退出。
 *
 * 2026-10-15 拆分:成员名单弹层搬去 GroupMembersSheet.vue(独立关注点),
 * 样式外置 GroupHead.css。本文件从 566 行降到约 250 行。
 */
import { ref, computed } from 'vue'
import {
  Avatar, Bell, Edit, Switch, Back, Mute, Setting,
} from '@element-plus/icons-vue'
import AddMembersSheet from './AddMembersSheet.vue'
import GroupMembersSheet from './GroupMembersSheet.vue'
import { resolveUrl } from '@/utils/resolve'

const props = defineProps({
  /** 当前群卡片(GroupVO,至少含 id/name/memberCount) */
  group: { type: Object, required: true },
  /** 群信息头(GroupMemberInfoVO),含 announcement + members + isOwner/isSelf */
  info: { type: Object, default: null },
  /** 拉人成功:透传给 useChatCore.onAddMembers(userIds) */
  onAdd: { type: Function, required: true },
  /** 踢人:透传 useChatCore.onKick(userId) */
  onKick: { type: Function, required: true },
  /** 退出群聊:透传 useChatCore.onLeaveGroup() */
  onLeaveGroup: { type: Function, required: true },
  /** 转让:透传 useChatCore.onTransfer(newOwnerId) */
  onTransfer: { type: Function, required: true },
  /** 改名:透传 useChatCore.onRename(name) */
  onRename: { type: Function, required: true },
  /** 设公告:透传 useChatCore.onSetAnnouncement(text) */
  onSetAnnouncement: { type: Function, required: true },
  /** 禁言某成员:透传 useChatCore.onMuteMember(userId, muted) */
  onMuteMember: { type: Function, default: () => {} },
  /** 禁言全员:透传 useChatCore.onMuteAll(muted) */
  onMuteAll: { type: Function, default: () => {} },
})

const emit = defineEmits(['renamed'])

const isOwner = computed(() => {
  // 我是群主:信息头里 members 带 isSelf 且 isOwner,或 info.ownerId === 我
  const self = props.info?.members?.find((m) => m.isSelf)
  return !!self?.isOwner
})

const membersOpen = ref(false)
const menuOpen = ref(false)
const addSheetOpen = ref(false)
const renameOpen = ref(false)
const announceOpen = ref(false)
const transferOpen = ref(false)
const renameVal = ref('')
const announceVal = ref('')

/**
 * 群 id:兼容两种入参形态 ——
 *  - GroupMemberInfoVO(当前用法):字段是 groupId
 *  - GroupVO(未来可能):字段是 id
 * 取不到时 undefined,AddMembersSheet 收到 undefined 会由后端 400 兜底。
 */
const groupId = computed(() => props.group.groupId ?? props.group.id)

// 转让目标:所有非我、非群主的普通成员(群主自己已在,不能传给自己)
const transferTargets = computed(() =>
  (props.info?.members || []).filter((m) => !m.isSelf && !m.isOwner),
)

// 是否已有任一成员被禁言:决定「全员禁言」按钮显示「解除全员禁言」还是「全员禁言」
const anyMuted = computed(() =>
  (props.info?.members || []).some((m) => m.muted && !m.isOwner),
)

/** 全员禁言切换:有任一成员被禁言 → 解除全员,否则全员禁言 */
function toggleMuteAll() {
  props.onMuteAll?.(!anyMuted.value)
}

function onAnnounceTap() {
  if (isOwner.value) {
    announceVal.value = props.info?.announcement || ''
    announceOpen.value = true
  }
}

function submitRename() {
  const name = renameVal.value.trim()
  if (!name) return
  renameOpen.value = false
  props.onRename(name)
  emit('renamed', name)
}

function submitAnnounce() {
  announceOpen.value = false
  props.onSetAnnouncement(announceVal.value.trim())
}

function doTransfer(uid) {
  transferOpen.value = false
  if (!uid) return
  if (window.confirm('确定把群主转让给这位成员?')) {
    props.onTransfer(uid)
  }
}
</script>


<style scoped src="./GroupHead.css"></style>
