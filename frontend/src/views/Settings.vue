<template>
  <div class="settings">
    <div class="settings-inner">
      <!--
        设置页(/settings):2026-10-18 改版为「分类首页」。
        - 首页 = 个人卡(进编辑资料) + 四个分类入口 + 深色模式快捷开关 + 退出
        - 陌生人消息开关搬去 /settings/privacy(隐私设置)
        - 我的审核记录弹层搬去导航抽屉(全局入口,不再只属于设置页)
        - 管理后台入口保留在本页:手机端管理员没有侧栏,这里是唯一入口,
          拿掉会让管理员在手机上失去后台(桌面有 SideNav 不受影响)
      -->

      <!-- 个人卡:头像 + 昵称 + 友声号,整行点击进编辑资料(/profile) -->
      <button type="button" class="mecard" @click="router.push('/profile')">
        <span class="mecard__ava">{{ avatarFallback }}</span>
        <span class="mecard__text">
          <span class="mecard__name">{{ userStore.userInfo?.nickname || '未登录' }}</span>
          <span class="mecard__id">
            友声号:{{ userStore.userInfo?.studentNo || '未填写' }}<template v-if="verifyVerified"> · 已认证</template>
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
              <span class="set-row__label">管理后台</span>
              <span class="set-row__desc">学号核验 / 内容审核 / 公告 / 日志</span>
            </span>
            <el-icon class="set-row__chevron"><ArrowRight /></el-icon>
          </button>
        </div>
      </el-card>

      <!-- 四个分类入口 -->
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

      <!-- 深色模式快捷开关:高频项外露,通用设置里也有同一开关(共享 useTheme) -->
      <el-card class="settings__group">
        <div class="set-row set-row--switch">
          <span class="set-row__icon"><el-icon :size="18"><Moon /></el-icon></span>
          <span class="set-row__text">
            <span class="set-row__label">深色模式</span>
            <span class="set-row__desc">夜间刷校园动态更护眼,切换即时生效,刷新不丢</span>
          </span>
          <el-switch
            :model-value="isDark"
            aria-label="深色模式开关"
            style="--el-switch-on-color: var(--ys-accent)"
            @change="toggleTheme"
          />
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
        <button type="button" class="asheet__item" @click="goAdmin('/admin/verify')">学号核验</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/review')">内容审核</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/announcement')">公告管理</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/audit')">操作日志</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/circles')">圈子处置</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/feedback')">意见反馈</button>
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
 * - 陌生人消息开关在 /settings/privacy;审核记录弹层在导航抽屉
 */
import {
  ArrowRight, Hide, InfoFilled, Lock, Moon, Operation, Setting, SwitchButton,
} from '@element-plus/icons-vue'
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { me as meApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { useSettings } from '@/composables/useSettings'
import { useTheme } from '@/composables/useTheme'
import { useVerifyStatus, VERIFY_OK } from '@/composables/useVerifyStatus'
import ActionSheet from '@/components/mobile/ActionSheet.vue'

const router = useRouter()
const userStore = useUserStore()
const { isDark, toggle: toggleTheme } = useTheme()
const { code, load: loadVerifyStatus } = useVerifyStatus()
const { adminSheetOpen, goAdmin, doLogout } = useSettings()

/** 已认证(个人卡展示用):只认 verify_status=1,「审核中/驳回」不标已认证 */
const verifyVerified = computed(() => code.value === VERIFY_OK)

/** 头像拉取失败/无图时的首字母兜底,与 TopBar 同规则 */
const avatarFallback = computed(() => {
  const n = userStore.userInfo?.nickname
  return n ? String(n).slice(0, 1) : '友'
})

onMounted(async () => {
  try {
    const me = await meApi()
    userStore.setUserInfo(me)
  } catch {
    /* 未登录由路由守卫拦,这里静默 */
  }
  // 认证状态:个人卡的「已认证」角标要用(全站单例缓存,不重复请求)
  loadVerifyStatus()
})
</script>

<style scoped src="./Settings.css"></style>
