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
        <el-icon :size="24"><ArrowLeft /></el-icon>      </button>
      <!--
        C20(2026-10-21):非返回态左上角默认品牌「友声」,但页面可以用
        meta.headTitle 覆盖成页面名(如圈子页写「圈子」) —— 顶栏是导航,
        用户在哪个页要一眼看到,不能靠底栏高亮去猜。品牌留给登录/个人页。

        2026-10-21 桌面端补页面名：桌面 CSS 把 .topbar__brand 隐藏了
        （左侧有常驻 SideNav，不需要再写品牌），但页面标题只在
        「返回型」顶栏（showBack）才渲染 —— 结果实测 .topbar__left
        宽度为 0，顶栏左边 900px 全空，只剩右边三个控件飘着。
        现在桌面端用 .topbar__page 单独渲染页面名，手机端继续用品牌/headTitle。
      -->
      <span v-if="!showBack" class="topbar__brand">{{ headTitle || '友声' }}</span>
      <span v-if="!showBack" class="topbar__page">{{ pageName }}</span>
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
        <!--
          自绘描边加号（2026-10-21）。原来用 EP 的 <Plus />，那是**填充式**图标，
          与右侧自绘的汉堡（描边 1.9）放在一起粗细完全不同 ——
          两个 44px 按钮挨着，一眼能看出不是一套。
          换成与 NavIcon 同口径的描边 SVG 后，顶栏三枚图标（+ / 汉堡 / 返回）
          都走 stroke 1.9，视觉统一。

          桌面端补文字「发动态」：发帖是最高频的主动作，一个裸图标在桌面上
          没有分量（侧栏原本有一项「发动态」，已移除，见 SideNav 注释）。
          手机端只留图标（空间紧，且 TabBar 让位后顶栏「+」是唯一发帖入口）。
        -->
        <svg
          class="topbar__ico"
          width="21"
          height="21"
          viewBox="0 0 24 24"
          fill="none"
          aria-hidden="true"
        >
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" />
        </svg>
        <span class="topbar__pub-text">发动态</span>
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
import { ArrowLeft, Bell, CaretBottom, Search } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { resolveThumb } from '@/utils/resolve'
import UnreadDot from '@/components/mobile/UnreadDot.vue'

const props = defineProps({
  showBack: { type: Boolean, default: false },
  pageTitle: { type: String, default: '' },
  /** 非返回态左上角文字(路由 meta.headTitle),空 = 品牌「友声」 */
  headTitle: { type: String, default: '' },
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

/**
 * 桌面端顶栏的页面名。
 *
 * 只在无返回态（一级页面）显示；返回态由 .topbar__title 负责（那里已经有 h1）。
 * headTitle 优先（圈子页写「圈子」），否则用 meta.title，最后回落品牌。
 * 手机端这个元素被 CSS 隐藏（见 TopBar.css），避免与品牌「友声」重复。
 */
const pageName = computed(() => props.headTitle || props.pageTitle || '')

function goBack() {
  // 有历史就退,没有(直接输 URL 进来)就去首页 —— 退无可退时给个确定去处
  if (window.history.length > 1) router.back()
  else router.replace('/')
}
</script>

<style scoped src="./TopBar.css"></style>
