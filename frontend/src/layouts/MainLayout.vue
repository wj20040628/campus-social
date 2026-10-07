<template>
  <div class="main-layout">
    <el-container class="shell">
      <el-aside width="220px" class="side">
        <div class="brand">友声</div>
        <el-menu :default-active="activeMenu" router>
          <el-menu-item index="/">
            <el-icon><House /></el-icon>
            <span>首页</span>
          </el-menu-item>
          <el-menu-item index="/publish">
            <el-icon><EditPen /></el-icon>
            <span>发动态</span>
          </el-menu-item>
          <el-menu-item index="/circles">
            <el-icon><Collection /></el-icon>
            <span>圈子</span>
          </el-menu-item>
          <el-menu-item index="/chat">
            <el-icon><ChatDotRound /></el-icon>
            <span>私信</span>
          </el-menu-item>
          <el-menu-item index="/notify">
            <el-icon><Bell /></el-icon>
            <span>通知</span>
          </el-menu-item>
          <el-menu-item v-if="userStore.isAdmin" index="/admin/review">
            <el-icon><DocumentChecked /></el-icon>
            <span>内容审核</span>
          </el-menu-item>
          <el-menu-item v-if="userStore.isAdmin" index="/admin/audit">
            <el-icon><List /></el-icon>
            <span>操作日志</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <div class="right-col">
        <!-- 顶栏:中间圆形「+」发动态,右侧头像下拉(主页/设置/退出) -->
        <el-header class="topbar">
          <div class="topbar-spacer"></div>
          <el-tooltip content="发动态" placement="bottom">
            <button class="publish-btn" @click="router.push('/publish')">
              <el-icon :size="18"><Plus /></el-icon>
            </button>
          </el-tooltip>
          <div class="topbar-spacer"></div>
          <el-dropdown trigger="click" @command="onUserCommand">
            <div class="avatar-trigger">
              <el-avatar :size="34" :src="resolveUrl(userStore.userInfo?.avatarUrl)" />
              <el-icon class="caret"><CaretBottom /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人主页</el-dropdown-item>
                <el-dropdown-item command="settings">设置</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </el-header>

        <el-main class="content">
          <router-view />
        </el-main>
      </div>
    </el-container>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, CaretBottom, House, EditPen, Collection, ChatDotRound, Bell, DocumentChecked, List } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { getWsClient } from '@/utils/ws'
import { resolveUrl } from '@/utils/resolve'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 兜底:进布局时若 userInfo 还没加载(token 有效但没拉过),拉一次,
// 保证顶栏头像 / 个人主页 / 退出 都能用。
onMounted(() => {
  if (userStore.hasToken && !userStore.userInfo) {
    userStore.fetchMe().catch(() => {})
  }
})

const activeMenu = computed(() => {
  const p = route.path
  if (p === '/publish') return '/publish'
  if (p.startsWith('/chat')) return '/chat'
  if (p.startsWith('/notify')) return '/notify'
  if (p.startsWith('/circles')) return '/circles'
  if (p.startsWith('/activity')) return '/circles'
  if (p.startsWith('/search')) return '/search'
  if (p.startsWith('/admin/review')) return '/admin/review'
  if (p.startsWith('/admin/audit')) return '/admin/audit'
  if (p.startsWith('/post')) return '/'
  if (p.startsWith('/user')) return '/'
  return '/'
})

function goProfile() {
  router.push(`/user/${userStore.userInfo?.id}`)
}

/** 头像下拉菜单命令 */
function onUserCommand(cmd) {
  if (cmd === 'profile') goProfile()
  else if (cmd === 'settings') router.push('/settings')
  else if (cmd === 'logout') logout()
}

async function logout() {
  try {
    await ElMessageBox.confirm('确定退出登录?', '提示', { type: 'warning' })
  } catch {
    return // 点了取消
  }
  getWsClient().close() // 断开 WS,避免收到已注销账号的推送
  try {
    await userStore.logout() // 调后端吊销 refresh token + 清本地
    ElMessage.success('已退出登录')
    router.replace({ path: '/login/form' })
  } catch {
    // 即使后端调不通(如网络),也清本地并跳登录页
    userStore.clear()
    router.replace({ path: '/login/form' })
  }
}
</script>

<style scoped>
.main-layout {
  min-height: 100vh;
}
.shell {
  min-height: 100vh;
}
.side {
  border-right: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
}
.brand {
  padding: 20px 24px;
  font-size: 22px;
  font-weight: 700;
  color: var(--el-color-primary);
}
.side .el-menu {
  border-right: none;
  flex: 1;
}
.side .el-menu-item {
  font-size: 15px;
}
.side .el-menu-item .el-icon {
  font-size: 18px;
  margin-right: 6px;
}
.side .el-menu-item.is-active {
  font-weight: 600;
}
.right-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}
.topbar {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  position: sticky;
  top: 0;
  z-index: 10;
}
.topbar-spacer {
  flex: 1;
}
.publish-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-color-primary);
  color: #fff;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.35);
  transition: transform 0.15s, opacity 0.15s, box-shadow 0.15s;
}
.publish-btn:hover {
  opacity: 0.92;
  transform: scale(1.05);
  box-shadow: 0 6px 16px rgba(64, 158, 255, 0.45);
}
.avatar-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
}
.avatar-trigger:hover {
  background: var(--el-fill-color);
}
.avatar-trigger .caret {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.content {
  flex: 1;
  background: var(--el-bg-color-page);
}
</style>
