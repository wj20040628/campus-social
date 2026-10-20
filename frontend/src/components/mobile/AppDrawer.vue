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
          <!-- 头部:头像 + 昵称 + 学号,点击进我的主页 -->
          <button type="button" class="adrawer__head" @click="goHome">
            <el-avatar :size="52" :src="resolveThumb(userStore.userInfo?.avatarUrl)">
              <span v-if="!userStore.userInfo?.avatarUrl">{{ avatarFallback }}</span>
            </el-avatar>
            <span class="adrawer__headtext">
              <span class="adrawer__name">{{ userStore.userInfo?.nickname || '未登录' }}</span>
              <span class="adrawer__id">{{ studentNoText }}</span>
            </span>
          </button>

          <nav class="adrawer__nav">
            <!-- 编辑资料:进 /profile 编辑昵称/学校信息;头部点进去是「我的主页」展示页,这里才是编辑入口 -->
            <button type="button" class="adrawer__item" @click="go('/profile')">
              <span class="adrawer__ic"><el-icon :size="17"><Edit /></el-icon></span>
              编辑资料
            </button>

            <!-- 通知:互动通知(/notify),未读数与顶栏铃铛同源(MainLayout 下发) -->
            <button type="button" class="adrawer__item" @click="go('/notify')">
              <span class="adrawer__ic"><el-icon :size="17"><Bell /></el-icon></span>
              通知
              <UnreadDot :count="notifyUnread" noun="条通知未读" class="adrawer__dot" />
            </button>

            <div class="adrawer__cap">我的内容</div>
            <!-- 我的审核记录:弹层(原设置页逻辑整体搬来,入口更全局) -->
            <button type="button" class="adrawer__item" @click="openReview">
              <span class="adrawer__ic"><el-icon :size="17"><Tickets /></el-icon></span>
              我的审核记录
            </button>

            <div class="adrawer__cap">发现</div>
            <button type="button" class="adrawer__item" @click="go('/discover')">
              <span class="adrawer__ic"><el-icon :size="17"><Compass /></el-icon></span>
              发现
            </button>
            <button type="button" class="adrawer__item" @click="go('/announcement')">
              <span class="adrawer__ic"><el-icon :size="17"><Promotion /></el-icon></span>
              公告
            </button>

            <div class="adrawer__cap">更多</div>
            <!-- 意见反馈:真表单(分类 + 描述 + 截图/录屏),提交进管理员审核队列 -->
            <button type="button" class="adrawer__item" @click="openFeedback">
              <span class="adrawer__ic"><el-icon :size="17"><Promotion /></el-icon></span>
              意见反馈
            </button>

            <template v-if="userStore.isAdmin">
              <div class="adrawer__cap">管理</div>
              <!-- 管理后台:复用 useSettings 的 adminSheetOpen(ActionSheet 与各子页同源) -->
              <button type="button" class="adrawer__item" @click="adminSheetOpen = true">
                <span class="adrawer__ic"><el-icon :size="17"><Operation /></el-icon></span>
                管理后台
              </button>
            </template>
          </nav>

          <!-- 底部固定:深色开关 + 设置 + 退出(快手式,入口从此不再藏两层)。
               退出登录原在桌面头像下拉里,下拉改抽屉后挪到这里,两端统一 -->
          <footer class="adrawer__foot">
            <div class="adrawer__item adrawer__item--static">
              <span class="adrawer__ic adrawer__ic--dark"><el-icon :size="17"><Moon /></el-icon></span>
              深色模式
              <el-switch
                :model-value="isDark"
                size="small"
                aria-label="深色模式开关"
                style="--el-switch-on-color: var(--ys-accent)"
                @change="toggleTheme"
              />
            </div>
            <button type="button" class="adrawer__item adrawer__item--accent" @click="go('/settings')">
              <span class="adrawer__ic"><el-icon :size="17"><Setting /></el-icon></span>
              设置
            </button>
            <button type="button" class="adrawer__item adrawer__item--danger" @click="onLogout">
              <span class="adrawer__ic adrawer__ic--danger"><el-icon :size="17"><SwitchButton /></el-icon></span>
              退出登录
            </button>
          </footer>
        </aside>
      </div>
    </Transition>

    <!-- 我的审核记录弹层:与旧设置页同一套(懒加载 /review/mine) -->
    <ActionSheet v-model="reviewOpen" title="我的审核记录" height="70vh">
      <div v-if="reviewLoading" class="adrawer__empty">加载中…</div>
      <MyReviewBlock v-else-if="reviewList.length" :list="reviewList" />
      <div v-else class="adrawer__empty">还没有审核记录</div>
    </ActionSheet>

    <!--
      意见反馈弹层挂在 Teleport 内但**不在抽屉 DOM 里**:
      el-dialog 自己带 Teleport + append-to-body,放在抽屉面板内会被
      Transition 的 v-if 一起撕掉(抽屉一关,弹层跟着消失)。
    -->
    <!-- 管理后台弹层:复用 useSettings 的 adminSheetOpen(与设置页同源) -->
    <ActionSheet v-model="adminSheetOpen" title="管理后台">
      <button type="button" class="asheet__item" @click="goAdmin('/admin/verify')">学号核验</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/review')">内容审核</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/announcement')">公告管理</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/audit')">操作日志</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/circles')">圈子处置</button>
      <button type="button" class="asheet__item" @click="goAdmin('/admin/feedback')">意见反馈</button>
    </ActionSheet>

    <FeedbackDialog ref="feedbackRef" />
  </Teleport>
