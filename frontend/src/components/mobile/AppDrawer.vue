<template>
  <Teleport to="body">
    <Transition name="adrawer">
      <div v-if="modelValue" class="adrawer" @keydown.esc="close">
        <!-- 遮罩:点击关闭 -->
        <div class="adrawer__mask" @click="close"></div>

        <!--
          左侧滑出面板。导航项点击后先跳路由再关抽屉:
          先关会让 Transition 撕掉 DOM,router.push 的动画会闪一下。
        -->
        <aside class="adrawer__panel" role="dialog" aria-modal="true" aria-label="导航菜单">
          <!--
            关闭按钮独占顶部一条 44px 工具条(2026-10-20)。
            原来没有关闭按钮,只能点遮罩或按 ESC —— 手机上没有 ESC,
            遮罩又被抽屉挡掉大半,只剩左边一条缝。
            也不能把它绝对定位在右上角:会和资料区垂直居中的「›」挤在同一列。
          -->
          <div class="adrawer__tool">
            <button type="button" class="adrawer__close" aria-label="关闭菜单" @click="close">
              <NavIcon name="close" :size="17" />
            </button>
          </div>

          <!-- 头部:头像 + 昵称 + 认证徽章 + 学号,点击进我的主页 -->
          <button type="button" class="adrawer__head" @click="goHome">
            <el-avatar :size="56" :src="resolveThumb(userStore.userInfo?.avatarUrl)">
              <span v-if="!userStore.userInfo?.avatarUrl">{{ avatarFallback }}</span>
            </el-avatar>
            <span class="adrawer__headtext">
              <span class="adrawer__namerow">
                <span class="adrawer__name">{{ userStore.userInfo?.nickname || '未登录' }}</span>
                <span v-if="verified" class="adrawer__badge">
                  <NavIcon name="check" :size="11" :stroke-width="2.6" />
                  已认证
                </span>
              </span>
              <span class="adrawer__id">{{ studentNoText }}</span>
            </span>
            <span class="adrawer__chev" aria-hidden="true">›</span>
          </button>

          <nav class="adrawer__nav">
            <button
              v-for="item in NAV_ITEMS"
              :key="item.label"
              type="button"
              class="adrawer__item"
              @click="onNav(item)"
            >
              <span class="adrawer__ic">
                <NavIcon :name="item.icon" />
                <!-- UnreadDot 是 absolute：必须挂在有定位的图标容器里，
                     否则会以抽屉面板为基准跑到角落里（通知角标“消失”）。 -->
                <UnreadDot
                  v-if="item.unread"
                  :count="notifyUnread"
                  noun="条通知未读"
                />
              </span>
              {{ item.label }}
            </button>

            <!--
              管理区:唯一保留分组标题与色块的地方 —— 它是权限分界,
              「我不是以普通用户身份进来的」值得一个视觉标记。
            -->
            <div v-if="userStore.isAdmin" class="adrawer__admin">
              <div class="adrawer__cap">管理</div>
              <button type="button" class="adrawer__item" @click="adminSheetOpen = true">
                <span class="adrawer__ic adrawer__ic--admin"><NavIcon name="sliders" :size="15" /></span>
                管理后台
              </button>
            </div>
          </nav>

          <!-- 底部:外观三态 + 设置 + 退出。退出单独分隔,防误触掉登录态 -->
          <footer class="adrawer__foot">
            <button type="button" class="adrawer__item" @click="themeSheetOpen = true">
              <span class="adrawer__ic"><NavIcon name="moon" /></span>
              深色模式
              <span class="adrawer__val">
                {{ modeLabel }}<span class="adrawer__cs" aria-hidden="true">›</span>
              </span>
            </button>
            <button type="button" class="adrawer__item" @click="go('/settings')">
              <span class="adrawer__ic"><NavIcon name="gear" /></span>
              设置
            </button>
            <div class="adrawer__sep" aria-hidden="true"></div>
            <button type="button" class="adrawer__item adrawer__item--danger" @click="onLogout">
              <span class="adrawer__ic"><NavIcon name="logout" /></span>
              退出登录
            </button>
          </footer>
        </aside>
      </div>
    </Transition>

    <!-- 外观三态:行里只看得到当前值,点开才是三选一 -->
    <ActionSheet v-model="themeSheetOpen" title="外观">
      <button
        v-for="opt in THEME_OPTIONS"
        :key="opt.value"
        type="button"
        class="asheet__item adrawer__opt"
        :aria-pressed="mode === opt.value"
        @click="pickMode(opt.value)"
      >
        {{ opt.label }}
        <NavIcon
          v-if="mode === opt.value"
          class="adrawer__tick"
          name="check"
          :size="16"
          :stroke-width="2.2"
        />
      </button>
    </ActionSheet>

    <!-- 管理后台弹层:复用 useSettings 的 adminSheetOpen(与设置页同源) -->
    <ActionSheet v-model="adminSheetOpen" title="管理后台">
      <button type="button" class="asheet__item" @click="goAdmin('/admin/verify')">
        学号核验
        <UnreadDot :count="pending.summary.verifyPending" show-count noun="条待审" class="adrawer__pending" />
      </button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/reports')">
        举报处置
        <UnreadDot :count="pending.summary.reportPending" show-count noun="条待办" class="adrawer__pending" />
      </button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/review')">
        内容审核
        <UnreadDot :count="pending.summary.reviewPending" show-count noun="条待办" class="adrawer__pending" />
      </button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/users')">用户处置</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/announcement')">公告管理</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/audit')">操作日志</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/circles')">圈子处置</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/feedback')">
        意见反馈
        <UnreadDot :count="pending.summary.feedbackPending" show-count noun="条待办" class="adrawer__pending" />
      </button>
    </ActionSheet>

    <!--
      意见反馈弹层挂在 Teleport 内但**不在抽屉 DOM 里**:
      el-dialog 自己带 Teleport + append-to-body,放在抽屉面板内会被
      Transition 的 v-if 一起撕掉(抽屉一关,弹层跟着消失)。
    -->
    <FeedbackDialog ref="feedbackRef" />
  </Teleport>
