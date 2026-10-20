<template>
  <header class="topbar">
    <!--
      顶栏两种形态由路由 meta 决定(MainLayout.vue.md §1.1):
        无 meta.back  -> 品牌型:左「友声」+ 右 发布/铃铛/汉堡
        有 meta.back  -> 返回型:左 ArrowLeft + 页面标题 + 右 铃铛/汉堡
      用 meta 而不是组件内自己算路径:activeMenu 已经是串 startsWith 判断,
      再加两种模式会变成三处互相打架的分支。
    -->
    <div class="topbar__left">
      <button
        v-if="showBack"
        type="button"
        class="topbar__icon-btn"
        aria-label="返回上一页"
        @click="goBack"
      >
        <el-icon :size="24"><ArrowLeft /></el-icon>
      </button>
      <span v-else class="topbar__brand">友声</span>
      <h1 v-if="showBack" class="topbar__title">{{ pageTitle }}</h1>
    </div>

    <div class="topbar__right">
      <!--
        发布按钮(2026-10-20 移动端恢复):TabBar 中位已让位给「集市」,
        顶栏「+」是移动端唯一常驻发帖入口,手机/桌面两端都显示
        (桌面为实心圆样式,见 TopBar.css 媒体查询)。
      -->
      <button
        type="button"
        class="topbar__icon-btn topbar__publish"
        aria-label="发一条动态"
        @click="router.push('/publish')"
      >
        <el-icon :size="20"><Plus /></el-icon>
      </button>

      <!--
        「找人」只留桌面:feed 内已有搜索框、/search 页是主入口,移动端
        再塞一个找人与它们重叠。移动端右侧 = 发布/铃铛/汉堡;
        桌面 ≥1024 空间够、侧栏已占左边,顶栏保留 发动态/找人/铃铛/头像/汉堡(隐藏)。
      -->
      <button
        type="button"
        class="topbar__icon-btn topbar__search"
        aria-label="找人"
        @click="router.push('/search')"
      >
        <el-icon :size="24"><Search /></el-icon>
      </button>

      <button
        type="button"
        class="topbar__icon-btn topbar__bell"
        :aria-label="notifyAriaLabel"
        @click="router.push('/notify')"
      >
        <el-icon :size="24"><Bell /></el-icon>
        <UnreadDot :count="notifyUnread" noun="条通知未读" />
      </button>

      <!--
        桌面端头像:点击打开全局导航抽屉(2026-10-18 从精简下拉菜单
        改为与手机端共用的 AppDrawer,内容与设计稿一致——头像头部卡/圈子/
        审核记录/校内认证/意见反馈/深色开关/设置/退出全在抽屉里);
        手机端头像放底部 TabBar「我的」,此处不渲染(桌面才显示 .topbar__avatar-wrap)。
      -->
      <div class="topbar__avatar-wrap">
        <button
          type="button"
          class="topbar__avatar-trigger"
          aria-label="打开导航菜单"
          @click="$emit('menu')"
        >
          <el-avatar :size="34" :src="resolveThumb(userStore.userInfo?.avatarUrl)">
            <span v-if="!userStore.userInfo?.avatarUrl" class="topbar__avatar-fallback">
              {{ avatarFallback }}
            </span>
          </el-avatar>
          <el-icon class="topbar__caret"><CaretBottom /></el-icon>
        </button>
      </div>

      <!--
        汉堡(2026-10-20 移到右端):全局导航抽屉(AppDrawer)入口,
        **所有顶栏形态都渲染** —— 返回型(如「我的」页)也必须有,
        否则用户进了二级页就找不到抽屉。桌面 CSS 隐藏(有常驻 SideNav,冗余)。
        图标用内联 SVG 三横线(主流汉堡样式):EP 无纯三横线图标,
        Menu 是九宫格、Fold/Expand 带箭头,观感都不对,故自绘。
      -->
      <button
        type="button"
        class="topbar__icon-btn topbar__menu"
        aria-label="打开菜单"
        @click="$emit('menu')"
      >
        <svg
          class="topbar__burger"
          width="22"
          height="22"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <path
            d="M4 6h16M4 12h16M4 18h16"
            stroke="currentColor"
            stroke-width="2"
            stroke-linecap="round"
          />
        </svg>
      </button>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Bell, CaretBottom, Plus, Search } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { resolveThumb } from '@/utils/resolve'
import UnreadDot from '@/components/mobile/UnreadDot.vue'

const props = defineProps({
  showBack: { type: Boolean, default: false },
  pageTitle: { type: String, default: '' },
  notifyUnread: { type: Number, default: 0 },
})

/** menu:汉堡(手机)与头像(桌面)都唤起全局导航抽屉,由 MainLayout 接管 */
const emit = defineEmits(['menu'])

const router = useRouter()
const userStore = useUserStore()

/** 头像拉取失败时降级为首字母块,不报错不弹窗(布局不接管全站级错误) */
const avatarFallback = computed(() => {
  const n = userStore.userInfo?.nickname
  return n ? String(n).slice(0, 1) : '友'
})

/** 未读为 0 时不加「N 条未读」后缀(避免播报一个无意义的 0) */
const notifyAriaLabel = computed(() =>
  props.notifyUnread > 0 ? `通知，${props.notifyUnread} 条未读` : '通知'
)

function goBack() {
  // 有历史就退,没有(直接输 URL 进来)就去首页 —— 退无可退时给个确定去处
  if (window.history.length > 1) router.back()
  else router.replace('/')
}
</script>

<style scoped src="./TopBar.css"></style>
