/**
 * 管理后台路由块(2026-10-18 从 index.js 抽出)。
 * index.js 已贴着 300 行门禁,设置二级页要加路由,把语义独立的
 * admin 块外置 —— 路由守卫(requiresAdmin)仍在 index.js 统一执行。
 */
const adminRoutes = [
  {
    /*
      校园信息核对（只影响资料标记，不影响功能权限）。
      独立页而不是 AdminReview 的第四个 Tab —— 理由写在 AdminVerify.vue
      头部(行数门禁 + 资料核对与内容处置语义不同 + 契约分属两个 tag)。
    */
    path: 'admin/verify',
    name: 'verify',
    component: () => import('@/views/AdminVerify.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '校园信息核对', back: true },
  },
  {
    /*
      意见反馈审核。独立页而非挂到 AdminVerify 的 Tab 下 ——
      反馈是「对产品的建议」,与身份核验/内容审核都不是一类事,
      混在一起会让那两页的行数门禁与筛选逻辑都变复杂。
    */
    path: 'admin/feedback',
    name: 'adminFeedback',
    component: () => import('@/views/AdminFeedback.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '意见反馈', back: true },
  },
  {
    /* 文件审核(2026-10-27):待审/通过/驳回/下架,队列就是 file_asset.status */
    path: 'admin/files',
    name: 'adminFiles',
    component: () => import('@/views/AdminFiles.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '文件审核', back: true },
  },
  {
    /*
      用户处置(2026-10-24 新增):找人 / 直接封禁 / 手动解封。
      此前封人只能走举报流程、解封没有任何端点——永久封禁误判只能改库。
      独立页:身份处置(封账号)与内容处置(审核帖子)语义不同,不塞进 AdminReview。
    */
    path: 'admin/users',
    name: 'adminUsers',
    component: () => import('@/views/AdminUsers.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '用户处置', back: true },
  },
  {
    /* 举报处置独立页(2026-10-25 从 AdminReview 拆出):时效优先,默认落点 */
    path: 'admin/reports',
    name: 'adminReports',
    component: () => import('@/views/AdminReports.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '举报处置', back: true },
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
    /*
      圈子官方名保护 + 管理员处置(两个 Tab:圈子处置 / 预留名)。
      移交圈主 / 封禁圈子 / 维护 reserved_circle_name。
    */
    path: 'admin/circles',
    name: 'adminCircles',
    component: () => import('@/views/AdminCircles.vue'),
    meta: { requiresAuth: true, requiresAdmin: true, title: '圈子处置', back: true },
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
]

export default adminRoutes