</template>

<script setup>
/**
 * 全局导航抽屉(2026-10-18 新增;2026-10-20 按设计稿重排入口与视觉)。
 *
 * [入口顺序:按「从当前页能不能一步到达」筛,不是按功能目录排]
 *   通知      唯一带未读数字、会主动推你点的项。个人主页是 ownTop,
 *             不渲染顶栏也就没有铃铛 —— 在「我的」页想看通知只有这条路上来。
 *   公告      和通知才是同类:一个是「谁动了我的东西」,一个是「平台对我说话」。
 *   发现同好  产品核心动机(按兴趣找同类型的人)。
 *   找人      补一个真实的空洞:TopBar 在移动端把搜索 display:none 了
 *             (TopBar.css「找人只留桌面」),而「feed 里有搜索框」只在首页成立,
 *             圈子/集市/消息页原本没有任何主动找人的入口。
 *   意见反馈  与设置是邻居,但设置固定在底部,这里放列表末尾。
 *
 * 被移走的两项(不是拍脑袋,各有理由):
 *   编辑资料      与个人主页头部 @edit 完全重复(isMe 时就在最显眼处),
 *                 没必要占抽屉第一排。
 *   我的审核记录  全站最低频的入口之一(被审过才有记录),已搬回设置页。
 *
 * [为什么删掉 4 个分组标题]
 * 「我的内容 / 发现 / 更多」平均每段只有 1.3 个项,标题比内容还密。
 * 现在入口从 7 个减到 6 个,靠留白就能分隔,只给「管理」保留标题。
 *
 * [为什么不做成 8 项]
 * 面板 698px,当前组合占满后 nav 区只剩约 75px,一行是 46px ——
 * 再加一项就要滚。抽屉一旦要滚,第一项的位置价值就翻倍。
 *
 * - 未读数由 MainLayout 下发(props),与顶栏铃铛同源,不重复请求
 * - 管理后台复用 useSettings 的 adminSheetOpen,与设置页同源
 * - 退出登录的确认弹窗与真实登出都在 MainLayout.logout
 */
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useTheme, THEME_MODE_LABEL } from '@/composables/useTheme'
import { useVerifyStatus, VERIFY_OK, VERIFY_PENDING } from '@/composables/useVerifyStatus'
import { useSettings } from '@/composables/useSettings'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import FeedbackDialog from '@/views/settings/FeedbackDialog.vue'
import UnreadDot from '@/components/mobile/UnreadDot.vue'
import NavIcon from '@/components/mobile/NavIcon.vue'
import { useAdminPending } from '@/composables/useAdminPending'
import { resolveThumb } from '@/utils/resolve'

