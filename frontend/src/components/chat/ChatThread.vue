<template>
  <!--
    消息窗(Chat.vue.md §1.2 ~ §1.4)
    唯一负责「键盘弹起时输入框不被吞掉」的地方。
  -->
  <div class="th">
    <!-- 行 0 + 行 2:自绘顶栏与来源上下文条(2026-10-15 拆去 ChatThreadHeader.vue) -->
    <ChatThreadHeader
      :peer-name="peerName"
      :source-title="sourceTitle"
      @close-source="$emit('close-source')"
    />

    <!--
      ② 群信息头(仅群聊)。**桌面 + 手机都常显**:群头固定在消息区上方,
      不随滚动收起/隐藏。早前的「下滑收起 / 上滑拉回」已移除 —— 用户反馈
      手机端收起后群头找不回来,桌面端也要求常显,统一为恒显。
    -->
    <div v-if="kind === 'group' && groupInfo" class="gh-wrap">
      <GroupHead
        :group="groupInfo"
        :info="groupInfo"
        :on-add="onAdd"
        :on-kick="onKick"
        :on-leave-group="onLeaveGroup"
        :on-transfer="onTransfer"
        :on-rename="onRename"
        :on-set-announcement="onSetAnnouncement"
        :on-mute-member="onMuteMember"
        :on-mute-all="onMuteAll"
      />
    </div>

    <!-- 权限提示(AC-04/05/06)。三种各有具体文案,不弹 toast -->
    <p v-if="blockReason" class="block" :class="`block--${blockTone}`">{{ blockReason }}</p>

    <!-- 行 3:消息列表。role=log + aria-live=polite,新消息会被读屏播报 -->
    <div ref="listEl" class="th__list" role="log" aria-live="polite" aria-label="消息列表">
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
      <ul v-else class="bubbles">
        <ChatBubble
          v-for="m in messages"
          :key="m.id"
          :msg="m"
          :show-sender="showSender(m)"
          @long-press="onLongPress"
          @retry="$emit('retry-send', $event)"
        />
      </ul>
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

    <!-- 长按气泡后的操作表(2026-10-15 拆去 ChatMsgSheet.vue) -->
    <ChatMsgSheet
      v-model="sheetOpen"
      :title="sheetTitle"
      :can-recall="sheetCanRecall"
      :can-quote="sheetCanQuote"
      @recall="emit('recall', sheetTarget)"
      @quote="emit('quote', sheetTarget)"
      @go-peer="onGoProfile"
    />
  </div>
</template>

<script setup>
/**
 * 消息窗(Chat.vue.md §1.2)
 *
 * ## 长按而不是常驻按钮
 *
 * 原实现给我方气泡右侧常驻一个撤回字符、给对方气泡常驻一个附件字符
 * (且都是字符图标,命中 P0)。375px 下两个 44px 热区会把气泡正文宽度压到
 * 200px 以下。现在改成**长按气泡**弹 ActionSheet(§1.3)。
 *
 * ## 长按阈值
 *
 * 500ms:短于此会和点击/滚动冲突,长于此用户会以为没响应。
 * 用 pointerdown/pointerup 而不是 touchstart —— 前者在桌面也能触发,
 * 开发时不用为了测长按切到手机。
 */
import { ref, computed, watch } from 'vue'
import { Promotion } from '@element-plus/icons-vue'
import GroupHead from './GroupHead.vue'
import ChatThreadHeader from './ChatThreadHeader.vue'
import ChatBubble from './ChatBubble.vue'
import ChatMsgSheet from './ChatMsgSheet.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  draft: { type: String, default: '' },
  replyTo: { type: Object, default: null },
  kind: { type: String, default: 'dm' },
  peerName: { type: String, default: '' },
  peerId: { type: [Number, String], default: null },
  sourceTitle: { type: String, default: '' },
  historyLoading: { type: Boolean, default: false },
  historyError: { type: Boolean, default: false },
  /** 阻断原因:{ text, tone: 'danger'|'warning' } */
  block: { type: Object, default: null },
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
  'go-peer',
])

const listEl = ref(null)
const sheetOpen = ref(false)
/** 长按操作表当前针对的那条消息 */
const sheetTarget = ref(null)

const skelWidths = ['40%', '60%', '52%', '44%', '58%']

/** v-model:draft 由父组件持有(切换会话时不该由子组件清) */
const draft = computed({
  get: () => props.draft,
  set: (v) => emit('update:draft', v),
})

const blockReason = computed(() => props.block?.text || '')
const blockTone = computed(() => props.block?.tone || 'danger')

/** 输入框占位符:群聊时带群名(回应「发消息栏不知道在哪个群」),单聊保持通用 */
const inputPlaceholder = computed(() => {
  if (props.kind === 'group' && props.groupInfo?.name) {
    return `在「${props.groupInfo.name}」按 Enter 发送`
  }
  return '按 Enter 发送,Shift+Enter 换行'
})

const sheetTitle = computed(() =>
  props.kind === 'group' ? '这条群消息' : `发给 ${props.peerName || 'TA'} 的消息`,
)
const sheetCanRecall = computed(() => props.canRecall(sheetTarget.value))
const sheetCanQuote = computed(
  () => !!sheetTarget.value && !sheetTarget.value.fromMe && !sheetTarget.value.recalled,
)

/** 是否显示发送者昵称(群聊 + 非本人 + 未撤回)。判定留在本层:它取决于会话类型 */
function showSender(m) {
  return props.kind === 'group' && !m.fromMe && !m.recalled
}

function send() {
  if (!draft.value.trim()) return
  emit('send')
}

/** 键盘弹起时把最新一条滚进可视区。不加平滑滚动(§4):连发时会互相打断 */
function scrollBottom() {
  const el = listEl.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

/**
 * ① 进会话/切会话:messages 变化即滚到最新(最下)。
 * 手机端每个会话是独立路由、ChatThread 会**重挂载** —— 必须 immediate,
 * 否则挂载时已有 messages(列表先于会话加载)不会触发,停在上部。
 * flush:'post' 保证 DOM 已更新;再 rAF 等布局稳定(scrollHeight 才准)。
 */
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

/*
 * ② 群信息头:桌面 + 手机都常显。
 * 早前的「下滑收起 / 上滑拉回」(headPinned / onListScroll / scroll 监听)已删除:
 * 手机端收起后群头找不回来、群管理面板又被消息盖住,统一改为恒显,
 * 群头固定在消息区上方,不再随滚动平移。
 */

/* ---------------- 长按操作表 ---------------- */

/**
 * 长按的计时与 pointer 事件在 ChatBubble 内部(它自己的状态),
 * 这里只接收「长按发生了」并记住目标是哪条消息。
 */
function onLongPress(m) {
  sheetTarget.value = m
  sheetOpen.value = true
}

function onGoProfile() {
  sheetOpen.value = false
  if (props.peerId) emit('go-peer', props.peerId)
}

/*
 * 暴露给父组件的只有 scrollBottom —— 键盘弹起时把最新一条滚进可视区。
 * 不暴露 onPointerDown/clearPress:它们已经在模板里绑好了,
 * defineExpose 一旦多暴露就变成父子两处都在改同一状态,容易双触发。
 */
defineExpose({ scrollBottom })
</script>

<style scoped src="./ChatThread.css"></style>
