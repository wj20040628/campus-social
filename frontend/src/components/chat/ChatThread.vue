<template>
  <!-- 消息窗(Chat.vue.md §1.2~§1.4):唯一负责「键盘弹起时输入框不被吞掉」的地方 -->
  <div class="th">
    <!-- 行 0 + 行 2:自绘顶栏与来源上下文条(2026-10-15 拆去 ChatThreadHeader.vue) -->
    <ChatThreadHeader
      :peer-name="peerName"
      :subtitle="groupSub"
      :source-title="sourceTitle"
      @close-source="$emit('close-source')"
    >
      <template v-if="kind === 'group' && groupInfo" #actions>
        <GroupMenu
          :group="groupInfo"
          :info="groupInfo"
          :on-add="onAdd"
          :on-kick="onKick"
          :on-leave-group="onLeaveGroup"
          :on-transfer="onTransfer"
          :on-rename="onRename"
          :on-mute-member="onMuteMember"
          :on-mute-all="onMuteAll"
          @open-announce="announceOpen = true"
        />
      </template>
    </ChatThreadHeader>

    <!-- 群公告:压成单行,点开看全文;群主可在对话框里改 -->
    <GroupAnnounce
      v-if="kind === 'group' && groupInfo?.announcement"
      :announcement="groupInfo.announcement"
      @open="announceOpen = true"
    />

    <!-- 权限提示(AC-04/05/06)。三种各有具体文案,不弹 toast -->
    <div v-if="blockReason" class="block" :class="`block--${blockTone}`">
      <!-- block.to = 出口(未认证→去认证):不让用户看完提示自己找路 -->
      <p class="block__text">{{ blockReason }}<button v-if="block?.to" type="button" class="block__go" @click="router.push(block.to)">去认证</button></p>
    </div>

    <!-- 行 3:消息列表。role=log + aria-live=polite,新消息会被读屏播报 -->
    <div ref="listEl" class="th__list" role="log" aria-live="polite" aria-label="消息列表">
      <!-- 单聊规则行:消息流最前,即第一条消息之前(文案与数字见 ChatThreadRule) -->
      <ChatThreadRule
        v-if="!historyLoading && !historyError && kind !== 'group' && !hideStrangerRule"
        :limit="perPeerLimit"
        :remaining="perPeerRemaining"
      />

      <!-- 历史加载失败:中部一行 + 重试,不整页白屏 -->
      <div v-if="historyError" class="hist-err">
        <p class="hist-err__text">消息没加载出来</p>
        <button type="button" class="hist-err__btn" @click="$emit('reload')">重新加载</button>
      </div>

      <!-- 骨架:左右交替气泡,禁全屏 spinner -->
      <div v-else-if="historyLoading" class="skel" aria-hidden="true">
        <span v-for="(w, i) in skelWidths" :key="i" class="skel__bubble" :class="{ 'is-me': i % 2 === 1 }" :style="{ width: w }" />
      </div>

      <p v-else-if="!messages.length" class="th__blank">
        还没有消息,说点什么打个招呼
      </p>

      <!-- 气泡渲染在 ChatBubble.vue(展开全文与长按计时器都是它自己的状态) -->
      <div v-else class="bubbles">
        <template v-for="b in blocks" :key="b.key">
          <p v-if="b.t === 'day'" class="tl__day">{{ b.text }}</p>
          <p v-else-if="b.t === 'sys'" class="tl__sys">{{ b.text }}</p>
          <div v-else class="blk" :class="b.fromMe ? 'blk--me' : 'blk--other'">
            <div class="blk__head">
              <template v-if="!b.fromMe && b.name">
                <span class="blk__av" aria-hidden="true">{{ b.initial }}</span>
                <span class="blk__name">{{ b.name }}</span>
              </template>
              <span class="blk__time">{{ b.time }}</span>
            </div>
            <div class="blk__stack" :class="{ 'blk__stack--in': !b.fromMe && b.name }">
              <ChatBubble
                v-for="(m, i) in b.msgs"
                :key="m.id"
                :msg="m"
                :first="i === 0"
                :last="i === b.msgs.length - 1"
                @long-press="onLongPress"
                @context-menu="onContextMenu"
                @retry="$emit('retry-send', $event)"
              />
            </div>
            <p v-if="b.read" class="blk__read">已读</p>
          </div>
        </template>
      </div>
    </div>

    <!-- 行 4:引用条 -->
    <div v-if="replyTo" class="reply">
      <span class="reply__label">回复 {{ replyTo.fromMe ? '我' : replyTo.senderNickname || '对方' }}:</span>
      <span class="reply__text">{{ replyTo.content || '(已撤回)' }}</span>
      <button type="button" class="reply__cancel" aria-label="取消引用" @click="$emit('cancel-reply')">
        <el-icon :size="16"><Close /></el-icon>
      </button>
    </div>

    <!--
      行 5:输入区(§1.4)
      transform: translateY(calc(-1 * var(--kb-h, 0px))) —— 动 transform 不动 height/top。
      键盘高度由 useKeyboardViewport 写入 :root 的 --kb-h。
    -->
    <div class="input" :class="{ 'input--disabled': !!blockReason }">
      <el-input
        v-model="draft"
        class="input__box"
        type="textarea"
        :rows="1"
        :autosize="{ minRows: 1, maxRows: 4 }"
        :disabled="!!blockReason"
        aria-label="消息输入框"
        :placeholder="inputPlaceholder"
        @keydown.enter.exact.prevent="send"
      />
      <button
        type="button"
        class="input__send"
        :disabled="!draft.trim() || !!blockReason || sending"
        aria-label="发送"
        @click="send"
      >
        <el-icon :size="20"><Promotion /></el-icon>
      </button>
    </div>

    <!--
      群公告对话框:两个入口共用 —— 顶部那条单行条、以及 ⋯ 菜单里的「群公告」行。
      对话框只能有一个,否则两处会各自维护「查看 ↔ 编辑」状态并逐渐漂移。
    -->
    <GroupAnnounceDialog
      v-if="kind === 'group'"
      v-model="announceOpen"
      :announcement="groupInfo?.announcement || ''"
      :is-owner="isGroupOwner"
      :on-set-announcement="onSetAnnouncement"
    />

    <!-- 长按气泡后的操作表(2026-10-15 拆去 ChatMsgSheet.vue) -->
    <ChatMsgSheet
      v-model="sheetOpen"
      :title="sheetTitle"
      :can-recall="sheetCanRecall"
      :can-quote="sheetCanQuote"
      :can-report="sheetCanReport"
      @recall="emit('recall', sheetTarget)"
      @quote="emit('quote', sheetTarget)"
      @report="openReport(sheetTarget)"
    />

    <!-- 桌面端右键菜单(teleport 到 body,仅 hover:hover 设备挂载) -->
    <MessageContextMenu
      v-model="ctxOpen"
      :pos="ctxPos"
      :can-recall="ctxTarget ? canRecall(ctxTarget) : false"
      :can-copy="!!ctxTarget?.content"
      :can-quote="ctxTarget ? !ctxTarget.recalled && !ctxTarget.failed : false"
      :can-report="!!ctxTarget && !ctxTarget.fromMe && !ctxTarget.recalled"
      @copy="onCtxCopy"
      @quote="ctx.quote"
      @recall="ctx.recall"
      @report="openReport(ctxTarget)"
    />

    <!-- 举报消息：私聊 4 / 群聊 8；group_message 与 chat_message 是两张表，id 不能混用 -->
    <ChatMsgReport
      v-model="reportOpen"
      :message-id="reportTargetId"
      :target-type="kind === 'group' ? 8 : 4"
    />
  </div>
