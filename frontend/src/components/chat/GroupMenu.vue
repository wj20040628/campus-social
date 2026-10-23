<template>
  <!--
    群聊「⋯」菜单(2026-10-20 由 GroupHead.vue 改造,2026-10-21 重排)。
    改版前是群名下面第二行的三个圆钮,外加一条常驻公告条,手机上四分之一屏没了。
    2026-10-21 又从「六项平铺的命令列表」改成「两组状态面板」:
    每一行右侧显示当前值,让菜单先承担「查看」,再承担「操作」。
  -->
  <div class="gm">
    <button
      type="button"
      class="gm__btn"
      aria-label="群聊设置"
      :aria-expanded="menuOpen"
      @click="menuOpen = !menuOpen"
    >
      <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
        <circle cx="5" cy="12" r="1.7" />
        <circle cx="12" cy="12" r="1.7" />
        <circle cx="19" cy="12" r="1.7" />
      </svg>
    </button>

    <div v-if="menuOpen" class="gm__scrim" @click="menuOpen = false"></div>

    <div v-if="menuOpen" class="gm__menu" role="menu">
      <!-- 管理组:5 行,每行右侧是当前值 -->
      <div class="gm__grp">
        <button type="button" class="gm__row" role="menuitem" @click="pick('members')">
          <span class="gm__label">成员</span>
          <span class="gm__v">{{ memberCount ? `${memberCount} 人` : '' }}</span>
          <svg class="gm__go" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        </button>

        <button type="button" class="gm__row" role="menuitem" @click="pick('announce')">
          <span class="gm__label">群公告</span>
          <span class="gm__v">{{ announcement || '未设置' }}</span>
          <svg class="gm__go" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        </button>

        <button v-if="isOwner" type="button" class="gm__row" role="menuitem" @click="pick('rename')">
          <span class="gm__label">群聊名称</span>
          <span class="gm__v">{{ group.name || '' }}</span>
          <svg class="gm__go" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        </button>

        <!--
          全员禁言用开关而不是可点条目:开关同时表达「当前状态」和「可以切换」,
          切换后不关菜单,用户能立刻看到它变成开启态。
        -->
        <div v-if="isOwner" class="gm__row gm__row--tg">
          <span class="gm__label">全员禁言</span>
          <button
            type="button"
            class="gm__tg"
            :class="{ 'is-on': muteAll }"
            role="switch"
            :aria-checked="muteAll"
            aria-label="全员禁言"
            @click="onToggleMuteAll"
          >
            <i />
          </button>
        </div>

        <button v-if="isOwner" type="button" class="gm__row" role="menuitem" @click="pick('transfer')">
          <span class="gm__label">转让群主</span>
          <svg class="gm__go" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        </button>
      </div>

      <!-- 危险组:单独一张卡,与上组留 8px。分组本身就是一道减速带 -->
      <div class="gm__grp">
        <button type="button" class="gm__row gm__row--danger" role="menuitem" @click="pick('leave')">
          <span class="gm__label">退出群聊</span>
        </button>
      </div>
    </div>

    <!-- 成员名单(群里每个人都能看;点某一行再对这个人生效) -->
    <GroupMembersSheet
      v-model="membersOpen"
      :info="info"
      @add="onInvite"
      @pick="onPickMember"
    />

    <!-- 对单个成员的操作:查看主页 / 禁言 / 移出(移出带二次确认) -->
    <GroupMemberActions
      v-model="actionOpen"
      :target="actionTarget"
      :is-owner="isOwner"
      @profile="goProfile"
      @mute="onMuteMember"
      @kick="onKick"
    />

    <!-- 邀请成员 -->
    <AddMembersSheet
      v-if="inviteOpen"
      v-model="inviteOpen"
      :group-id="groupId"
      :existing-member-ids="existingIds"
      @added="onAdd"
    />

    <GroupRenameDialog v-model="renameOpen" :name="group.name" @submit="onRenameSubmit" />

    <GroupTransferDialog v-model="transferOpen" :members="info?.members || []" @submit="onTransfer" />

    <!-- 全员禁言:只拦「开启」。「解除」是安全动作,多一次确认只会让人以为系统在犹豫 -->
    <ConfirmDialog
      v-model="muteConfirmOpen"
      title="全员禁言?"
      :text="`除你之外的 ${otherCount} 位成员将无法发言。可以随时解除。`"
      :confirm-text="`禁言 ${otherCount} 人`"
      @confirm="onMuteAll?.(true)"
    />

    <ConfirmDialog
      v-model="leaveConfirmOpen"
      title="退出群聊?"
      :text="leaveText"
      confirm-text="退出"
      danger
      @confirm="onLeaveGroup?.()"
    />
  </div>
</template>

<script setup>
/**
 * 群操作菜单。
 *
 * 2026-10-21 三条结构性改动:
 * 1. 菜单从「命令列表」变「状态面板」—— 每行右侧显示当前值。用户点开菜单的
 *    第一目的是确认状态(群名是什么、公告写了什么),第二才是改。
 * 2. 「改群名」改叫「群聊名称」、「拉人入群」不再单独占一行(入口挪到成员表顶部)。
 * 3. 危险操作(退出)独立成卡;全员禁言改开关,且「开启」要确认。
 *
 * 退出入口对普通成员也开放 —— 原来普通成员的退出在成员名单底部,
 * 名单底部那三个动作这一版删掉了,退出必须在这里有位置,否则普通成员退不了群。
 */
