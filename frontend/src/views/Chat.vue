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
    <!--
      搭话规则说明(2026-10-25 改版):原先是这里的一条常驻横幅(带「知道了」),
      被用户评「难看」——横幅为一句静态规则占用整行视觉权重不划算。
      现收进气泡:ⓘ 挂在会话列表搜索框右缘(ChatConvList.vue .cl__rulebtn),
      悬停/聚焦即看,零打扰。额度数字与 campus.daily-new-peer-limit /
      per-peer-daily-limit 对应,改配置时同步那边。
    -->
    <!--
      ============ 会话列表 ============
      手机:仅 /chat 列表路由渲染(单栏路由切换);
      桌面:**双栏的左栏,必须渲染** —— 早前 v-if 只写了 isListRoute(手机条件),
      桌面双栏布局在、CSS 在,列表组件却永远不挂载,左栏一片空白,
      用户只能靠去别人主页点「发私信」才能进聊天(Chat.vue.md §1 桌面双栏设计被架空)。
    -->
    <ChatConvList
      v-if="isListRoute || isDesktop"
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
      @peer="goPeer"
      @create-group="createGroupOpen = true"
      @browse="router.push('/circles')"
      @pin="pinRow"
      @mute="muteRow"
      @delete="deleteRow"
    />

    <!-- ============ 会话窗:手机是独立路由页,桌面是右栏 ============ -->
    <div v-if="isThreadRoute || isDesktop" class="chat__main">
      <template v-if="hasActive">
        <ChatThread
          ref="threadRef"
          v-model:draft="draft"
          class="chat__thread"
          :key="currentConvId ?? currentGroup?.id ?? 'blank'"
          :messages="messages"
          :reply-to="replyToMsg"
          :kind="currentKind"
          :peer-name="headTitle"
          :source-title="sourceTitle"
          :history-loading="historyLoading"
          :history-error="historyError"
          :block="block"
          :sending="sending"
          :can-recall="canRecall"
          :group-info="groupInfo"
          :on-add="(ids) => onAddMembers(ids)"
          :on-kick="onKick"
          :on-leave-group="onLeaveGroup"
          :on-transfer="onTransfer"
          :on-rename="onRename"
          :on-set-announcement="onSetAnnouncement"
          :on-mute-member="onMuteMember"
          :on-mute-all="onMuteAll"
          @send="onSend"
          @recall="doRecall"
          @quote="replyToMsg = $event"
          @cancel-reply="replyToMsg = null"
          @retry-send="retrySend"
          @reload="reload"
          @close-source="sourceTitle = ''"
        />
      </template>

      <!-- 桌面 /chat 无选中会话:空态。手机上永远有选中项(路由保证) -->
      <div v-else class="chat__blank">
        <p class="chat__blank-title">从左边挑一个会话,或者直接找个人搭句话</p>
        <button type="button" class="chat__blank-btn" @click="router.push('/search')">找人</button>
      </div>
    </div>

    <!-- 建群操作表:从会话列表「＋」进入,创建后拉群并打开 -->
    <CreateGroupSheet v-model="createGroupOpen" @created="onGroupCreated" />
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
import { ref, computed, watch, nextTick, inject, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useIsMobile } from '@/composables/useBreakpoint'
import { useVerifyStatus, VERIFY_OK } from '@/composables/useVerifyStatus'
import { useKeyboardViewport } from '@/composables/useKeyboardViewport'
import { useChatCore } from '@/composables/useChatCore'
import { useChatComposer } from '@/composables/useChatComposer'
import ChatConvList from '@/components/chat/ChatConvList.vue'
import ChatThread from '@/components/chat/ChatThread.vue'
import CreateGroupSheet from '@/components/chat/CreateGroupSheet.vue'

const route = useRoute()
const router = useRouter()
const isMobile = useIsMobile()

/** 路由跳转引发的临时列表加载(打开会话前等列表就绪的那段时间) */
const routeLoading = ref(false)
const sourceTitle = ref('')
/** 建群操作表开关(ChatConvList 的「＋」触发) */
const createGroupOpen = ref(false)

/** 键盘弹起时把最新一条滚进可视区。iOS 上 window.resize 不触发,见 useKeyboardViewport */
const { kbHeight } = useKeyboardViewport((kb) => {
  if (kb > 0) nextTick(() => threadRef.value?.scrollBottom())
})

const core = useChatCore({
  onNeedScrollBottom: () => nextTick(() => threadRef.value?.scrollBottom()),
})

/*
 * 清零消息角标（2026-10-21）。
 *
 * 用户点进「消息」，角标就该消失 —— 他的原话是「点进去看了，
 * 左边的数字提醒就多余了」。角标语义是「有你看不到的新东西」，
 * 人已经在会话列表里，它对这个人已经没有信息价值。
 * 未读消息在会话项上仍各有自己的圆点，信息不会丢。
 *
 * inject 可能拿不到（组件被单独挂载时），给空函数兜底。
 */
const clearChatBadge = inject('clearChatBadge', () => {})

onMounted(() => {
  /*
   * 进页面立刻清零。
   * 放在 onMounted 而不是 setup 顶层：setup 阶段布局可能还没挂载完，
   * 此时 provide 值虽已注入，但语义上「已经进入页面」应以挂载为准。
   */
  clearChatBadge()
})

/*
 * 模板里的 ref 解包只在**顶层绑定**时生效;`core.xxx` 是对象属性,
 * Vue 不会解包。所以这里逐个解出顶层 const,模板直接用名字 ——
 * 写 `core.convs.value` 在模板里能跑但极易漏掉 .value(漏了就永远渲染空)。
 */