</template>

<script setup>
/**
 * 消息窗(Chat.vue.md §1.2)。操作入口是长按气泡弹 ActionSheet(§1.3),
 * 不是常驻按钮:两个 44px 热区会把正文压到 200px 以下。
 */
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Promotion, Close } from '@element-plus/icons-vue'
import { ElMessage } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import { useIsMobile } from '@/composables/useBreakpoint'
import { useChatBlock } from '@/composables/useChatBlock'
import { useMessageBlocks } from '@/composables/useMessageBlocks'
import { useMessageContextMenu } from '@/composables/useMessageContextMenu'
import { useMsgReport } from '@/composables/useMsgReport'
import GroupMenu from './GroupMenu.vue'
import GroupAnnounce from './GroupAnnounce.vue'
import GroupAnnounceDialog from './GroupAnnounceDialog.vue'
import ChatThreadHeader from './ChatThreadHeader.vue'
import ChatThreadRule from './ChatThreadRule.vue'
import ChatBubble from './ChatBubble.vue'
import ChatMsgSheet from './ChatMsgSheet.vue'
import ChatMsgReport from './ChatMsgReport.vue'
import MessageContextMenu from './MessageContextMenu.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  draft: { type: String, default: '' },
  replyTo: { type: Object, default: null },
  kind: { type: String, default: 'dm' },
  peerName: { type: String, default: '' },
  sourceTitle: { type: String, default: '' },
  historyLoading: { type: Boolean, default: false },
  historyError: { type: Boolean, default: false },
  /** 阻断原因:{ text, tone: 'danger'|'warning' } */
  block: { type: Object, default: null },
  /** 陌生人每日同人条数上限(来自 /chat/stranger-quota;null=没拉到,不显示) */
  perPeerLimit: { type: Number, default: null },
  /**
   * 对**当前这个人**今天还剩几条(来自 /chat/stranger-quota?peerId=)。
   * null = 没拉到 / 对方已不是陌生人 —— 都不显示剩余数,
   * 不能拿 0 顶替:0 会被读成「今天发不出去了」,与「不知道」是两件事。
   */
  perPeerRemaining: { type: Number, default: null },
  /** 互相关注（2026-10-28）：关系已建立，不再提示陌生人条数限制 */
  hideStrangerRule: { type: Boolean, default: false },
  sending: { type: Boolean, default: false },
  canRecall: { type: Function, default: () => false },
  // 群聊相关(GroupHead 用)
  /** 群信息头(GroupMemberInfoVO);null=单聊或加载中 */
  groupInfo: { type: Object, default: null },
  onAdd: { type: Function, default: () => {} },
  onKick: { type: Function, default: () => {} },
  onLeaveGroup: { type: Function, default: () => {} },
  onTransfer: { type: Function, default: () => {} },
  onRename: { type: Function, default: () => {} },
  onSetAnnouncement: { type: Function, default: () => {} },
  /** 禁言某成员:透传 useChatCore.onMuteMember(userId, muted) */
  onMuteMember: { type: Function, default: () => {} },
  /** 禁言全员:透传 useChatCore.onMuteAll(muted) */
  onMuteAll: { type: Function, default: () => {} },
})

