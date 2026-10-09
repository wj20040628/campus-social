import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import MainLayout from '@/layouts/MainLayout.vue'
import AuthLayout from '@/layouts/AuthLayout.vue'

/**
 * 路由表
 * ------------------------------------------------------------------
 * meta 字段(依据 MainLayout.vue.md §1.1):
 *   title       浏览器标题 + 移动端顶栏标题
 *   back        true = 顶栏用「返回型」(左箭头 + 标题);不填 = 品牌型(左「友声」)
 *   hideTabBar  true = 隐藏底部 TabBar,内容区底部留白随之改变。
 *               用于会话页、帖子详情、发布页、全屏编辑器 —— 这些页面自己
 *               要占满底部(输入区 / 吸底操作栏),再叠一个 56px TabBar,
 *               在 iPhone 上会把可用高度压到半屏以下。
 *
 * 断点纪律:本页不出现任何与断点有关的逻辑。EP 的 el-col 响应式属性
 * (:xs/:sm/:md/:lg/:xl)在本项目**禁止使用** —— EP 内置断点
 * (768/992/1200/1920)与设计 Token(640/768/1024/1280/1536)完全错位。
 */
const routes = [
  {
    path: '/login',
    component: AuthLayout,
    children: [
      { path: '', redirect: '/login/form' },
      { path: 'form', name: 'login', component: () => import('@/views/Login.vue'), meta: { title: '登录' } },
      { path: 'register', name: 'register', component: () => import('@/views/Register.vue'), meta: { title: '注册' } },
    ],
  },
  {
    path: '/',
    component: MainLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/Home.vue'),
        meta: { requiresAuth: true, title: '首页' },
      },
      {
        path: 'publish',
        name: 'publish',
        component: () => import('@/views/Publish.vue'),
        meta: { requiresAuth: true, title: '发一条', back: true, hideTabBar: true },
      },
      {
        path: 'post/:id',
        name: 'postDetail',
        component: () => import('@/views/PostDetail.vue'),
        meta: { requiresAuth: true, title: '帖子详情', back: true, hideTabBar: true },
      },
      {
        path: 'post/:id/edit',
        name: 'postEdit',
        component: () => import('@/views/Publish.vue'),
        meta: { requiresAuth: true, title: '改一下', back: true, hideTabBar: true },
      },

      /* ---- 会话:列表页与单栏会话页分成两条路由(SPEC §9 AC-16) ---- */
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/Chat.vue'),
        meta: { requiresAuth: true, title: '消息' },
      },
      {
        path: 'chat/t/:kind/:id',
        name: 'chatThread',
        component: () => import('@/views/Chat.vue'),
        meta: { requiresAuth: true, title: '会话', back: true, hideTabBar: true },
      },

      {
        path: 'notify',
        name: 'notify',
        component: () => import('@/views/Notify.vue'),
        meta: { requiresAuth: true, title: '通知', back: true },
      },
      {
        path: 'user/:id',
        name: 'userProfile',
        component: () => import('@/views/Profile.vue'),
        meta: { requiresAuth: true, title: '个人主页', back: true },
      },
      {
        path: 'topic/:name',
        name: 'topic',
        component: () => import('@/views/TopicDetail.vue'),
        meta: { requiresAuth: true, title: '话题', back: true },
      },
      {
        path: 'settings',
        name: 'settings',
        component: () => import('@/views/Settings.vue'),
        meta: { requiresAuth: true, title: '我的' },
      },
      {
        path: 'circles',
        name: 'circles',
        component: () => import('@/views/Circles.vue'),
        meta: { requiresAuth: true, title: '圈子' },
      },
      {
        path: 'circles/:id',
        name: 'circleDetail',
        component: () => import('@/views/CircleDetail.vue'),
        meta: { requiresAuth: true, title: '圈子详情', back: true },
      },
      {
        path: 'activity/:id',
        name: 'activityDetail',
        component: () => import('@/views/ActivityDetail.vue'),
        meta: { requiresAuth: true, title: '活动详情', back: true },
      },
      {
        path: 'search',
        name: 'search',
        component: () => import('@/views/Search.vue'),
        meta: { requiresAuth: true, title: '找人', back: true },
      },

      /* ---- 官方公告(F5) ---- */
      {
        path: 'announcement',
        name: 'announcement',
        component: () => import('@/views/Announcement.vue'),
        meta: { requiresAuth: true, title: '公告', back: true },
      },
      {
        path: 'announcement/:id',
        name: 'announcementDetail',
        component: () => import('@/views/AnnouncementDetail.vue'),
        meta: { requiresAuth: true, title: '公告详情', back: true, hideTabBar: true },
      },

      /* ---- 新用户引导(F11):第 1 步不可跳过(AC-18),故不给 back ---- */
      {
        path: 'onboarding',
        name: 'onboarding',
        component: () => import('@/views/Onboarding.vue'),
        // ownTop:引导页顶栏是「进度条 + 步骤指示 + 跳过」,
        // 叠在全站顶栏下面会变成两行 56px,把内容区压到 300px 以下。
        meta: { requiresAuth: true, title: '完善资料', hideTabBar: true, ownTop: true },
      },

      /* ---- 后台:手机端同样可用(OD-19) ---- */
      {
        /*
          学号核验审核。
          独立页而不是 AdminReview 的第四个 Tab —— 理由写在 AdminVerify.vue
          头部(行数门禁 + 身份处置与内容处置语义不同 + 契约分属两个 tag)。
        */
        path: 'admin/verify',
        name: 'verify',
        component: () => import('@/views/AdminVerify.vue'),
        meta: { requiresAuth: true, requiresAdmin: true, title: '学号核验', back: true },
      },
      {
        path: 'admin/review',
        name: 'review',
        component: () => import('@/views/AdminReview.vue'),
        meta: { requiresAuth: true, requiresAdmin: true, title: '内容审核', back: true },
      },
      {
        path: 'admin/audit',
        name: 'audit',
        component: () => import('@/views/AdminAudit.vue'),
        meta: { requiresAuth: true, requiresAdmin: true, title: '操作日志', back: true },
      },
      {
        path: 'admin/announcement',
        name: 'adminAnnouncement',
        component: () => import('@/views/AdminAnnouncement.vue'),
        meta: { requiresAuth: true, requiresAdmin: true, title: '公告管理', back: true, hideTabBar: true },
      },
      {
        // 公告编辑器:全屏页面而非抽屉(标题+正文+分类+置顶,字段多抽屉装不下)。
        // 不设 back —— 退出只能走顶栏「取消」,防止误触返回丢内容。
        // ownTop:编辑器自带「取消 / 新建公告 / 发布」顶栏。
        path: 'admin/announcement/edit/:id?',
        name: 'adminAnnouncementEdit',
        component: () => import('@/views/AdminAnnouncementEdit.vue'),
        meta: { requiresAuth: true, requiresAdmin: true, title: '写公告', hideTabBar: true, ownTop: true },
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局守卫:未登录跳登录页,带 returnUrl;管理员页面校验权限
import { ElMessage } from '@/utils/feedback'

router.beforeEach((to, from, next) => {
  const user = useUserStore()

  // 未登录 -> 登录页,带 returnUrl 便于登录后跳回
  if (to.meta.requiresAuth && !user.hasToken) {
    next({ path: '/login/form', query: { returnUrl: to.fullPath } })
    return
  }

  // 已登录访问登录/注册页 → 跳回 returnUrl
  if (user.hasToken && (to.name === 'login' || to.name === 'register')) {
    next(to.query.returnUrl || '/')
    return
  }

  // 管理员页面:非管理员拦回首页(AC-20 的前端一半。
  // 注意:后端仍必须独立返回 403 NOT_ADMIN —— 前端拦截不能替代服务端鉴权,
  // 直接改 URL/用 curl 绕过前端守卫是必须守住的底线。)
  if (to.meta.requiresAdmin && !user.isAdmin) {
    ElMessage.warning('这个页面只有管理员能看')
    next('/')
    return
  }

  next()
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · 友声` : '友声 · 校园生活'
})

export default router