</template>

<script setup>
/**
 * 全局导航抽屉(2026-10-18 新增,手机端顶栏汉堡唤起;2026-10-19 按用户结构重构分组)。
 * - 头部(头像+昵称+学号,点进「我的主页」) + 编辑资料 + 通知(红点)
 *   + 我的内容(我的审核记录) + 发现(发现/公告) + 更多(意见反馈)
 *   + 管理(isAdmin:管理后台 ActionSheet) + 底部深色开关/设置/退出
 * - 桌面端头像也唤起本抽屉,两端内容一致
 * - 未读数由 MainLayout 下发(props),与顶栏铃铛同源,不重复请求
 * - 编辑资料跳 /profile;管理后台复用 useSettings 的 adminSheetOpen(与设置页同源)
 */
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell, Compass, Edit, Moon, Operation, Promotion, Setting, SwitchButton, Tickets,
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useTheme } from '@/composables/useTheme'
import { useVerifyStatus, VERIFY_PENDING } from '@/composables/useVerifyStatus'
import { useSettings } from '@/composables/useSettings'
import { mine as myReviewApi } from '@/api/review'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import FeedbackDialog from '@/views/settings/FeedbackDialog.vue'
import MyReviewBlock from '@/components/common/MyReviewBlock.vue'
import UnreadDot from '@/components/mobile/UnreadDot.vue'
import { resolveThumb } from '@/utils/resolve'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  notifyUnread: { type: Number, default: 0 },
})

const emit = defineEmits(['update:modelValue', 'logout'])

const router = useRouter()
const userStore = useUserStore()
const { isDark, toggle: toggleTheme } = useTheme()
const { adminSheetOpen, goAdmin } = useSettings()

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

/* ---- 学号:注册时自动填入并经校内认证,不在 /user/me 里回传,只有 verify/status 有 ---- */
const { code: verifyCode, studentNo, load: loadVerifyStatus } = useVerifyStatus()

/**
 * 学号展示。三态各说各的话,不给「未填写」这种像在催用户填表的措辞:
 * - 有学号 → 直接显示(绝大多数人)
 * - 审核中 → 「学号审核中」
 * - 没填/驳回 → 「未认证」
 */
const studentNoText = computed(() => {
  if (studentNo.value) return `学号:${studentNo.value}`
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

/* ---- 我的审核记录(懒加载,拉过不重复) ---- */
const reviewOpen = ref(false)
const reviewList = ref([])
const reviewLoading = ref(false)

function openReview() {
  close()
  reviewOpen.value = true
  if (reviewList.value.length) return
  reviewLoading.value = true
  myReviewApi(null, 50)
    .then((data) => {
      reviewList.value = (data?.list || []).filter(Boolean)
    })
    .catch(() => {
      reviewList.value = []
    })
    .finally(() => {
      reviewLoading.value = false
    })
}

onMounted(() => {
  // 认证状态决定头部学号文案(单例缓存,不重复请求)
  loadVerifyStatus()
})
</script>

<style scoped src="./AppDrawer.css"></style>
