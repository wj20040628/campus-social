<template>
  <!--
    会话(Chat.vue.md)
    一份组件,两种形态:
      手机(<1024px):/chat 列表页 与 /chat/t/:kind/:id 会话页 **单栏路由切换**
      桌面(≥1024px):**双栏主从**,两页共存于同一路由
    数据与 WS 在 useChatCore.js,列表 UI 在 ChatConvList.vue,消息窗在 ChatThread.vue,
    本文件只做路由分支与编排 —— 原 1072 行的 Chat.vue 拆成了 4 个文件。
  -->
  <div class="chat">
    <!-- ============ 手机:列表页 ============ -->
    <ChatConvList
      v-if="isListRoute"
      class="chat__list"
      :convs="convs"
      :groups="groups"
      :stranger-list="strangerList"
      :quota="quotaLeft"
      :loading="listLoading"
      :active-id="currentConvId"
      :active-kind="currentKind"
      @open="openConv"
      @open-group="openGroup"
      @browse="router.push('/circles')"
    />

    <!-- ============ 会话窗:手机是独立路由页,桌面是右栏 ============ -->
    <div v-if="isThreadRoute || isDesktop" class="chat__main">
      <template v-if="hasActive">
        <ChatThread
          ref="threadRef"
          v-model:draft="draft"
          class="chat__thread"
          :messages="messages"
          :reply-to="replyToMsg"
          :kind="currentKind"
          :peer-name="headTitle"
          :peer-id="peerId"
          :source-title="sourceTitle"
          :history-loading="historyLoading"
          :history-error="historyError"
          :block="block"
          :sending="sending"
          :can-recall="canRecall"
          @send="onSend"
          @recall="doRecall"
          @quote="replyToMsg = $event"
          @cancel-reply="replyToMsg = null"
          @retry-send="retrySend"
          @reload="reload"
          @close-source="sourceTitle = ''"
          @go-peer="router.push(`/user/${$event}`)"
        />
      </template>

      <!-- 桌面 /chat 无选中会话:空态。手机上永远有选中项(路由保证) -->
      <div v-else class="chat__blank">
        <p class="chat__blank-title">从左边挑一个会话,或者直接找个人搭句话</p>
        <button type="button" class="chat__blank-btn" @click="router.push('/search')">找人</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 会话页编排(Chat.vue.md)
 *
 * ## 单栏 vs 双栏的判定
 *
 * 用 `useIsMobile()` 而不是纯 CSS 隐藏:手机上 /chat 与 /chat/t/... 是**两条路由**,
 * 列表与会话是两个页面。如果只靠 CSS,切到会话页时列表 DOM 还在,
 * 会出现「会话页左边还挂着 280px 列表」的双栏残留。
 * 桌面端才把两者放回同一个 DOM。
 *
 * ## 断点来源
 *
 * useBreakpoint.js 从 design-tokens.json import 断点值,不写死 1024。
 */
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useIsMobile } from '@/composables/useBreakpoint'
import { useKeyboardViewport } from '@/composables/useKeyboardViewport'
import { useChatCore } from '@/composables/useChatCore'
import ChatConvList from '@/components/chat/ChatConvList.vue'
import ChatThread from '@/components/chat/ChatThread.vue'

const route = useRoute()
const router = useRouter()
const isMobile = useIsMobile()

const draft = ref('')
const sending = ref(false)
const listLoading = ref(true)
const sourceTitle = ref('')
const threadRef = ref(null)

/** 键盘弹起时把最新一条滚进可视区。iOS 上 window.resize 不触发,见 useKeyboardViewport */
const { kbHeight } = useKeyboardViewport((kb) => {
  if (kb > 0) nextTick(() => threadRef.value?.scrollBottom())
})

const core = useChatCore({
  onNeedScrollBottom: () => nextTick(() => threadRef.value?.scrollBottom()),
})

/*
 * 模板里的 ref 解包只在**顶层绑定**时生效;`core.xxx` 是对象属性,
 * Vue 不会解包。所以这里逐个解出顶层 const,模板直接用名字 ——
 * 写 `core.convs.value` 在模板里能跑但极易漏掉 .value(漏了就永远渲染空)。
 */
const {
  convs, groups, strangerList, quotaLeft,
  messages, replyToMsg, currentKind, currentConvId,
  historyLoading, historyError, headTitle, hasActive, canRecall,
  openConv: coreOpenConv, openGroup: coreOpenGroup, doRecall,
} = core

