<template>
  <!--
    桌面侧栏(≥1024px)。手机端整个组件 display:none,不进入可聚焦树。
    保留 el-menu —— MainLayout 是「加 TabBar」,不是「重写导航」
    (mobile-shell.md §2.7 明确要求不删除 el-menu)。
  -->
  <aside class="side">
    <div class="brand">友声</div>
    <el-menu :default-active="activeMenu" router class="side__menu">
      <!--
        2026-10-21 分组改造。改版前 7 个普通项 + 6 个管理子项一路平铺，
        浏览 / 个人 / 管理三种性质混在一列，只能靠图标区分。
        现在按「我要干什么」分三段：浏览（去哪儿看）/ 我的（关于我的）/
        管理后台（管理员才有）。

        分组标题只写文字、不可点 —— el-menu 里塞不进裸 div，
        用 el-menu-item 的 disabled 态承载：它不响应点击、不参与路由，
        且保留 el-menu 的键盘导航语义。
      -->
      <el-menu-item index="__grp_browse__" disabled class="side__grp">浏览</el-menu-item>
      <el-menu-item index="/">
        <el-icon><House /></el-icon>
        <span>首页</span>
      </el-menu-item>
      <el-menu-item index="/circles">
        <el-icon><ChatDotSquare /></el-icon>
        <span>圈子</span>
      </el-menu-item>
      <el-menu-item index="/mkt">
        <el-icon><OfficeBuilding /></el-icon>
        <span>集市</span>
      </el-menu-item>
      <el-menu-item index="/files">
        <el-icon><FolderOpened /></el-icon>
        <span>文件</span>
      </el-menu-item>

      <el-menu-item index="__grp_mine__" disabled class="side__grp">我的</el-menu-item>
      <el-menu-item index="/chat">
        <el-icon><ChatDotRound /></el-icon>
        <span>消息</span>
        <!-- 未读角标：桌面端此前完全没有这个数字，必须点进去才知道有几条 -->
        <span v-if="chatUnread > 0" class="side__badge ys-num">{{ chatUnread > 99 ? '99+' : chatUnread }}</span>
      </el-menu-item>
      <el-menu-item index="/notify">
        <el-icon><Bell /></el-icon>
        <span>通知</span>
        <span v-if="notifyUnread > 0" class="side__badge ys-num">{{ notifyUnread > 99 ? '99+' : notifyUnread }}</span>
      </el-menu-item>
      <el-menu-item index="/announcement">
        <el-icon><Promotion /></el-icon>
        <span>公告</span>
      </el-menu-item>

      <!--
        「发现」已从桌面左栏移除(2026-10-19):顶栏已有「找人」入口(/search),
        /discover 页面本身保留(个人主页标签区仍可进),只是不再占侧栏位置。

        「发动态」也已移出侧栏(2026-10-21):它是**动作**不是页面,
        顶栏右侧有常驻的「发动态」主按钮,侧栏再占一格等于同一件事两个入口,
        而且切碎了「浏览 / 我的 / 管理」的分组结构。
      -->

      <!--
        管理员项:isAdmin 为 false 时整块不渲染,不给用户一个点进去才被拒的入口。
        收进 el-sub-menu「管理后台」子菜单:桌面侧栏 hover 展开,
        管理员一眼看到分组,不占主列高度(避免 6 普通 + 4 管理平铺一屏滚到底)。
        el-menu 内部不能塞裸 div,用 el-sub-menu 包一层。
      -->
      <el-sub-menu v-if="userStore.isAdmin" index="__admin__">
        <template #title>
          <el-icon><Lock /></el-icon>
          <span>管理后台</span>
          <!-- 子菜单收起时也要能看见有活：总数挂在外层，不必 hover 才发现 -->
          <AdminPendingBadge :count="pendingTotal" class="side__sub-badge" />
        </template>
        <!--
          学号核验排第一,不是按字母也不是按开发顺序:
          认证是其他一切能力的前置(没过认证的用户不能搭话、不能发公告),
          一堆人卡在「审核中」时它是管理员唯一有盼头的那件事。
        -->
        <el-menu-item
          index="/admin/verify"
          :class="{ 'is-pending': pending.summary.verifyPending > 0 }"
        >
          <el-icon><Postcard /></el-icon>
          <span>学号核验</span>
          <AdminPendingBadge :count="pending.summary.verifyPending" class="side__pending" />
        </el-menu-item>
        <!-- 举报有时效,排最前(2026-10-25 举报拆独立页后与机审分开计数) -->
        <el-menu-item
          index="/admin/reports"
          :class="{ 'is-pending': pending.summary.reportPending > 0 }"
        >
          <el-icon><DocumentChecked /></el-icon>
          <span>举报处置</span>
          <AdminPendingBadge :count="pending.summary.reportPending" class="side__pending" />
        </el-menu-item>
        <el-menu-item
          index="/admin/review"
          :class="{ 'is-pending': pending.summary.reviewPending > 0 }"
        >
          <el-icon><DocumentChecked /></el-icon>
          <span>内容审核</span>
          <AdminPendingBadge :count="pending.summary.reviewPending" class="side__pending" />
        </el-menu-item>
        <el-menu-item
          index="/admin/files"
          :class="{ 'is-pending': pending.summary.filePending > 0 }"
        >
          <el-icon><FolderOpened /></el-icon>
          <span>文件审核</span>
          <AdminPendingBadge :count="pending.summary.filePending" class="side__pending" />
        </el-menu-item>
        <!-- 用户处置(2026-10-24 新增):找人 / 直接封禁 / 手动解封 -->
        <el-menu-item index="/admin/users">
          <el-icon><UserFilled /></el-icon>
          <span>用户处置</span>
        </el-menu-item>
        <el-menu-item index="/admin/announcement">
          <el-icon><EditPen /></el-icon>
          <span>公告管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/audit">
          <el-icon><List /></el-icon>
          <span>操作日志</span>
        </el-menu-item>
        <el-menu-item index="/admin/circles">
          <el-icon><Collection /></el-icon>
          <span>圈子处置</span>
        </el-menu-item>
        <!-- 反馈排最后:它是「改进建议」不是「必须处理的违规」,不占管理员的日常动线 -->
        <el-menu-item
          index="/admin/feedback"
          :class="{ 'is-pending': pending.summary.feedbackPending > 0 }"
        >
          <el-icon><ChatLineSquare /></el-icon>
          <span>意见反馈</span>
          <AdminPendingBadge :count="pending.summary.feedbackPending" class="side__pending" />
        </el-menu-item>
      </el-sub-menu>
    </el-menu>
  </aside>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import {
  House, ChatDotSquare, EditPen, ChatDotRound, Promotion,
  Bell, DocumentChecked, List, Postcard, Lock, OfficeBuilding, Collection,
  ChatLineSquare, UserFilled, FolderOpened,
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useAdminPending } from '@/composables/useAdminPending'
import AdminPendingBadge from '@/components/admin/AdminPendingBadge.vue'

