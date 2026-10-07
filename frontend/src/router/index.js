import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import MainLayout from '@/layouts/MainLayout.vue'
import AuthLayout from '@/layouts/AuthLayout.vue'

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
      { path: '', name: 'home', component: () => import('@/views/Home.vue'), meta: { requiresAuth: true, title: '首页' } },
      { path: 'publish', name: 'publish', component: () => import('@/views/Publish.vue'), meta: { requiresAuth: true, title: '发动态' } },
      { path: 'post/:id', name: 'postDetail', component: () => import('@/views/PostDetail.vue'), meta: { requiresAuth: true, title: '帖子详情' } },
      { path: 'post/:id/edit', name: 'postEdit', component: () => import('@/views/Publish.vue'), meta: { requiresAuth: true, title: '编辑动态' } },
      { path: 'chat', name: 'chat', component: () => import('@/views/Chat.vue'), meta: { requiresAuth: true, title: '私信' } },
      { path: 'notify', name: 'notify', component: () => import('@/views/Notify.vue'), meta: { requiresAuth: true, title: '通知' } },
      { path: 'user/:id', name: 'userProfile', component: () => import('@/views/Profile.vue'), meta: { requiresAuth: true, title: '个人主页' } },
      { path: 'topic/:name', name: 'topic', component: () => import('@/views/TopicDetail.vue'), meta: { requiresAuth: true, title: '话题' } },
      { path: 'settings', name: 'settings', component: () => import('@/views/Settings.vue'), meta: { requiresAuth: true, title: '设置' } },
      { path: 'circles', name: 'circles', component: () => import('@/views/Circles.vue'), meta: { requiresAuth: true, title: '圈子' } },
      { path: 'circles/:id', name: 'circleDetail', component: () => import('@/views/CircleDetail.vue'), meta: { requiresAuth: true, title: '圈子详情' } },
      { path: 'activity/:id', name: 'activityDetail', component: () => import('@/views/ActivityDetail.vue'), meta: { requiresAuth: true, title: '活动详情' } },
      { path: 'search', name: 'search', component: () => import('@/views/Search.vue'), meta: { requiresAuth: true, title: '搜索' } },
      { path: 'admin/review', name: 'review', component: () => import('@/views/AdminReview.vue'), meta: { requiresAuth: true, requiresAdmin: true, title: '内容审核' } },
      { path: 'admin/audit', name: 'audit', component: () => import('@/views/AdminAudit.vue'), meta: { requiresAuth: true, requiresAdmin: true, title: '操作日志' } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局守卫:未登录跳登录页,带 returnUrl;管理员页面校验权限
import { ElMessage } from 'element-plus'
router.beforeEach((to, from, next) => {
  const user = useUserStore()
  if (to.meta.requiresAuth && !user.hasToken) {
    next({ path: '/login/form', query: { returnUrl: to.fullPath } })
    return
  }
  // 已登录访问登录/注册页 → 跳回 returnUrl
  if (user.hasToken && (to.name === 'login' || to.name === 'register')) {
    next(to.query.returnUrl || '/')
    return
  }
  // 管理员页面:非管理员重定向首页 + 提示
  if (to.meta.requiresAdmin && !user.isAdmin) {
    ElMessage.warning('需要管理员权限')
    next('/')
    return
  }
  next()
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · 友声` : '友声 · 校园生活'
})

export default router