const isDesktop = computed(() => !isMobile.value)
const isListRoute = computed(() => isMobile.value && route.name === 'chat')
const isThreadRoute = computed(() => route.name === 'chatThread')
const peerId = computed(() => core.currentConv.value?.peer?.id || null)

/**
 * 阻断原因(AC-04)。
 * **只在 quota 明确为 0 时阻断** —— 拉取失败时 quotaLeft 是 null,
 * 此时不阻断。误阻断(明明能发却发不出去)比漏阻断(发出去才被拒)伤害大得多。
 * 对方关闭陌生人消息(AC-05)需要后端在会话详情里下发该字段,
 * 当前 openapi 的 ConversationVO 无此字段,故本轮不做前端判定(见交付 advisory)。
 */
const block = computed(() => {
  const q = quotaLeft.value
  if (q !== null && q <= 0) {
    return { text: '今天打招呼的次数用完了,明天再来', tone: 'danger' }
  }
  return null
})

/* ---------------- 路由 -> 会话 ---------------- */

/**
 * 从 URL 参数定位会话。
 * 直接进 /chat/t/dm/123 时列表可能还没加载完 —— 先等列表,
 * 否则 find 落空会误判成「新会话」,进一个空消息页还以为消息丢了。
 */
watch(
  [() => route.params.kind, () => route.params.id, isMobile],
  async ([kind, id]) => {
    if (!kind || !id) return
    listLoading.value = true
    const deadline = Date.now() + 1200
    while (!convs.value.length && !groups.value.length && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 60))
    }
    if (kind === 'group') {
      const g = groups.value.find((x) => String(x.id) === String(id))
      if (g) await coreOpenGroup(g)
      else {
        ElMessage.error('群会话没找到,回列表看看')
        router.replace('/chat')
        return
      }
    } else {
      const ok = await core.openConvByPeerId(Number(id))
      if (!ok) router.replace('/chat')
    }
    listLoading.value = false
  },
  { immediate: true },
)

/** 他人主页「发私信」入口:?to={uid} */
watch(
  () => route.query.to,
  async (to) => {
    if (!to || route.name === 'chatThread') return
    listLoading.value = true
    await core.openConvByPeerId(Number(to))
    listLoading.value = false
  },
  { immediate: true },
)

/* ---------------- 列表点击 -> 推会话路由 ---------------- */

/** 单聊路由用 peerId 而非 convId:convId 是本地的,换个入口(主页直接进)就对不上 */
function openConv(c) {
  if (isMobile.value) {
    if (c.peer?.id) router.push(`/chat/t/dm/${c.peer.id}`)
    return
  }
  coreOpenConv(c)
}

function openGroup(g) {
  if (isMobile.value) {
    router.push(`/chat/t/group/${g.id}`)
    return
  }
  coreOpenGroup(g)
}

/* ---------------- 发送 ---------------- */

async function onSend() {
  if (sending.value || !draft.value.trim()) return
  sending.value = true
  const text = draft.value
  try {
    const res = await core.doSend(draft)
    if (res.ok) {
      draft.value = ''
      return
    }
    /*
     * 失败:**草稿保留**(§3 错误态)。清空输入框是最伤的交互。
     * 同时追加一条 failed 气泡,让用户看到「到底发出去了没」的答案 ——
     * 界面毫无变化会让人以为按钮坏了而狂点。
     */
    messages.value.push({
      id: `local-${Date.now()}`,
      content: text,
      fromMe: true,
      createTime: nowText(),
      sendTs: Date.now(),
      status: 0,
      failed: true,
    })
    await nextTick()
    threadRef.value?.scrollBottom()
    ElMessage.error('没发出去,点气泡重试')
  } finally {
    sending.value = false
  }
}

async function retrySend(m) {
  const text = m.content
  // 先撤掉失败气泡:重试成功时页面不能留下两条一样的消息
  messages.value = messages.value.filter((x) => x.id !== m.id)
  draft.value = text
  const res = await core.doSend(draft)
  if (res.ok) {
    draft.value = ''
  } else {
    draft.value = text
    ElMessage.error('还是没发出去,检查一下网络')
  }
}

function nowText() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function reload() {
  core.loadHistory()
}

/** 键盘收起后同样要滚到底:否则最后一条会停在被键盘压住的位置 */
watch(kbHeight, (kb) => {
  if (kb === 0) nextTick(() => threadRef.value?.scrollBottom())
})
</script>

<style scoped src="./Chat.css"></style>