const props = defineProps({
  routePath: { type: String, required: true },
  /** 通知未读数，由 MainLayout 传入（与顶栏铃铛同源，不重复请求） */
  notifyUnread: { type: Number, default: 0 },
  /**
   * 消息未读数（待回应的搭话纸条数）。
   * 复用 MainLayout 已经拿到的 paperCount —— 桌面端此前侧栏完全没有角标，
   * 用户看不到「有几条新消息」，必须点进去才知道。
   */
  chatUnread: { type: Number, default: 0 },
})

const userStore = useUserStore()

/* 管理待办角标:与 AppDrawer 共用模块级缓存,挂载时拉一次(2026-10-24 新增) */
const pending = useAdminPending()
const pendingTotal = computed(() => pending.total())
onMounted(() => pending.refresh())

const activeMenu = computed(() => {
  const p = props.routePath
  if (p.startsWith('/admin/verify')) return '/admin/verify'
  if (p.startsWith('/admin/announcement')) return '/admin/announcement'
  if (p.startsWith('/admin/review')) return '/admin/review'
  if (p.startsWith('/admin/files')) return '/admin/files'
  if (p.startsWith('/admin/reports')) return '/admin/reports'
  if (p.startsWith('/admin/users')) return '/admin/users'
  if (p.startsWith('/admin/audit')) return '/admin/audit'
  if (p.startsWith('/admin/circles')) return '/admin/circles'
  if (p.startsWith('/admin/feedback')) return '/admin/feedback'
  if (p.startsWith('/chat')) return '/chat'
  if (p.startsWith('/notify')) return '/notify'
  if (p.startsWith('/discover')) return '/discover'
  if (p.startsWith('/announcement')) return '/announcement'
  if (p.startsWith('/circles')) return '/circles'
  if (p.startsWith('/activity')) return '/circles'
  if (p.startsWith('/mkt')) return '/mkt'
  if (p.startsWith('/files')) return '/files'
  /*
   * /publish 不再有侧栏菜单项（发动态已移进顶栏按钮，2026-10-21）。
   * 但它在语义上属于首页动线，落到「首页」高亮比整列全灭更合理。
   */
  if (p.startsWith('/publish')) return '/'
  if (p.startsWith('/post')) return '/'
  if (p.startsWith('/user')) return '/'
  return '/'
})
</script>

