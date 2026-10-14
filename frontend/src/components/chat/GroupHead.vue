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
        ⚙ 群管理(改名/公告/全员禁言/转让/退出)仅群主可见 —— 普通成员
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
      >⚙</button>
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

    <!-- 成员名单弹层(手机 ActionSheet 风格;桌面用抽屉由 CSS 控制) -->
    <ActionSheet v-model="membersOpen" :title="`${group.name} 成员`" height="70vh">
      <p v-if="info?.announcement" class="ghm__announce">
        <el-icon :size="14"><Bell /></el-icon> {{ info.announcement }}
      </p>
      <ul class="ghm__list">
        <li
          v-for="mm in info?.members || []"
          :key="mm.user?.id"
          class="ghm__row"
        >
          <el-avatar :size="40" :src="resolveUrl(mm.user?.avatarUrl)" />
          <div class="ghm__info">
            <div class="ghm__name">
              {{ mm.user?.nickname || mm.user?.username || '成员' }}
              <span v-if="mm.isOwner" class="ghm__tag ghm__tag--owner">群主</span>
              <span v-if="mm.isSelf" class="ghm__tag ghm__tag--self">我</span>
              <span v-if="mm.muted" class="ghm__tag ghm__tag--muted">已禁言</span>
            </div>
            <div class="ghm__sub">{{ maskUsername(mm.user?.username) }}</div>
          </div>
          <!-- 群主可对普通成员「移出群聊」/「禁言」(不能对自己、不能对群主自己) -->
          <button
            v-if="isOwner && !mm.isSelf && !mm.isOwner"
            type="button"
            class="ghm__kick"
            @click="onKick(mm.user.id)"
          >移出群聊</button>
          <button
            v-if="isOwner && !mm.isOwner && !mm.isSelf"
            type="button"
            class="ghm__mute"
            :class="{ 'is-on': mm.muted }"
            @click="onMuteMember(mm.user.id, !mm.muted)"
          >{{ mm.muted ? '解除禁言' : '禁言' }}</button>
          <button
            v-if="!mm.isSelf"
            type="button"
            class="ghm__go"
            @click="onGoProfile(mm.user.id)"
          >查看主页</button>
        </li>
      </ul>
      <!-- 群主在名单底部:转让 + 拉人 + 退出 -->
      <template v-if="isOwner">
        <button type="button" class="ghm__foot" @click="membersOpen = false; addSheetOpen = true">
          ＋ 拉人入群
        </button>
        <button type="button" class="ghm__foot" @click="membersOpen = false; transferOpen = true">
          转让群主
        </button>
        <button type="button" class="ghm__foot ghm__foot--danger" @click="membersOpen = false; onLeaveGroup()">
          退出群聊
        </button>
      </template>
      <!-- 普通成员:拉人 + 退出(拉人对群成员开放,转让仍限群主) -->
      <template v-else>
        <button type="button" class="ghm__foot" @click="membersOpen = false; addSheetOpen = true">
          ＋ 拉人入群
        </button>
        <button type="button" class="ghm__foot ghm__foot--danger" @click="membersOpen = false; onLeaveGroup()">
          退出群聊
        </button>
      </template>
    </ActionSheet>

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
 * 群信息头:群名/人数/公告 + 成员名单 + 群主/成员操作入口。
 * 仿微信/QQ 群顶栏,按角色显隐:群主见管理项,成员见退出。
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import {
  Avatar, Bell, Edit, Switch, Back, Mute,
} from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import AddMembersSheet from './AddMembersSheet.vue'
import { resolveUrl } from '@/utils/resolve'
import { maskUsername } from '@/utils/mask'

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

const router = useRouter()

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