const emit = defineEmits([
  'update:draft',
  'send',
  'recall',
  'quote',
  'cancel-reply',
  'retry-send',
  'reload',
  'close-source',
])

const listEl = ref(null)
const sheetOpen = ref(false)
/** 群公告对话框:顶部公告条与 ⋯ 菜单里的「群公告」都打开这一个 */
const announceOpen = ref(false)
/** 长按操作表当前针对的那条消息 */
const sheetTarget = ref(null)

const skelWidths = ['40%', '60%', '52%', '44%', '58%']

/** v-model:draft 由父组件持有(切换会话时不该由子组件清) */
const draft = computed({
  get: () => props.draft,
  set: (v) => emit('update:draft', v),
})

const { blockReason, blockTone } = useChatBlock(props)

const router = useRouter()
const blockTo = computed(() => props.block?.to || '')

const isMobile = useIsMobile()

/** 顶栏次要信息:群聊显示人数;单聊留空(标题本身就是人名) */
const groupSub = computed(() => {
  const n = props.groupInfo?.memberCount ?? props.groupInfo?.members?.length ?? 0
  return n ? `${n} 人` : ''
})

/** 公告对话框里的「编辑」只对群主出现 —— 判定与 GroupMenu 保持一致 */
const isGroupOwner = computed(
  () => !!props.groupInfo?.members?.find((m) => m.isSelf)?.isOwner,
)

/**
 * 输入框占位符。
 * - 桌面:群聊带群名(回应「发消息栏不知道在哪个群」),单聊给快捷键提示
 * - 手机:没有 Shift 键,「按 Enter 发送,Shift+Enter 换行」在触屏上根本执行不了,
 *   还会被输入框截成半句(实测 320px 下只显示到「Shift+Enter 」)。
 *   手机上只留「发给谁 / 在哪说」,一句话说完。
 */
