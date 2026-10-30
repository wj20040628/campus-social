<template>
  <div class="settings">
    <div class="settings-inner">
      <!--
        设置页(/settings):2026-10-18 改版为「分类首页」。
        - 首页 = 个人卡(进编辑资料) + 四个分类入口 + 深色模式快捷开关 + 退出
        - 陌生人消息开关搬去 /settings/privacy(隐私设置)
        - 我的审核记录弹层 2026-10-20 从导航抽屉搬回本页:被审过才有记录,
          是全站最低频的入口之一,不该占抽屉中段还独立占一个分组标题
        - 管理后台入口保留在本页:手机端管理员没有侧栏,这里是唯一入口,
          拿掉会让管理员在手机上失去后台(桌面有 SideNav 不受影响)
      -->

      <!-- 个人卡:头像 + 昵称 + 学号,整行点击进编辑资料(/profile) -->
      <button type="button" class="mecard" @click="router.push('/profile')">
        <span class="mecard__ava">{{ avatarFallback }}</span>
        <span class="mecard__text">
          <span class="mecard__name">{{ userStore.userInfo?.nickname || '未登录' }}</span>
          <span class="mecard__id">
            学号:{{ studentNo || userStore.userInfo?.studentNo || '未填写' }}<template v-if="verifyVerified"> · 校园信息已登记</template>
          </span>
        </span>
        <el-icon class="mecard__chev"><ArrowRight /></el-icon>
      </button>

      <!-- 管理后台:仅 isAdmin(手机端管理员的唯一入口) -->
      <el-card v-if="userStore.isAdmin" class="settings__group">
        <div class="set-row set-row--link">
          <button type="button" class="set-row__btn" @click="adminSheetOpen = true">
            <span class="set-row__icon"><el-icon :size="18"><Operation /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">
                管理后台
                <AdminPendingBadge :count="pendingTotal" class="set-row__count" />
              </span>
              <span class="set-row__desc">校园信息核对 / 举报 / 内容审核 / 公告 / 日志</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
      </el-card>

      <!-- 五个分类入口 -->
      <el-card class="settings__group">
        <div class="set-row set-row--link">
          <button type="button" class="set-row__btn" @click="router.push('/settings/security')">
            <span class="set-row__icon"><el-icon :size="18"><Lock /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">账号与安全</span>
              <span class="set-row__desc">修改密码 / 绑定邮箱</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="set-row set-row--link">
          <button type="button" class="set-row__btn" @click="router.push('/settings/privacy')">
            <span class="set-row__icon"><el-icon :size="18"><Hide /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">隐私设置</span>
              <span class="set-row__desc">陌生人消息 / 黑名单</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="set-row set-row--link">
          <button type="button" class="set-row__btn" @click="router.push('/settings/notify')">
            <span class="set-row__icon"><el-icon :size="18"><Bell /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">通知设置</span>
              <span class="set-row__desc">点赞 / 评论 / 关注提醒</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="set-row set-row--link">
          <button type="button" class="set-row__btn" @click="router.push('/settings/general')">
            <span class="set-row__icon"><el-icon :size="18"><Setting /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">通用设置</span>
              <span class="set-row__desc">深色模式 / 字体大小</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
        <div class="set-row set-row--link">
          <button type="button" class="set-row__btn" @click="router.push('/settings/about')">
            <span class="set-row__icon"><el-icon :size="18"><InfoFilled /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">关于我们</span>
              <span class="set-row__desc">版本 / 联系我们</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
      </el-card>

      <!--
        我的审核记录(2026-10-20 从抽屉搬回)。
        放在分类入口之后、深色模式之前:它既不是设置项也不是高频入口,
        只是「我的历史」,靠后但和内容相关的位置最合适。
      -->
      <el-card class="settings__group">
        <div class="set-row set-row--link">
          <button type="button" class="set-row__btn" @click="openReview">
            <span class="set-row__icon"><el-icon :size="18"><Tickets /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">我的审核记录</span>
              <span class="set-row__desc">看自己发过的动态和评论审到哪一步了</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
      </el-card>

      

      <!-- 退出登录:危险项 -->
      <el-card class="settings__group settings__group--danger">
        <div class="set-row set-row--link set-row--danger">
          <button type="button" class="set-row__btn" @click="doLogout">
            <span class="set-row__icon"><el-icon :size="18"><SwitchButton /></el-icon></span>
            <span class="set-row__text">
              <span class="set-row__label">退出登录</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
      </el-card>

      <ActionSheet v-model="adminSheetOpen" title="管理后台">
        <button
          type="button"
          class="asheet__item"
          :class="{ 'is-pending': pending.summary.verifyPending > 0 }"
          @click="goAdmin('/admin/verify')"
        >
          校园信息核对
          <AdminPendingBadge :count="pending.summary.verifyPending" class="settings__pending" />
        </button>
        <button
          type="button"
          class="asheet__item"
          :class="{ 'is-pending': pending.summary.reportPending > 0 }"
          @click="goAdmin('/admin/reports')"
        >
          举报处置
          <AdminPendingBadge :count="pending.summary.reportPending" class="settings__pending" />
        </button>
        <button
          type="button"
          class="asheet__item"
          :class="{ 'is-pending': pending.summary.reviewPending > 0 }"
          @click="goAdmin('/admin/review')"
        >
          内容审核
          <AdminPendingBadge :count="pending.summary.reviewPending" class="settings__pending" />
        </button>
        <button
          type="button"
          class="asheet__item"
          :class="{ 'is-pending': pending.summary.filePending > 0 }"
          @click="goAdmin('/admin/files')"
        >
          文件审核
          <AdminPendingBadge :count="pending.summary.filePending" class="settings__pending" />
        </button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/users')">用户处置</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/announcement')">公告管理</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/audit')">操作日志</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/circles')">圈子处置</button>
        <button
          type="button"
          class="asheet__item"
          :class="{ 'is-pending': pending.summary.feedbackPending > 0 }"
          @click="goAdmin('/admin/feedback')"
        >
          意见反馈
          <AdminPendingBadge :count="pending.summary.feedbackPending" class="settings__pending" />
        </button>
      </ActionSheet>

      <!-- 我的审核记录弹层:从抽屉整体搬来,懒加载 /review/mine,拉过不重复 -->
      <ActionSheet v-model="reviewOpen" title="我的审核记录" height="70vh">
        <div v-if="reviewLoading" class="review-empty">加载中…</div>
        <MyReviewBlock v-else-if="reviewList.length" :list="reviewList" />
        <div v-else class="review-empty">还没有审核记录</div>
      </ActionSheet>
    </div>
  </div>