function onGoProfile(uid) {
  if (!uid) return
  membersOpen.value = false
  router.push(`/user/${uid}`).catch(() => {})
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

<style scoped>
.gh {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ys-space-2);
  position: relative;
  flex: none;
  padding: var(--ys-space-2) var(--ys-space-4);
  background: var(--ys-surface);
  border-bottom: 1px solid var(--ys-border-soft);
}
.gh__main { min-width: 0; flex: 1; }
.gh__title {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
}
.gh__icon { color: var(--ys-accent); flex: none; }
.gh__name {
  font-size: var(--ys-text-md);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gh__count {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  flex: none;
}
.gh__announce {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  width: 100%;
  margin-top: var(--ys-space-1);
  padding: var(--ys-space-1) var(--ys-space-2);
  border: none;
  border-radius: var(--ys-radius-md);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
  font-family: var(--ys-font-body);
  text-align: left;
  cursor: pointer;
}
.gh__announce-icon { flex: none; }
.gh__announce-text {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gh__announce-edit {
  flex: none;
  font-size: var(--ys-text-xs);
  text-decoration: underline;
}
.gh__actions {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  flex: none;
}
.gh__btn {
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: 1px solid var(--ys-border-control);
  border-radius: 50%;
  background: var(--ys-surface);
  color: var(--ys-fg-2);
  font-size: var(--ys-text-sm);
  cursor: pointer;
}
.gh__btn:hover { background: var(--ys-surface-2); }
.gh__btn[aria-label='成员'] {
  width: auto;
  border-radius: var(--ys-radius-lg);
  padding: 0 var(--ys-space-2);
}
.gh__menu {
  position: absolute;
  top: 100%;
  right: var(--ys-space-2);
  /* 必须盖住下方消息列表(.th__list)—— 此前 z-index:10 被困在
     .gh-wrap 的 transform 层叠上下文里,被外层 .input(z-index:1100) 盖住。
     提到 --ys-z-sticky,相对 .th 正常盖消息,低于抽屉(1220)。 */
  z-index: var(--ys-z-sticky);
  min-width: 160px;
  background: var(--ys-surface);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-lg);
  box-shadow: var(--ys-shadow-3);
  padding: var(--ys-space-1);
}
.gh__menu-item {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  width: 100%;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: none;
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-fg);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  text-align: left;
  cursor: pointer;
}
.gh__menu-item:hover { background: var(--ys-surface-2); }
.gh__menu-item--danger { color: var(--ys-accent-2); }

/* 成员名单弹层内部 */
.ghm__announce {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  margin: 0;
  padding: var(--ys-space-1) var(--ys-space-2);
  border-radius: var(--ys-radius-md);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
}
.ghm__list { list-style: none; margin: 0; padding: 0; }
.ghm__row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: var(--ys-space-2) 0;
  border-bottom: 1px solid var(--ys-border-soft);
}
.ghm__info { flex: 1; min-width: 0; }
.ghm__name {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
}
.ghm__tag {
  font-size: 10px;
  padding: 0 4px;
  border-radius: 4px;
  line-height: 16px;
}
.ghm__tag--owner { background: var(--ys-accent-soft); color: var(--ys-accent); }
.ghm__tag--self { background: var(--ys-surface-2); color: var(--ys-muted); }
.ghm__tag--muted { background: var(--ys-danger-soft); color: var(--ys-accent-2); }
.ghm__sub {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  margin-top: 1px;
}
.ghm__kick {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: 1px solid var(--ys-danger);
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-danger);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
.ghm__kick:hover { background: var(--ys-danger-soft); }
.ghm__mute {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: 1px solid var(--ys-muted);
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-muted);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
.ghm__mute:hover { background: var(--ys-surface-2); }
.ghm__mute.is-on { border-color: var(--ys-accent); color: var(--ys-accent); background: var(--ys-accent-soft); }
.ghm__go {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: none;
  background: transparent;
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
.ghm__foot {
  display: block;
  width: 100%;
  min-height: var(--ys-touch-min);
  margin-top: var(--ys-space-1);
  padding: 0 var(--ys-space-2);
  border: none;
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  text-align: center;
  cursor: pointer;
}
.ghm__foot:hover { background: var(--ys-surface-2); }
.ghm__foot--danger { color: var(--ys-accent-2); }
.ghm__foot--danger:hover { background: var(--ys-danger-soft); }

/* 转让列表 */
.gh__transfer-hint {
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}
.gh__transfer-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 280px;
  overflow-y: auto;
}
.gh__transfer-row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: var(--ys-space-1) 0;
}
.gh__transfer-name { flex: 1; font-size: var(--ys-text-sm); color: var(--ys-fg); }
.gh__transfer-go {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
.gh__transfer-go:hover { background: var(--ys-accent-soft); }
</style>
