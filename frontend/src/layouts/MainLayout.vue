<template>
  <div class="layout">
    <!--
      Skip link:仅键盘用户可达。TabBar + 侧栏在前面,
      键盘用户每个页面都要先穿过 5-6 个导航项才能到内容,这条是补救。
    -->
    <a class="skip-link" href="#main-content">跳到主内容</a>

    <div class="shell">
      <!-- 桌面侧栏;手机端 display:none,不进入可聚焦树 -->
      <SideNav
        class="layout__side"
        :route-path="route.path"
        :notify-unread="notifyUnread"
        :chat-unread="paperCount"
      />

      <div class="col">
        <!--
          ownTop:页面自带顶栏时不渲染这里的顶栏。
          用 v-if 而非 CSS 隐藏 —— 引导页(/onboarding)与公告编辑器
          的顶栏形态是「取消 / 进度 / 发布」,和全站顶栏的
          「品牌 / 搜索 / 铃铛」不是一回事,叠在一起会挤成两行 56px。
          (CSS 隐藏的 display:none 虽然不进 Tab 序,但 DOM 里留一份
          无用的 44x44 焦点位,键盘用户会 Tab 到看不见的按钮上。)
        -->
        <TopBar
          v-if="!ownTop"
          :show-back="showBack"
          :page-title="pageTitle"
          :head-title="headTitle"
          :notify-unread="notifyUnread"
          @menu="drawerOpen = true"
        />

        <!--
          2026-10-27:全站认证横幅移除。
          未认证是常见起点,但不该在每个页面顶部常驻一条提示;
          改成在真正受限的动作处弹「认证闸门」(见 AuthGateSheet),
          由 request.js 的 1029 与页面主动拦截共同触发。
        -->

        <main
          id="main-content"
          class="content"
          :class="{
            'content--no-tabbar': hideTabBar,
            'content--immersive': immersive,
            'content--own-top': ownTop,
          }"
          tabindex="-1"
        >
          <router-view v-slot="{ Component }">
            <!--
              **不要加 mode="out-in"**(实测:同一 URL `/user/undefined`,
              整页加载 kids=9/text=27 正常,SPA 导航 kids=0/text=0 永久空白,
              再点侧栏也恢复不了,只有 F5 能重置)。
              机制:out-in 要等旧组件 leave 完才挂新的,靠 transitionend 推进;
              组件在 leave 阶段抛错时收不到该事件,新组件永不挂载。
              去掉后组件抛错不再阻断挂载 —— 用户看到可用的错误页,而不是整片空白。

              KeepAlive 只缓存 Home(必须显式 name,见 Home.vue 头注释):
              从帖子返回时 feed 不重新挂载,配合 router scrollBehavior 的
              savedPosition 实现「退出帖子回到刚才的位置」。内容变更(发帖/
              编辑/删帖)后由 useFeedRefresh 的脏标记触发重拉,不会看到旧数据。
            -->
            <!--
              :key 路由切换钥匙(Bug3 根治 + 聊天双栏保活)。
              默认用 route.path 当 key:同一路由内滚动恢复交给 KeepAlive + savedPosition,
              跨路由(path 变)强制新实例、干净换页,不再「URL 变了内容没变、须刷新」。
              **唯一例外 chatThread**:桌面双栏下 /chat 与 /chat/t/:kind/:id 是同一个
              Chat 组件的两栏,打开/切换会话只换右栏、不该整页重挂 —— 否则历史/草稿/
              WS 全被销毁重建,表现为「点会话卡一下、要再点」。故 chatThread 钉死成
              常量 'chat',与 /chat 共享同一键;而 chat -> mkt 这种跨视图跳转 path 仍
              会变,照样正常换页。
              注意:注释不能放进 <KeepAlive> 里 —— 编译器把注释也算子节点,
              会报「KeepAlive expects exactly one child component」直接白屏。
            -->
            <Transition name="ys-fade">
              <KeepAlive include="Home">
                <component
                  :is="Component"
                  :key="route.name === 'chatThread' ? 'chat' : route.path"
                />
              </KeepAlive>
            </Transition>
          </router-view>
        </main>
      </div>
    </div>

    <!--
      TabBar 用 v-if 而非 CSS display 切换:
      CSS 隐藏的元素仍然能被 Tab 键走到(display:none 不会,但 v-if 连 DOM 都不存在,
      两套外壳彻底隔离,不会各自维护一份焦点顺序)。
    -->
    <TabBar v-if="!hideTabBar" :paper-count="paperCount" />

    <!--
      全局导航抽屉(2026-10-18):手机端顶栏汉堡 / 桌面端头像 都唤起。
      挂在布局层而不是 TopBar 内 —— 它要盖住 TabBar(fixed),且未读数
      (notifyUnread)在这里,与顶栏铃铛同源不重复请求;退出登录走 logout()。
    -->
    <AppDrawer v-model="drawerOpen" :notify-unread="notifyUnread" @logout="logout" />

    <!-- 全站认证闸门:1029 或页面主动拦截时打开 -->
    <AuthGateSheet />
  </div>
