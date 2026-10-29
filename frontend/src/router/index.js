import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import MainLayout from '@/layouts/MainLayout.vue'
import AuthLayout from '@/layouts/AuthLayout.vue'
import adminRoutes from './admin'

/**
 * 路由表
 * ------------------------------------------------------------------
 * meta 字段:title(标题)、back(返回型顶栏)、hideTabBar(隐藏底栏)、
 * ownTop(自绘顶栏)、requiresAuth/requiresAdmin(守卫)。
 * 断点纪律:本页禁止任何断点逻辑;EP 的 el-col 响应式属性与设计 Token 错位,禁用。
 */
const routes = [
  {
    path: '/login',
    component: AuthLayout,
    children: [
      /*
       * 用**函数式 redirect** 而不是字符串 '/login/form'（2026-10-21 修）。
       *
       * 字符串 redirect 会**丢掉 query**：request.js 在 401 时跳的是
       * `/login?returnUrl=<原页面>`，进到 /login 后被字符串重定向成
       * `/login/form`，returnUrl 就此丢失。于是每次登录过期重新登录后
       * 都只能回首页，而不是回到用户原本在的那一页 ——
       * 对「正在写东西被踢出去」的场景尤其难受。
       *
       * 函数式 redirect 能拿到完整的 to 对象，把 query 原样带过去。
       */
      { path: '', redirect: (to) => ({ path: '/login/form', query: to.query }) },
      { path: 'form', name: 'login', component: () => import('@/views/Login.vue'), meta: { title: '登录' } },
      { path: 'register', name: 'register', component: () => import('@/views/Register.vue'), meta: { title: '注册' } },
    ],
  },

  /* ---- 协议独立页（2026-10-20）：备案/外链用，公开可访问、无需登录 ----
     与注册页弹窗共用 @/constants/legalDocs 同一份文本；props 静态传入 doc 决定渲染哪一份。 */
  {
    path: '/agreement',
    name: 'userAgreement',
    component: () => import('@/views/LegalPage.vue'),
    props: { doc: 'user' },
    meta: { title: '用户协议' },
  },
  {
    path: '/privacy',
    name: 'privacyPolicy',
    component: () => import('@/views/LegalPage.vue'),
    props: { doc: 'privacy' },
    meta: { title: '隐私政策' },
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
        // ownTop:Publish 自带「< 发动态」顶栏(编辑模式的取消就是它),
        // 不加 ownTop 会叠出双顶栏双返回(实测截图确认)。
        meta: { requiresAuth: true, title: '发一条', back: true, hideTabBar: true, ownTop: true },
      },
      {
        path: 'post/:id',
        name: 'postDetail',
        component: () => import('@/views/PostDetail.vue'),
        // ownTop:PostDetail 自带「< 帖子详情 + 更多(...)」顶栏,理由同上。
        // 免登录(2026-10-25 分享卡片):二维码扫码进来的游客必须能看内容,
        // 否则分享等于白发;后端已对 GET 详情/评论放游客视角。
        meta: { title: '帖子详情', back: true, hideTabBar: true, ownTop: true },
      },
      {
        path: 'post/:id/edit',
        name: 'postEdit',
        component: () => import('@/views/Publish.vue'),
        meta: { requiresAuth: true, title: '改一下', back: true, hideTabBar: true, ownTop: true },
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
        // ownTop:true = 会话页自绘顶栏(显示对方/群名),不渲染全站顶栏。
        // 全站顶栏标题是路由 meta 写死的「会话」,看不出在和谁聊;
        // 会话页顶栏内容与聊天数据同源(ChatThread 自绘),见 ChatThread.vue .th__bar。
        meta: { requiresAuth: true, title: '会话', back: true, hideTabBar: true, ownTop: true },
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
        /*
          ownTop:个人主页改成抖音/快手式沉浸版式 —— 封面图整屏铺到状态栏下方,
          不能有 52px 的纯色导航条把它截断。关掉全局顶栏后,「更多」按钮
          由 Profile 自己画在封面上(点击仍唤起同一个全局抽屉)。
          注意:ownTop 只是不渲染 TopBar,抽屉本体还在 MainLayout 里,
          通过 provide('openDrawer') 交给页面调用。
        */
        meta: { requiresAuth: true, title: '个人主页', back: true, ownTop: true },
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
        meta: { requiresAuth: true, title: '设置' },
      },
      {
        // 编辑资料独立页:/profile(不再挂在 /settings 下)。
        // ownTop:自绘顶栏(返回+编辑资料),不叠全站顶栏;hideTabBar 让表单独占底部。
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/SettingsProfile.vue'),
        meta: { requiresAuth: true, title: '编辑资料', back: true, hideTabBar: true, ownTop: true },
      },
      {
        // 兼容旧地址:编辑资料曾挂在 /settings/profile,重定向到 /profile。
        path: 'settings/profile',
        redirect: '/profile',
      },
      {
        path: 'circles',
        name: 'circles',
        component: () => import('@/views/Circles.vue'),
        // headTitle(C20):顶栏左上角写页面名「圈子」,不写品牌「友声」
        meta: { requiresAuth: true, title: '圈子', headTitle: '圈子' },
      },
      {
        path: 'circles/:id',
        name: 'circleDetail',
        component: () => import('@/views/CircleDetail.vue'),
        meta: { requiresAuth: true, title: '圈子详情', back: true },
      },
      {
        /*
         * 圈子成员页（2026-10-21 新增）。
         * 独立页而不是弹窗：成员可能几十人，弹窗里滚动很难受；
         * 而「看成员」是会反复回来看的动作（找人、看谁在），
         * 独立页可以返回、也能被浏览器记住位置。
         */
        path: 'circles/:id/members',
        name: 'circleMembers',
        component: () => import('@/views/CircleMembers.vue'),
        meta: { requiresAuth: true, title: '圈子成员', back: true },
      },
      {
        path: 'activity/:id',
        name: 'activityDetail',
        component: () => import('@/views/ActivityDetail.vue'),
        // 免登录(2026-10-25 分享卡片):活动详情与报名名单后端已放游客 GET
        meta: { title: '活动详情', back: true },
      },

      /* ---- 校园限时活动集市（mkt）：广场+我的发布 / 发布页 / 详情 ---- */
      {
        path: 'mkt',
        name: 'mkt',
        component: () => import('@/views/MktPlaza.vue'),
        meta: { requiresAuth: true, title: '集市' },
      },
      {
        path: 'mkt/publish',
        name: 'mktPublish',
        component: () => import('@/views/MktPublish.vue'),
        // ownTop:发布页自带「< 发布活动」顶栏 + 吸底提交栏,与 Publish 同理由。
        meta: { requiresAuth: true, title: '发布集市活动', back: true, hideTabBar: true, ownTop: true },
      },
      {
        path: 'mkt/:id',
        name: 'mktDetail',
        component: () => import('@/views/MktDetail.vue'),
        // 免登录(2026-10-25 分享卡片):详情与留言后端已放游客 GET
        meta: { title: '活动详情', back: true, hideTabBar: true },
      },

      /* ---- 文件库(2026-10-27):全部/我的、上传、详情、公开分享 ---- */
      {
        path: 'files',
        name: 'files',
        component: () => import('@/views/files/FileList.vue'),
        meta: { requiresAuth: true, title: '文件', headTitle: '文件' },
      },
      {
        path: 'files/upload',
        name: 'fileUpload',
        component: () => import('@/views/files/FileUpload.vue'),
        meta: { requiresAuth: true, title: '上传文件', back: true, hideTabBar: true },
      },
      {
        path: 'files/:id',
        name: 'fileDetail',
        component: () => import('@/views/files/FileDetail.vue'),
        meta: { requiresAuth: true, title: '文件详情', back: true, hideTabBar: true },
      },
      {
        // 公开分享页:未注册同学扫码/点链接也要能看
        path: 'f/:id',
        name: 'fileShare',
        component: () => import('@/views/files/FileShare.vue'),
        meta: { title: '文件分享', back: true, hideTabBar: true },
      },
      {
        path: 'search',
        name: 'search',
        component: () => import('@/views/Search.vue'),
        meta: { requiresAuth: true, title: '找人', back: true },
      },

      /* ---- 发现:按兴趣找同好 + 热门圈子(计划 §6 场景3) ---- */
      {
        path: 'discover',
        name: 'discover',
        component: () => import('@/views/Discover.vue'),
        meta: { requiresAuth: true, title: '发现' },
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

      /* ---- 后台:手机端同样可用(OD-19);路由块外置在 ./admin.js ---- */
      ...adminRoutes,

      /* ---- 设置二级页(2026-10-18 改版:分类首页 + 二级页) ---- */
      {
        path: 'settings/privacy',
        name: 'settingsPrivacy',
        component: () => import('@/views/settings/SettingsPrivacy.vue'),
        meta: { requiresAuth: true, title: '隐私设置', back: true },
      },
      {
        path: 'settings/general',
        name: 'settingsGeneral',
        component: () => import('@/views/settings/SettingsGeneral.vue'),
        meta: { requiresAuth: true, title: '通用设置', back: true },
      },
      {
        path: 'settings/security',
        name: 'settingsSecurity',
        component: () => import('@/views/settings/SettingsSecurity.vue'),
        meta: { requiresAuth: true, title: '账号与安全', back: true },
      },
      {
        path: 'settings/about',
        name: 'settingsAbout',
        component: () => import('@/views/settings/SettingsAbout.vue'),
        meta: { requiresAuth: true, title: '关于我们', back: true },
      },
      {
        path: 'settings/notify',
        name: 'settingsNotify',
        component: () => import('@/views/settings/SettingsNotify.vue'),
        meta: { requiresAuth: true, title: '通知设置', back: true },
      },
    ],
  },
  /*
   * catch-all 兜底(2026-10-23 加,审查 P1-33):
   * 之前没有任何兜底路由,拼错的 URL / 后端消息里的旧链接 / 失效分享链接
   * 会渲染空 router-view —— 纯白页、无提示,用户以为站挂了。SPA 必须有兜底。
   */
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  /**
   * 滚动恢复:后退/前进(browser back 或页面返回键)时回到离开时的位置,
   * 新导航回顶部。
   *
   * 例外:后退到首页(name==='home')时返回 false 不插手 —— 首页由 keep-alive
   * 缓存,滚动位置交给 Home 组件自己的 onDeactivated/onActivated 自管
   * (savedPosition 与缓存组件的配合时机不可靠,实测返回位置不准,且会与
   * Home 自管恢复互相覆盖)。新导航到首页(savedPosition 为空)仍回顶部。
   */
  scrollBehavior(to, from, savedPosition) {
    if (to.name === 'home' && savedPosition) return false
    return savedPosition || { top: 0 }
  },
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