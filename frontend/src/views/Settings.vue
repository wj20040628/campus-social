<template>
  <div class="settings">
    <div class="settings-inner">
      <!--
        「我的」页:账号概览 + 粉丝/关注 + **我发布的帖子列表**。
        点 TabBar「我的」首屏看到的就是自己发的帖子,不是资料表单 ——
        资料编辑收进独立页 /settings/profile(概览卡「编辑资料」进去)。
        手机端概览置顶,帖子列表往下滚;桌面并存不退化。
      -->
      <el-card class="settings__overview">
        <div class="ov__avatar">
          <el-avatar :size="72" :src="resolveUrl(userStore.userInfo?.avatarUrl || '')" />
        </div>
        <div class="ov__id">
          <div class="ov__name">{{ userStore.nickname }}</div>
          <div class="ov__sub ys-num">@{{ userStore.userInfo?.username || '未登录' }}</div>
        </div>
        <!--
          账户操作收进右上角「⋯」下拉:手机端 375px 下两个横排按钮太挤,
          收成一个 44×44 触发器,展开是 编辑资料 / 我的审核记录 / 退出登录。
          范式对齐 TopBar.vue 的 el-dropdown(EP 内置,弹层层叠/esc 免手写)。
          范式:触发器用 <el-icon> 而非 <i class>;危险项退出登录用 divided 分隔。
        -->
        <el-dropdown class="ov__more" trigger="click" placement="bottom-end" @command="onOvCommand">
          <button type="button" class="ov__more-btn" aria-label="更多操作">
            <el-icon :size="20"><MoreFilled /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">编辑资料</el-dropdown-item>
              <el-dropdown-item command="review">我的审核记录</el-dropdown-item>
              <!--
                危险项退出登录孤零零挂最底:divided 分隔线 + CloseBold 图标前缀,
                与上面两个纯文字项拉开视觉距离;橙色 accent-2(非纯红,用户可恢复)。
                误触路径最长:点 ⋯ → 滑到底 → 才碰到它。
              -->
              <el-dropdown-item command="logout" divided>
                <el-icon class="ov__logout-icon"><CloseBold /></el-icon>
                <span class="ov__logout">退出登录</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-card>

      <!--
        管理员入口:手机端后台没有独立侧栏(桌面 SideNav 在移动端 display:none),
        「我的」是唯一能挂管理项的地方。仅 isAdmin 时渲染。
      -->
      <el-card v-if="userStore.isAdmin" class="settings__admin">
        <div class="ad__head">
          <span class="ad__label">管理</span>
          <span class="ad__sub">学号 / 内容 / 公告 / 日志</span>
        </div>
        <button type="button" class="ad__btn" @click="adminSheetOpen = true">进入管理后台</button>
      </el-card>

      <!-- 粉丝 / 关注:两个可点数字,点谁弹谁的名单 ActionSheet -->
      <el-card class="settings__relations">
        <div class="rel">
          <button
            type="button"
            class="rel__item"
            :disabled="relLoading.followers"
            @click="openList('followers')"
          >
            <span class="rel__num">{{ cap(userStore.userInfo?.followerCount ?? 0) }}</span>
            <span class="rel__label">粉丝</span>
          </button>
          <button
            type="button"
            class="rel__item"
            :disabled="relLoading.following"
            @click="openList('following')"
          >
            <span class="rel__num">{{ cap(userStore.userInfo?.followingCount ?? 0) }}</span>
            <span class="rel__label">关注</span>
          </button>
          <button
            type="button"
            class="rel__item"
            :disabled="postsLoading"
            @click="scrollToPosts"
          >
            <span class="rel__num">{{ cap(posts.length) }}</span>
            <span class="rel__label">帖子</span>
          </button>
        </div>
      </el-card>

      <!-- 我发布的帖子列表(首屏主体) -->
      <div class="settings__posts" id="settings-posts">
        <StateBlock
          v-if="postsFailed"
          variant="error"
          title="帖子没拉出来"
          description="网络好像断了,再试一次"
          action-label="重试"
          :icon="WarningFilled"
          @action="loadPosts"
        />
        <template v-else>
          <PostCard
            v-for="p in posts"
            :key="p.id"
            :post="p"
            @click="goDetail(p.id)"
          />
          <StateBlock
            v-if="posts.length === 0"
            variant="empty"
            title="你还没发过帖子"
            description="发一条,同校的同学就能看到你了"
            action-label="发一条"
            :icon="ChatLineSquare"
            @action="router.push('/publish')"
          />
        </template>
      </div>

      <!-- 粉丝 / 关注 名单弹层 -->
      <ActionSheet
        v-model="listSheetOpen"
        :title="listSheetKind === 'followers' ? '粉丝' : '我关注的人'"
        height="70vh"
      >
        <UserList
          :list="listSheetList"
          :loading="listSheetLoading"
          empty-title="这里还是空的"
          empty-desc="去圈子里看看,多互动就有内容了"
          empty-action="去逛逛"
          @empty-action="goCircles"
        />
      </ActionSheet>

      <!-- 我的审核记录弹层:普通用户可见(我发的内容被机审/驳回的历史)。
           复用 Profile 页同款 MyReviewBlock + review.mine(),不新建数据层。 -->
      <ActionSheet v-model="reviewSheetOpen" title="我的审核记录" height="70vh">
        <div class="ov__review" v-if="!reviewList.length && !reviewLoading">
          还没有审核记录
        </div>
        <MyReviewBlock v-else :list="reviewList" />
      </ActionSheet>

      <ActionSheet v-model="adminSheetOpen" title="管理后台">
        <button type="button" class="asheet__item" @click="goAdmin('/admin/verify')">学号核验</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/review')">内容审核</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/announcement')">公告管理</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/audit')">操作日志</button>
        <button type="button" class="asheet__item" @click="goAdmin('/admin/circles')">圈子处置</button>
      </ActionSheet>
    </div>
  </div>