</template>

<script setup>
/**
 * 全站外壳(MainLayout.vue.md)
 *
 * 一套组件两副骨架:
 *   手机(<1024px):顶栏 56px + 底部 TabBar 56px + 内容区
 *   桌面(≥1024px):左侧 220px 侧栏(el-menu 保留)+ 顶栏 + 内容区
 *
 * 所有页面都继承它,改错一处全站受影响 —— 所以逻辑尽量少,
 * 复杂的部分拆成 SideNav.vue 与 TopBar.vue 两个子组件。
 *
 * 断点纪律:本文件只用字面量 `@media (min-width: 1024px)`,
 * 值取自 design-tokens.json 的 breakpoint.lg。
 * 本项目没有 postcss-custom-media,`@media (--ys-bp-lg)` 会被浏览器
 * 静默丢弃(SPEC §11 的「沉默故障」之一)。
 */
import { ref, computed, watch, onMounted, onBeforeUnmount, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import SideNav from './SideNav.vue'
import TopBar from './TopBar.vue'
import TabBar from '@/components/mobile/TabBar.vue'
import AppDrawer from '@/components/mobile/AppDrawer.vue'
import AuthGateSheet from '@/components/auth/AuthGateSheet.vue'
import { useUserStore } from '@/stores/user'
import { getWsClient } from '@/utils/ws'
import * as notifyApi from '@/api/notify'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const ws = getWsClient()

/** 顶栏形态与 TabBar 显隐全部由路由 meta 驱动,不在组件内判断路径 */
const showBack = computed(() => route.meta.back === true)
const hideTabBar = computed(() => route.meta.hideTabBar === true)
const pageTitle = computed(() => route.meta.title || '友声')
/** 非返回态左上角文字(C20):页面名覆盖品牌,空串 = 用品牌「友声」 */
const headTitle = computed(() => route.meta.headTitle || '')
/** 页面自带顶栏(引导页 / 全屏编辑器)时不渲染全站顶栏 */
const ownTop = computed(() => route.meta.ownTop === true)

/**
 * 会话页(/chat/t/...)是沉浸式:输入区自己撑底,
 * 内容区不能再留 TabBar 高度 + 安全区,否则消息区会多出一段空白。
 */
const immersive = computed(() => route.name === 'chatThread')

const notifyUnread = ref(0)
const paperCount = ref(0)
/** 导航抽屉开关:TopBar 的汉堡按钮唤起(仅手机端渲染汉堡) */
const drawerOpen = ref(false)

/*
  把「打开抽屉」这个能力向下开放给页面。

  为什么需要:抽屉本体(AppDrawer)和 drawerOpen 都住在本组件里,
  子页面拿不到 ref。个人主页用了 meta.ownTop 自绘顶栏,TopBar 不再渲染,
  页面自己的「更多」按钮如果没有任何通道,点了抽屉不会动。

  为什么不用「页面内再挂一个 AppDrawer」:那样会同时存在两个抽屉实例,
  状态各管各的,未读数会重复拉取,还可能出现一个开着另一个不知道的情况。
  provide/inject 是 Vue 官方的跨层传能力方式,一处注入,后续其它自绘顶栏
  的页面(发布页等)也能直接 inject 复用。
*/
provide('openDrawer', () => { drawerOpen.value = true })

/*
 * 角标清零能力（2026-10-21 加）。
 *
 * 问题：用户点「通知」进页面、把通知都看了，左侧/顶栏的数字却还在 ——
 * 要刷新整页才消失。因为角标状态住在 MainLayout，而「标记已读」发生在
 * Notify.vue / Chat.vue 内部，两边没有通道。
 *
 * 这里把清零函数 provide 下去：
 *   clearNotifyBadge() —— 通知页标记已读后调用
 *   clearChatBadge()   —— 消息页读完会话后调用
 *
 * 为什么是「清零」而不是「重拉」：用户已经进页面了，角标的语义就是
 * 「有新东西你看不到」。他正在看，所以立刻归零是符合直觉的即时反馈；
 * 之后仍会由路由监听 / WS 推送在有新增时重新拉准值。
 */
provide('clearNotifyBadge', () => {
  notifyUnread.value = 0
})

provide('clearChatBadge', () => {
  paperCount.value = 0
})

/**
 * 未读通知数（顶栏铃铛 / 侧栏「通知」/ 抽屉）。
 *
 * 【2026-10-21 修 bug】改版前读的是 `data?.unread`，而后端 /notify/unread
 * 实际返回的是 `{ count: 0 }` —— 字段名对不上，`data.unread` 恒为 undefined，
 * 于是 `?? 0` 永远给出 0，红点根本不会亮。
 * 现在两者都兼容（count 优先），字段再改名也不会静默失效。
 */
async function loadUnread() {
  try {
    const data = await notifyApi.unread()
    notifyUnread.value = data?.count ?? data?.unread ?? 0
  } catch {
    notifyUnread.value = 0
  }
}

/** WS 收到 notify 事件即重拉未读数,顶栏铃铛红点实时亮起(不再依赖整页刷新) */
function onNotifyPush() {
  loadUnread()
}

/**
 * 消息未读条数（TabBar / 侧栏「消息」角标）= 私信未读 + 群未读。
 *
 * 【2026-10-21 修 bug #1】改版前数的是 `convs.filter(c => c.pendingReply).length` —
 * 那是「待回应会话数」，不是未读消息数。只要有一条会话需要回复，
 * 角标就恒为 1，无论后来来了多少条消息都不变 —— 用户反馈的「一直是 1」就是它。
 * 改成对 conversations 里每个会话的 `unread` 求和。
 *
 * 【2026-10-21 修 bug #2】群聊未读此前完全没被计入：
 * 群消息在 GroupVO.myUnread 里（后端 GroupServiceImpl 算好的），
 * 而这里只查了私信会话列表，于是「群里有人说话」角标毫无反应。
 * 角标语义是「有几个地方在等你」，群消息同样算 —— 现在两路合并累加。
 *
 * 取数：私信会话前 30 条 + 我加入的群前 50 个。
 * 这是正常用户不可能撑满的量；未读超过 99 时 UI 显示 99+，再大也没有区分意义。
 * 任一路失败不影响另一路（各自 catch），避免一个接口抖动把角标清零。
 */
async function loadPaperCount() {
  const [chatApi, groupApi] = await Promise.all([
    import('@/api/chat').catch(() => null),
    import('@/api/group').catch(() => null),
  ])
  if (!chatApi) {
    paperCount.value = 0
    return
  }

  /*
   * 两路并行而不是串行：少一个 RTT。
   * 注意分别 catch —— 私信挂了不该让群未读也归零，反之亦然。
   */
  const [dmUnread, groupUnread] = await Promise.all([
    chatApi.conversations(0, 30)
      .then((page) => (page?.list ?? []).reduce((s, c) => s + (Number(c?.unread) || 0), 0))
      .catch(() => 0),
    loadGroupUnread(groupApi),
  ])

  paperCount.value = dmUnread + groupUnread
}

/**
 * 群未读汇总。
 *
 * myGroups（GET /group）是现成端点，返回里的 GroupVO.myUnread
 * 由后端按「入群水位线之后的消息数」算好，无需新增接口。
 *
 * 失败返回 0 而不是抛错：群列表拿不到时，角标至少还能反映私信未读，
 * 比整块归零诚实。
 */
async function loadGroupUnread(groupApi) {
  if (!groupApi) return 0
  try {
    const data = await groupApi.myGroups(null, 50)
    return (data?.list ?? []).reduce((s, g) => s + (Number(g?.myUnread) || 0), 0)
  } catch {
    return 0
  }
}

/*
 * 角标随页面切换刷新（2026-10-21 加）。
 *
 * 改版前两个角标只在 onMounted 拉一次 —— 用户读完消息、回到首页，
 * 「消息 1」还在那儿亮着，要刷新整页才消失。
 *
 * 现在的规则：
 *   进入聊天/通知页 → 不清零也不拉（清零由页面自己 inject 后调用；
 *     布局也不该在这时把值拉回来覆盖掉）
 *   离开聊天/通知页 → 重拉一次拿准值
 *   其他跳页 → 什么都不做，避免每跳一次打两个请求
 */
watch(
  () => route.fullPath,
  (to, from) => {
    if (!userStore.hasToken) return
    const isChat = (r) => String(r?.name || '').startsWith('chat')
    const isNotify = (r) => r?.name === 'notify'
    /*
     * 进入这两个页面时不拉数：
     * 页面自己会 clearXxxBadge() 清零，若这里再 loadUnread()，
     * 请求返回的旧值会把刚清零的角标又覆盖回去（实测角标仍是 3）。
     */
    if (isChat(to) || isNotify(to)) return
    // 离开时才重拉，拿的是「读完之后」的真实值
    if (isChat(from) || isNotify(from)) {
      loadPaperCount()
      loadUnread()
    }
  },
)

onMounted(() => {
  // 兜底:进布局时若 userInfo 还没加载(token 有效但没拉过),拉一次,
  // 保证顶栏头像 / 个人主页 / 退出都能用。
  if (userStore.hasToken && !userStore.userInfo) {
    userStore.fetchMe().catch(() => {})
  }
  if (userStore.hasToken) {
    /*
     * 首屏就落在聊天/通知页时不要拉数（2026-10-21）。
     * 否则：页面 onMounted 里清零 → 这里晚一步的 loadUnread() 返回旧值 →
     * 角标又被点亮。用户在通知页里却看到左侧「通知 3」，很矛盾。
     */
    const startName = String(route.name || '')
    const onFeedPage = startName === 'notify' || startName.startsWith('chat')
    if (!onFeedPage) {
      loadUnread()
      loadPaperCount()
    }
    // 常连 notify 通道:即使不在通知页,新通知也能实时点亮顶栏红点。
    // 与 Notify.vue 各自订阅同一事件、互不干扰。
    ws.connect('/ws/notify')
    ws.on('notify', onNotifyPush)
  }
})

onBeforeUnmount(() => {
  ws.off('notify', onNotifyPush)
})

async function logout() {
  try {
    await ElMessageBox.confirm('确定退出登录?', '提示', { type: 'warning' })
  } catch {
    return // 点了取消
  }
  getWsClient().close() // 断开 WS,避免收到已注销账号的推送
  try {
    await userStore.logout() // 吊销 refresh token + 清本地
    ElMessage.success('已退出登录')
    router.replace({ path: '/login/form' })
  } catch {
    // 即使后端调不通(如网络),也清本地并跳登录页 —— 不能把人困在已登录态
    userStore.clear()
    router.replace({ path: '/login/form' })
  }
}
</script>

<style scoped src="./MainLayout.css"></style>