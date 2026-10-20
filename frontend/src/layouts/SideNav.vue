<template>
  <!--
    桌面侧栏(≥1024px)。手机端整个组件 display:none,不进入可聚焦树。
    保留 el-menu —— MainLayout 是「加 TabBar」,不是「重写导航」
    (mobile-shell.md §2.7 明确要求不删除 el-menu)。
  -->
  <aside class="side">
    <div class="brand">友声</div>
    <el-menu :default-active="activeMenu" router class="side__menu">
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
      <el-menu-item index="/publish">
        <el-icon><EditPen /></el-icon>
        <span>发动态</span>
      </el-menu-item>
      <el-menu-item index="/chat">
        <el-icon><ChatDotRound /></el-icon>
        <span>消息</span>
      </el-menu-item>
      <el-menu-item index="/announcement">
        <el-icon><Promotion /></el-icon>
        <span>公告</span>
      </el-menu-item>
      <el-menu-item index="/notify">
        <el-icon><Bell /></el-icon>
        <span>通知</span>
      </el-menu-item>

      <!-- 「发现」已从桌面左栏移除(2026-10-19):顶栏已有「找人」入口(/search),
           /discover 页面本身保留(个人主页标签区仍可进),只是不再占侧栏位置。 -->

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
        </template>
        <!--
          学号核验排第一,不是按字母也不是按开发顺序:
          认证是其他一切能力的前置(没过认证的用户不能搭话、不能发公告),
          一堆人卡在「审核中」时它是管理员唯一有盼头的那件事。
        -->
        <el-menu-item index="/admin/verify">
          <el-icon><Postcard /></el-icon>
          <span>学号核验</span>
        </el-menu-item>
        <el-menu-item index="/admin/review">
          <el-icon><DocumentChecked /></el-icon>
          <span>内容审核</span>
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
        <el-menu-item index="/admin/feedback">
          <el-icon><ChatLineSquare /></el-icon>
          <span>意见反馈</span>
        </el-menu-item>
      </el-sub-menu>
    </el-menu>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import {
  House, ChatDotSquare, EditPen, ChatDotRound, Promotion,
  Bell, DocumentChecked, List, Postcard, Lock, OfficeBuilding, Collection,
  ChatLineSquare,
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  routePath: { type: String, required: true },
})

const userStore = useUserStore()

const activeMenu = computed(() => {
  const p = props.routePath
  if (p.startsWith('/admin/verify')) return '/admin/verify'
  if (p.startsWith('/admin/announcement')) return '/admin/announcement'
  if (p.startsWith('/admin/review')) return '/admin/review'
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
  if (p.startsWith('/publish')) return '/publish'
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
</style>