</template>

<script setup>
/**
 * 「我的」页(移动端 TabBar「我的」的首屏)。
 * 概览 + 粉丝/关注可点数字 + 我发布的帖子列表。
 * 资料编辑已拆到 /settings/profile(见 SettingsProfile.vue),本页不再含表单。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { ChatLineSquare, WarningFilled, MoreFilled, CloseBold } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import StateBlock from '@/components/common/StateBlock.vue'
import PostCard from '@/components/PostCard.vue'
import UserList from '@/components/UserList.vue'
import MyReviewBlock from '@/components/common/MyReviewBlock.vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import { me as meApi } from '@/api/auth'
import { myPosts as myPostsApi } from '@/api/feed'
import { mine as myReviewApi } from '@/api/review'
import { following as followingApi, followers as followersApi } from '@/api/relation'
import { useUserStore } from '@/stores/user'
import { resolveUrl } from '@/utils/resolve'

const userStore = useUserStore()
const router = useRouter()

/* ---- 我发布的帖子 ---- */
const posts = ref([])
const postsLoading = ref(false)
const postsFailed = ref(false)

async function loadPosts() {
  postsFailed.value = false
  postsLoading.value = true
  try {
    const data = await myPostsApi(null, 50)
    // myPosts 契约与 useProfileData.normalize 相同:直接返回数组(非 {list})
    const list = Array.isArray(data) ? data : (data?.list || [])
    posts.value = list.map((p) => ({
      ...p,
      createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
    }))
  } catch {
    postsFailed.value = true
  } finally {
    postsLoading.value = false
  }
}

function goDetail(id) {
  router.push(`/post/${id}`)
}

/** 三数字卡「帖子」点击:平滑滚到下方帖子列表,与粉丝/关注弹名单的语义对称
 *  (可点三数字都是「定位到内容」而非「打开弹层」。用方法名而非模板里
 *  直接写 document.getElementById(...).scrollIntoView() 语句表达式 ——
 *  同 goCircles 的坑:编译器会静默丢掉成员调用的语句,渲染失败白屏。) */
