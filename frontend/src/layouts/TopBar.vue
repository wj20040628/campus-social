<template>
  <header class="topbar">
    <!--
      顶栏两种形态由路由 meta 决定(MainLayout.vue.md §1.1):
        无 meta.back  -> 品牌型:左「友声」+ 右 搜索/铃铛
        有 meta.back  -> 返回型:左 ArrowLeft + 页面标题
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
        移动端删掉了原中间那个圆形「+」发布按钮:
        TabBar 中位已有发布入口,两个是冗余;且它带彩色发光阴影。
        桌面端仍保留,移到右端(见下方 .topbar__publish 的媒体查询)。
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
        「找人」只留桌面:feed 内已有搜索框、/search 页是主入口,移动端顶栏
        再塞一个找人与它们重叠,且移动端顶栏右侧只保留铃铛(全局未读,必须置顶栏)。
        桌面 ≥1024 空间够、侧栏已占左边,顶栏保留 发动态/找人/铃铛/头像。
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

      <!-- 桌面端头像下拉;手机端头像放底部 TabBar「我的」,此处不重复 -->
      <el-dropdown trigger="click" class="topbar__avatar-wrap" @command="onUserCommand">
        <button type="button" class="topbar__avatar-trigger" aria-label="账号菜单">
          <el-avatar :size="34" :src="resolveUrl(userStore.userInfo?.avatarUrl)">
            <span v-if="!userStore.userInfo?.avatarUrl" class="topbar__avatar-fallback">
              {{ avatarFallback }}
            </span>
          </el-avatar>
          <el-icon class="topbar__caret"><CaretBottom /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile">个人主页</el-dropdown-item>
            <el-dropdown-item command="settings">设置</el-dropdown-item>
            <!--
              深色模式开关放顶栏头像下拉(桌面端)——比「设置页下半张外观卡」更顺眼,
              切完即时生效(localStorage ys-theme + <html data-theme>),刷新不丢。
              移动端无此项(头像在 TabBar「我的」,深色走 Settings.vue「我的」页的外观卡)。
            -->
            <el-dropdown-item
              v-if="!isMobile"
              :command="isDark ? 'toggleLight' : 'toggleDark'"
            >
              <el-icon><Sunny v-if="!isDark" /><Moon v-else /></el-icon>
              {{ isDark ? '切换浅色' : '切换深色' }}
            </el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Plus, Search, Bell, CaretBottom, Sunny, Moon } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useIsMobile } from '@/composables/useBreakpoint'
import { useTheme } from '@/composables/useTheme'
import { resolveUrl } from '@/utils/resolve'
import UnreadDot from '@/components/mobile/UnreadDot.vue'

const props = defineProps({
  showBack: { type: Boolean, default: false },
  pageTitle: { type: String, default: '' },
  notifyUnread: { type: Number, default: 0 },
})

const emit = defineEmits(['logout'])

const router = useRouter()
const userStore = useUserStore()
const isMobile = useIsMobile()
/** 深色模式开关(桌面头像下拉项):与 Settings / SettingsProfile 共享 useTheme 单例,
 *  切完落 localStorage + <html data-theme>,即时生效、刷新不丢。 */
const { isDark, toggle: toggleTheme } = useTheme()

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

function onUserCommand(cmd) {
  if (cmd === 'profile') {
    // userInfo 可能尚未就绪(后台 fetchMe 还在飞),此时 id 为 undefined,
    // 拼成 /user/undefined 这种坏 URL,vue-router 推它会在 <Transition> 换页
    // 渲染时崩掉整片 <router-view>(parentNode 为 null 的 patch 错)→ 白屏。
    // 拿不到 id 就静默不动,绝不让一次坏导航打断全站渲染。
    const id = userStore.userInfo?.id
    if (id != null) router.push(`/user/${id}`).catch(() => {})
  } else if (cmd === 'settings') {
    // 桌面(≥1024)落真正的设置页 /settings/profile(编辑资料 + 隐私);
    // 移动端「我的」(/settings)是唯一能挂管理入口/退出/审核记录的地方,保留现状。
    router.push(isMobile.value ? '/settings' : '/settings/profile').catch(() => {})
  } else if (cmd === 'toggleDark' || cmd === 'toggleLight') {
    // 桌面端一键切主题:同一份 useTheme 单例,切完即时落类 + 持久化,无需跳转
    toggleTheme()
  } else if (cmd === 'logout') emit('logout')
}
</script>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: var(--ys-z-sticky);
  height: var(--ys-topbar-h);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-2);
  padding: 0 var(--ys-space-4);
  background: var(--ys-surface);
  border-bottom: 1px solid var(--ys-border);
  transition: box-shadow var(--ys-motion-fast) var(--ys-ease-out);
}

.topbar__left,
.topbar__right {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  min-width: 0;
}

.topbar__brand {
  font-size: var(--ys-text-lg);
  line-height: var(--ys-lh-lg);
  letter-spacing: var(--ys-ls-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  padding-left: var(--ys-space-2);
}

.topbar__title {
  margin: 0;
  /* 1 行截断:标题再长也不能把右侧图标挤出屏幕 */
  max-width: 52vw;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--ys-text-lg);
  line-height: var(--ys-lh-lg);
  letter-spacing: var(--ys-ls-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.topbar__icon-btn {
  position: relative;
  /* 热区 44x44,AC-15 硬要求 */
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  border-radius: var(--ys-radius-lg);
  cursor: pointer;
  /* 禁止 outline:none,焦点环由全局 :focus-visible 提供 */
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out),
    opacity var(--ys-motion-instant) var(--ys-ease-out);
}

.topbar__icon-btn:hover {
  background: var(--ys-surface-2);
}

.topbar__icon-btn:active {
  opacity: 0.7;
}

.topbar__avatar-wrap {
  display: none;
}

.topbar__avatar-trigger {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  height: var(--ys-touch-min);
  padding: 0 var(--ys-space-1);
  border: none;
  background: transparent;
  border-radius: var(--ys-radius-lg);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.topbar__avatar-trigger:hover {
  background: var(--ys-surface-2);
}

.topbar__avatar-fallback {
  color: var(--ys-fg-2);
  font-size: var(--ys-text-sm);
}

.topbar__caret {
  font-size: 12px;
  color: var(--ys-muted);
}

/*
 * 手机端隐藏发布按钮(TabBar 已有),头像也隐藏(在「我的」里)。
 * 桌面端(≥1024px)把发布按钮放回右端、显示头像下拉。
 * 断点用字面量:本项目无 postcss-custom-media,`@media (--ys-bp-lg)` 会被
 * 浏览器静默丢弃。值 1024 = design-tokens.json 的 breakpoint.lg。
 */
@media (max-width: 1023px) {
  .topbar__publish {
    display: none;
  }

  /* 「找人」只留桌面:移动端 feed 已有搜索框、/search 是主入口,顶栏右侧只留铃铛 */
  .topbar__search {
    display: none;
  }
}

@media (min-width: 1024px) {
  .topbar {
    padding: 0 var(--ys-gutter-desktop);
  }

  .topbar__brand {
    display: none;
  }

  .topbar__publish {
    display: grid;
    background: var(--ys-accent);
    color: var(--ys-accent-on);
    /* 原为彩色发光 0 4px 12px rgba(64,158,255,.35),命中红线;改用 --ys-shadow-2 */
    box-shadow: var(--ys-shadow-2);
  }

  .topbar__publish:hover {
    background: var(--ys-accent-hover);
  }

  .topbar__avatar-wrap {
    display: block;
  }
}
</style>