defineProps({
  modelValue: { type: Boolean, default: false },
  notifyUnread: { type: Number, default: 0 },
})

const emit = defineEmits(['update:modelValue', 'logout'])

const router = useRouter()
const userStore = useUserStore()
const { mode, setMode } = useTheme()
const { adminSheetOpen, goAdmin } = useSettings()

/*
 * 管理待办角标(2026-10-24 新增):挂「管理后台」弹层的入口上,
 * 让管理员不逐页巡检就知道哪条队列有活。模块级缓存,与 SideNav 共享,
 * 挂载时拉一次;处置动作后的强刷由各管理页自己 refresh(force) 触发。
 */
const pending = useAdminPending()
onMounted(() => pending.refresh())

/** 导航项:有 path 的走路由,没有的走组件内动作(意见反馈是弹层不是页面) */
const NAV_ITEMS = [
  { icon: 'bell', label: '通知', path: '/notify', unread: true },
  { icon: 'megaphone', label: '公告', path: '/announcement' },
  { icon: 'compass', label: '发现同好', path: '/discover' },
  { icon: 'users', label: '找人', path: '/search' },
  { icon: 'message', label: '意见反馈' },
]

const THEME_OPTIONS = [
  { value: 'system', label: THEME_MODE_LABEL.system },
  { value: 'light', label: THEME_MODE_LABEL.light },
  { value: 'dark', label: THEME_MODE_LABEL.dark },
]

const modeLabel = computed(() => THEME_MODE_LABEL[mode.value] || THEME_MODE_LABEL.system)

/** 头像无图/拉取失败时的首字母兜底,与 TopBar 同规则 */
const avatarFallback = computed(() => {
  const n = userStore.userInfo?.nickname
  return n ? String(n).slice(0, 1) : '友'
})

function close() {
  emit('update:modelValue', false)
}

/** 跳转后关抽屉:先 push(导航立即开始)再收起,避免 Transition 撕 DOM 闪白 */
function go(path) {
  close()
  router.push(path).catch(() => {})
}

function onNav(item) {
  if (item.path) go(item.path)
  else openFeedback()
}

function goHome() {
  const id = userStore.userInfo?.id
  if (id != null) go(`/user/${id}`)
  else close()
}

/** 退出登录:确认弹窗与真实登出都在 MainLayout.logout,这里只关闭并上抛 */
function onLogout() {
  close()
  emit('logout')
}

function pickMode(value) {
  setMode(value)
  themeSheetOpen.value = false
}

const themeSheetOpen = ref(false)

/* ---- 学号:注册时自动填入并经校内认证,不在 /user/me 里回传,只有 verify/status 有 ---- */
const { code: verifyCode, studentNo, load: loadVerifyStatus } = useVerifyStatus()

/** 已认证才出徽章;「审核中 / 驳回」不标已认证 */
const verified = computed(() => verifyCode.value === VERIFY_OK)

/**
 * 学号展示。三态各说各的话,不给「未填写」这种像在催用户填表的措辞:
 * - 有学号 → 直接显示(绝大多数人)
 * - 审核中 → 「学号审核中」
 * - 没填/驳回 → 「未认证」
 */
const studentNoText = computed(() => {
  if (studentNo.value) return `学号 ${studentNo.value}`
  if (verifyCode.value === VERIFY_PENDING) return '学号审核中'
  return '未认证'
})

/* ---- 意见反馈(真表单,提交进管理员审核队列) ---- */
const feedbackRef = ref(null)

function openFeedback() {
  close()
  // 等抽屉收起再开弹层:两个遮罩叠在一起会让用户以为卡住了
  nextTick(() => feedbackRef.value?.open())
}

onMounted(() => {
  // 认证状态决定头部徽章与学号文案(单例缓存,不重复请求)
  loadVerifyStatus()
})
</script>

<style scoped src="./AppDrawer.css"></style>