<style scoped>
.side {
  /* 不用 el-aside 的 width 属性硬编码 220px —— 属性写死媒体查询改不掉 */
  width: var(--ys-sidebar-w);
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  background: var(--ys-surface);
  border-right: 1px solid var(--ys-border);
}

.brand {
  padding: var(--ys-space-5) var(--ys-space-6);
  font-size: var(--ys-text-xl);
  line-height: var(--ys-lh-xl);
  letter-spacing: var(--ys-ls-xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.side__menu {
  border-right: none;
  flex: 1;
  background: transparent;
}

/* 菜单项文字 1 行截断,防止长标题把侧栏撑宽 */
.side__menu :deep(.el-menu-item span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 分组标题（2026-10-21）。用 disabled 的 el-menu-item 承载：
 * 不响应点击、不参与路由，但保留 el-menu 的 DOM 与键盘语义。
 * 视觉上要「不像菜单项」：无悬停底色、字号更小、加字距、颜色更淡。
 */
.side__menu :deep(.el-menu-item.is-disabled.side__grp) {
  height: 28px;
  line-height: 28px;
  margin-top: var(--ys-space-2);
  padding-left: var(--ys-space-5) !important;
  font-size: 10.5px;
  font-weight: var(--ys-weight-semibold);
  letter-spacing: 0.07em;
  color: var(--ys-meta);
  opacity: 1;              /* EP 默认给 disabled 0.25 透明度，这里要它清晰可读 */
  background: transparent;
  cursor: default;
  pointer-events: none;     /* 分组标题不可交互 */
}
.side__menu :deep(.el-menu-item.is-disabled.side__grp:first-child) {
  margin-top: 0;
}

/*
 * 未读角标。定位在菜单项右侧，不挤占文字空间。
 * 与手机端 TabBar 的 UnreadDot 同义（红底白字数字）但形态不同：
 * 桌面侧栏空间足够，直接给数字比一个红点更有信息量。
 */
.side__badge {
  position: absolute;
  right: var(--ys-space-3);
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-danger);
  color: var(--ys-accent-on);
  font-size: 10px;
  line-height: 17px;
  font-weight: var(--ys-weight-semibold);
  text-align: center;
}

/* 菜单项本身要 relative，角标才能绝对定位 */
.side__menu :deep(.el-menu-item) {
  position: relative;
}

/* 管理待办角标：红色数字贴右，整项文字同时变红，扫一眼就知道该点哪一项 */
.side__pending {
  margin-left: auto;
  margin-right: var(--ys-space-2);
}

.side__sub-badge {
  margin-left: var(--ys-space-2);
}

.side__menu :deep(.el-menu-item.is-pending),
.side__menu :deep(.el-menu-item.is-pending .el-icon) {
  color: var(--ys-danger);
}
</style>