const {
  convs, groups, strangerList, quotaLeft,
  messages, replyToMsg, currentKind, currentConvId,
  groupInfo, mutedMe, currentGroup,
  historyLoading, historyError, headTitle, hasActive, canRecall,
  openConv: coreOpenConv, openGroup: coreOpenGroup, doRecall,
  onAddMembers, onKick, onLeaveGroup, onTransfer, onRename, onSetAnnouncement,
  onMuteMember, onMuteAll, loadMutedMe,
  pinRow, muteRow, deleteRow,
} = core

/* 输入 / 发送 / 重试:2026-10-15 拆去 composables/useChatComposer.js
   (draft/sending/threadRef/onSend/retrySend 都从那里来) */
const { draft, sending, threadRef, onSend, retrySend } = useChatComposer(core)

/**
 * 列表骨架 = 首屏加载(core 内部三并发,结束后自己关) || 路由跳转加载。
 * 此前 listLoading 是个孤立 ref(true),只有带参路由的 watch 会关它——
 * 普通 /chat 没人关,列表永远卡骨架屏。桌面端此前列表组件根本不渲染,
 * 这个洞被上一个 bug 盖住了,修好挂载问题后它才露出来。
 */
const listLoading = computed(() => core.initialLoading.value || routeLoading.value)

const isDesktop = computed(() => !isMobile.value)
const isListRoute = computed(() => isMobile.value && route.name === 'chat')
const isThreadRoute = computed(() => route.name === 'chatThread')

/**
 * 阻断原因(AC-04)。
 * **只在 quota 明确为 0 时阻断** —— 拉取失败时 quotaLeft 是 null,
 * 此时不阻断。误阻断(明明能发却发不出去)比漏阻断(发出去才被拒)伤害大得多。
 * 对方关闭陌生人消息(AC-05)需要后端在会话详情里下发该字段,
 * 当前 openapi 的 ConversationVO 无此字段,故本轮不做前端判定(见交付 advisory)。
 * 群禁言:群聊 + 我被群主禁言(mutedMe)→ 阻断输入,后端 GROUP_MUTED(1046) 兜底。
 */
const { code: verifyCode } = useVerifyStatus()

const block = computed(() => {
  // 群禁言优先显示(更具体):群聊且我被禁言
  if (currentKind.value === 'group' && mutedMe.value) {
    return { text: '你已被群主禁言,无法发言', tone: 'danger' }
  }
  /*
   * 未认证阻断(2026-10-24 补):私聊发消息后端必拒(ChatGuard AC-11,403/1029),
   * 但此前前端不拦也不提示——实测未认证用户能打字、点发送后**静默无反应**
   * (无 toast、无跳转),像"点了没反应"。认证状态是确定值(非 null 语义),
   * 符合本函数"确定才阻断"的原则。to 字段给横幅一个"去认证"出口。
   */
  if (currentKind.value !== 'group' && verifyCode.value !== VERIFY_OK) {
    return { text: '完成校内认证后才能发私信', tone: 'danger', to: '/onboarding' }
  }
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
    routeLoading.value = true
    const deadline = Date.now() + 1200
    while (core.initialLoading.value && !convs.value.length && !groups.value.length && Date.now() < deadline) {
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
    routeLoading.value = false
  },
  { immediate: true },
)

/** 他人主页「发私信」入口:?to={uid} */
watch(
  () => route.query.to,
  async (to) => {
    if (!to || route.name === 'chatThread') return
    /*
     * 手机端 /chat 只有列表路由,会话窗模板 `v-if="isThreadRoute || isDesktop"`
     * 不渲染。此前这里只把会话放进内存状态,页面仍停在列表 ——
     * 用户从主页点「发私信」进来只能看到空列表/骨架。
     * 直接换成会话路由,交给上面的 chatThread watcher 走正常打开链路。
     */
    if (isMobile.value) {
      router.replace(`/chat/t/dm/${to}`)
      return
    }
    routeLoading.value = true
    await core.openConvByPeerId(Number(to))
    routeLoading.value = false
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
  // 切会话前清掉旧会话的残留消息与加载态:否则首点击可能沿用上一会话的
  // messages / historyError,表现为「点了没反应、再点才显示」(Bug1)。
  // 设 historyLoading=true 让右侧先出骨架,loadHistory 结束再翻成正文。
  messages.value = []
  historyError.value = false
  historyLoading.value = true
  coreOpenConv(c)
}

function openGroup(g) {
  if (isMobile.value) {
    router.push(`/chat/t/group/${g.id}`)
    return
  }
  coreOpenGroup(g)
}

/** 会话列表里点对方头像/名字:直接进 TA 的主页(peer.id = 对方 uid) */
function goPeer(uid) {
  if (uid) router.push(`/user/${uid}`)
}

/* ---------------- 建群完成:刷新群列表并打开新群 ---------------- */

async function onGroupCreated(groupId) {
  await core.loadGroups()
  const g = groups.value.find((x) => String(x.id) === String(groupId))
  if (g) openGroup(g)
  else ElMessage.error('群已创建，但没在列表里找到它，回列表看看')
}

/* ---------------- 发送 ---------------- */

function reload() {
  core.loadHistory()
}

/** 键盘收起后同样要滚到底:否则最后一条会停在被键盘压住的位置 */
watch(kbHeight, (kb) => {
  if (kb === 0) nextTick(() => threadRef.value?.scrollBottom())
})
</script>

<style scoped src="./Chat.css"></style>