</template>

<script setup>
/**
 * 设置页(/settings)。2026-10-18 改版为「分类首页」:
 * - 个人卡进编辑资料(/profile);四个分类入口进二级页
 * - 深色模式快捷开关外露(与 /settings/general、桌面顶栏下拉共享 useTheme 单例)
 * - 管理后台 ActionSheet 保留:手机端管理员没有侧栏,这里是唯一入口
 * - 陌生人消息开关在 /settings/privacy;审核记录弹层 2026-10-20 从抽屉搬回本页
 */
import {
  ArrowRight, Bell, Hide, InfoFilled, Lock, Moon, Operation, Setting, SwitchButton, Tickets,
} from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { me as meApi } from '@/api/auth'
import { mine as myReviewApi } from '@/api/review'
import { useUserStore } from '@/stores/user'
import { useSettings } from '@/composables/useSettings'
import { useTheme } from '@/composables/useTheme'
import { useVerifyStatus, VERIFY_OK } from '@/composables/useVerifyStatus'
import { useAdminPending } from '@/composables/useAdminPending'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import MyReviewBlock from '@/components/common/MyReviewBlock.vue'
import AdminPendingBadge from '@/components/admin/AdminPendingBadge.vue'

const router = useRouter()
const userStore = useUserStore()
const { isDark, toggle: toggleTheme } = useTheme()
const { code, studentNo, load: loadVerifyStatus } = useVerifyStatus()
const { adminSheetOpen, goAdmin, doLogout } = useSettings()

/* 管理待办:设置页是手机端管理员的一个入口,角标与抽屉/侧栏共用同一份缓存 */
const pending = useAdminPending()
const pendingTotal = computed(() => pending.total())

/** 校园信息已登记(个人卡展示用):只认 verify_status=1 */
const verifyVerified = computed(() => code.value === VERIFY_OK)

/** 头像拉取失败/无图时的首字母兜底,与 TopBar 同规则 */
const avatarFallback = computed(() => {
  const n = userStore.userInfo?.nickname
  return n ? String(n).slice(0, 1) : '友'
})

/* ---- 我的审核记录(2026-10-20 从抽屉搬回,懒加载,拉过不重复) ---- */
const reviewOpen = ref(false)
const reviewList = ref([])
const reviewLoading = ref(false)

function openReview() {
  reviewOpen.value = true
  if (reviewList.value.length) return
  reviewLoading.value = true
  myReviewApi(null, 50)
    .then((data) => {
      reviewList.value = (data?.list || []).filter(Boolean)
    })
    .catch(() => {
      // 拉不到就展示空态,不在设置页弹 toast —— 这是增强信息,不是主流程
      reviewList.value = []
    })
    .finally(() => {
      reviewLoading.value = false
    })
}

onMounted(async () => {
  try {
    const me = await meApi()
    userStore.setUserInfo(me)
  } catch {
    /* 未登录由路由守卫拦,这里静默 */
  }
  // 校园信息状态:个人卡的登记标记要用(全站单例缓存,不重复请求)
  loadVerifyStatus()
  pending.refresh()
})
</script>

<style scoped src="./Settings.css"></style>