function scrollToPosts() {
  const el = document.getElementById('settings-posts')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 粉丝/关注空态出口。必须是方法名而非模板里的语句表达式 ——
 *  模板编译器会静默丢掉含「成员调用」的语句(如 router.push(...)),
 *  整组件渲染失败,且没有 console 报错,表现为白屏(§踩坑记录)。 */
function goCircles() {
  router.push('/circles').catch(() => {})
}

/* ---- 粉丝 / 关注名单弹层 ---- */
const listSheetOpen = ref(false)
const listSheetKind = ref('followers')
const listSheetList = ref([])
const listSheetLoading = ref(false)
const relLoading = ref({ followers: false, following: false })
/** 计数封顶:超过 9999 显示 9999+ */
function cap(n) { return n > 9999 ? '9999+' : n }

async function openList(kind) {
  listSheetKind.value = kind
  listSheetOpen.value = true
  listSheetLoading.value = true
  listSheetList.value = []
  const meId = userStore.userInfo?.id
  try {
    const data = kind === 'followers'
      ? await followersApi(meId, null, 50)
      : await followingApi(meId, null, 50)
    listSheetList.value = (data?.list || []).filter(Boolean)
  } finally {
    listSheetLoading.value = false
    relLoading.value[kind] = false
  }
}

/* ---- 管理后台弹层 ---- */
const adminSheetOpen = ref(false)
function goAdmin(path) {
  adminSheetOpen.value = false
  router.push(path).catch(() => {})
}

/* ---- 概览卡右上角下拉(编辑资料 / 我的审核记录 / 退出登录) ---- */
function onOvCommand(cmd) {
  if (cmd === 'profile') {
    router.push('/settings/profile').catch(() => {})
  } else if (cmd === 'review') {
    openReview()
  } else if (cmd === 'logout') {
    doLogout()
  }
}

/* ---- 我的审核记录弹层 ---- */
const reviewSheetOpen = ref(false)
const reviewList = ref([])
const reviewLoading = ref(false)
async function openReview() {
  reviewSheetOpen.value = true
  if (reviewList.value.length) return // 已拉过就不重复请求
  reviewLoading.value = true
  try {
    // 契约同 useProfileData.normalize:直接取 {list}
    const data = await myReviewApi(null, 50)
    reviewList.value = (data?.list || []).filter(Boolean)
  } catch {
    reviewList.value = []
  } finally {
    reviewLoading.value = false
  }
}

/* ---- 退出登录 ---- */
async function doLogout() {
  try {
    await ElMessageBox.confirm('确定退出登录吗?', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await userStore.logout()
  } catch {
    userStore.clear()
  }
  ElMessage.success('已退出登录')
  router.replace('/login/form')
}

onMounted(async () => {
  // 刷新本地 userInfo(计数 followerCount 等随 /user/me 更新),再预拉帖子列表
  try {
    const me = await meApi()
    userStore.setUserInfo(me)
  } catch {
    /* 未登录由路由守卫拦,这里静默;posts 仍尽力拉 */
  }
  loadPosts()
})
</script>

<style scoped>
.settings-inner {
  max-width: 640px;
  margin: 0 auto;
  padding: var(--ys-gutter-desktop);
}

/* 账号概览卡:头像 + 昵称/username + 入口(编辑资料/退出登录) */
.settings__overview {
  margin-bottom: var(--ys-space-4);
}
.settings__overview :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
}
.ov__avatar { flex: none; }
.ov__id {
  flex: 1;
  min-width: 0;
  padding: 4px 0;
}
.ov__name {
  font-size: var(--ys-text-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ov__sub {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 右上角「⋯」触发器:el-dropdown 是 el-card 内的 flex 子项,flex:none 保持 44×44 */
.ov__more { flex: none; }
.ov__more-btn {
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  border-radius: var(--ys-radius-lg);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}
.ov__more-btn:hover { background: var(--ys-surface-2); }

/* el-dropdown 弹层挂在 body 上,scoped 选择器够不到;:deep 仅命中组件内触发器 */
.ov__logout { color: var(--ys-accent-2); }
/* 退出登录图标前缀:与文字同色,把危险项跟上面两个纯文字项拉开 */
.ov__logout-icon { color: var(--ys-accent-2); margin-right: var(--ys-space-1); }

/* 审核记录弹层空态 */
.ov__review {
  padding: var(--ys-space-6) var(--ys-space-4);
  text-align: center;
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}

/* 管理员入口卡 */
.settings__admin {
  margin-bottom: var(--ys-space-4);
}
.settings__admin :deep(.el-card__body) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
}
.ad__head {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-1);
  min-width: 0;
}
.ad__label {
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}
.ad__sub {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ad__btn {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
}
.ad__btn:hover { background: var(--ys-accent-hover); }

/* 粉丝 / 关注 / 帖子 计数卡 */
.settings__relations {
  margin-bottom: var(--ys-space-4);
}
.settings__relations :deep(.el-card__body) {
  display: flex;
}
.rel {
  display: flex;
  width: 100%;
}
.rel__item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ys-space-1);
  min-height: var(--ys-touch-min);
  border: none;
  background: transparent;
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}
.rel__item:hover { background: var(--ys-surface-2); }
.rel__item:disabled { cursor: default; opacity: 0.6; }
.rel__num {
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  font-family: var(--ys-font-body);
  color: var(--ys-fg-2);
}
.rel__label {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

/* 帖子列表 */
.settings__posts {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-3);
}
</style>