const inputPlaceholder = computed(() => {
  if (isMobile.value) {
    return '发消息'
  }
  if (props.kind === 'group') return '按 Enter 发送'
  return '按 Enter 发送,Shift+Enter 换行'
})

/* ---------------- 消息分组(2026-10-20 群聊改版) ---------------- */

/** 分组规则见 composables/useMessageBlocks.js —— 只做渲染折叠,不碰数据 */
const blocks = useMessageBlocks(props)

const sheetTitle = computed(() =>
  props.kind === 'group' ? '这条群消息' : `发给 ${props.peerName || 'TA'} 的消息`,
)
// 长按操作表尚未针对某条消息时 sheetTarget 是 null:
// 不判空会直接 canRecall(null) → null.id 抛 TypeError,
// 渲染中断、消息窗白屏(表现为「选中会话后聊天区不渲染,再点一次才好」)。
const sheetCanRecall = computed(() => {
  if (!sheetTarget.value) return false
  return props.canRecall(sheetTarget.value)
})
const sheetCanQuote = computed(
  () => !!sheetTarget.value && !sheetTarget.value.fromMe && !sheetTarget.value.recalled,
)

/* 举报消息：判定口径与 sheetCanQuote 一致（对方发的 + 未撤回），逻辑在 useMsgReport */
const { reportOpen, reportTargetId, canReportOf, openReport } = useMsgReport()
const sheetCanReport = computed(() => canReportOf(sheetTarget.value))

function send() {
  if (draft.value.trim()) emit('send')
}

/** 键盘弹起时把最新一条滚进可视区。不加平滑滚动(§4):连发时会互相打断 */
function scrollBottom() {
  const el = listEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

// 进会话/切会话滚动到底:手机端每个会话独立路由、组件会重挂载,必须 immediate;
// flush:'post' + rAF 等布局稳定后 scrollHeight 才准。
watch(
  () => props.messages,
  (m) => {
    if (m?.length) scheduleScrollBottom()
  },
  { flush: 'post', immediate: true },
)

/** 历史加载完成(loading true→false)时滚底:这是切会话/进群最稳的时机 */
watch(
  () => props.historyLoading,
  (loading, prev) => {
    if (prev && !loading && !props.historyError && props.messages?.length) {
      scheduleScrollBottom()
    }
  },
)

/** 下一帧再滚:让新气泡渲染 + 布局完成,scrollHeight 才是完整值 */
function scheduleScrollBottom() {
  requestAnimationFrame(scrollBottom)
}

/* ② 群信息头恒显:早前的下滑收起找不回来、还会盖住群管理面板,已删除。 */

/* ---------------- 长按操作表 ---------------- */

/** 长按的计时与 pointer 事件在 ChatBubble 内部,这里只接收「长按发生了」并记住目标 */
function onLongPress(m) {
  sheetTarget.value = m
  sheetOpen.value = true
}

/* ---------------- 桌面右键菜单(2026-10-15 新增) ---------------- */

/* 开合/位置/外部点击收起都在 composable 里;开菜单时顺手关掉长按表,两者互斥 */
const ctx = useMessageContextMenu({
  onOpen: () => {
    sheetOpen.value = false
  },
  onQuote: (m) => emit('quote', m),
  onRecall: (m) => emit('recall', m),
})
const ctxOpen = ctx.open
const ctxTarget = ctx.target
const ctxPos = ctx.pos
const onContextMenu = ctx.show

/** 复制:走 utils/clipboard 的统一入口,http 源下退化到 execCommand */
async function onCtxCopy() {
  const m = ctxTarget.value
  if (!m) return
  const ok = await copyText(m.content || '')
  if (ok) ElMessage.success('已复制')
  else ElMessage.warning('这个浏览器不让复制,长按消息试试')
}

/* 只暴露 scrollBottom(键盘弹起时滚到最新);长按相关状态不暴露,避免父子两处改同一状态 */
defineExpose({ scrollBottom })
</script>
<style scoped src="./ChatThread.css"></style>