import { ref, computed, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import AddMembersSheet from './AddMembersSheet.vue'
import GroupMembersSheet from './GroupMembersSheet.vue'
import GroupMemberActions from './GroupMemberActions.vue'
import GroupRenameDialog from './GroupRenameDialog.vue'
import GroupTransferDialog from './GroupTransferDialog.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'

const props = defineProps({
  /** 当前群卡片(GroupVO / GroupMemberInfoVO,至少含 id|groupId、name、memberCount) */
  group: { type: Object, required: true },
  /** 群信息头(GroupMemberInfoVO),含 announcement + members + isOwner/isSelf */
  info: { type: Object, default: null },
  onAdd: { type: Function, required: true },
  onKick: { type: Function, required: true },
  onLeaveGroup: { type: Function, required: true },
  onTransfer: { type: Function, required: true },
  onRename: { type: Function, required: true },
  onMuteMember: { type: Function, default: () => {} },
  onMuteAll: { type: Function, default: () => {} },
})

/** 公告对话框在 ChatThread 持有(聊天页顶部的公告条也要打开它) */
const emit = defineEmits(['open-announce'])

const isOwner = computed(() => !!props.info?.members?.find((m) => m.isSelf)?.isOwner)

const router = useRouter()

const membersOpen = ref(false)
/** 成员操作表针对的那个人(GroupMemberInfoVO.members 里的一项) */
const actionTarget = ref(null)
const actionOpen = ref(false)
const menuOpen = ref(false)
const inviteOpen = ref(false)
const renameOpen = ref(false)
const transferOpen = ref(false)
const muteConfirmOpen = ref(false)
const leaveConfirmOpen = ref(false)

/** 群 id:兼容 GroupMemberInfoVO(groupId)与 GroupVO(id) */
const groupId = computed(() => props.group.groupId ?? props.group.id)

const memberCount = computed(
  () => props.info?.memberCount ?? props.group?.memberCount ?? props.info?.members?.length ?? 0,
)

const announcement = computed(() => props.info?.announcement || '')

const existingIds = computed(() =>
  (props.info?.members || []).map((m) => m.user?.id).filter(Boolean),
)

/** 除群主以外的成员(群主恒不被禁言,不参与判定) */
const others = computed(() => (props.info?.members || []).filter((m) => !m.isOwner))
const otherCount = computed(() => others.value.length)

/**
 * 是否「全员禁言」= 除群主外**所有人**都被禁言。
 *
 * 改版前这里是 some(...):只要有一个成员被单独禁言,菜单就显示「解除全员禁言」,
 * 点下去调 onMuteAll(false) 把全群解禁 —— 一个单人禁言操作会污染群级状态。
 * 必须是 every,并且排除空数组(every 对空数组返回 true)。
 */
const muteAll = computed(
  () => others.value.length > 0 && others.value.every((m) => m.muted),
)

/**
 * 退出文案按后端真实行为写。
 * GroupServiceImpl.leaveGroup:群主退出会自动把群主交给「第一个非我成员」;
 * 只剩自己则解散(物理清四张表)。文案不提这个的话,群主退出会以为群会消失。
 */
const leaveText = computed(() => {
  if (!isOwner.value) {
    return '退出后这个群会从你的消息列表里移除。想回来需要有人重新邀请。'
  }
  if (memberCount.value > 1) {
    return '你是群主。退出后群主会自动交给群里另一位成员,你不再是这个群的一员。'
  }
  return '群里只有你一个人。退出后这个群会解散,聊天记录一并删除。'
})

/** 先关菜单再开层:菜单不关会和新弹层抢焦点 */
function pick(kind) {
  menuOpen.value = false
  if (kind === 'members') membersOpen.value = true
  else if (kind === 'announce') emit('open-announce')
  else if (kind === 'rename') renameOpen.value = true
  else if (kind === 'transfer') transferOpen.value = true
  else if (kind === 'leave') leaveConfirmOpen.value = true
}

/** 解除不需要确认;开启才拦 */
function onToggleMuteAll() {
  if (muteAll.value) {
    props.onMuteAll?.(false)
    return
  }
  muteConfirmOpen.value = true
}

/**
 * 名单 → 另一层操作表的换层。
 *
 * 两个 ActionSheet 不能同时在场(操作表规范之三),所以先收名单再开新的。
 * 用 180ms 而不是 nextTick:收起动画是 150ms,同一 tick 开第二层会看到
 * 两片遮罩叠在一起交叉淡入淡出,像闪了一下。
 */
let sheetTimer = 0
function swapSheet(openNext) {
  membersOpen.value = false
  window.clearTimeout(sheetTimer)
  sheetTimer = window.setTimeout(openNext, 180)
}

function onPickMember(mm) {
  swapSheet(() => {
    actionTarget.value = mm
    actionOpen.value = true
  })
}

/** 邀请成员同样是一张操作表:照这个规矩换层,不能叠在名单上 */
function onInvite() {
  swapSheet(() => {
    inviteOpen.value = true
  })
}

onBeforeUnmount(() => window.clearTimeout(sheetTimer))

/** 查看主页:纯导航,不经过聊天核心 */
function goProfile(uid) {
  if (!uid) return
  router.push(`/user/${uid}`).catch(() => {})
}

function onRenameSubmit(name) {
  props.onRename(name)
}
</script>

<style scoped src="./GroupMenu.css"></style>
