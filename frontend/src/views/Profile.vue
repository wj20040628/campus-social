<template>
  <div class="profile">
    <div class="profile-inner">
      <!-- 首屏加载骨架:资料还没拉回来时占位,避免他人主页进来纯白 -->
      <el-card v-if="!user" class="profile-card profile-skeleton">
        <el-skeleton :rows="5" animated />
      </el-card>
      <el-card v-else class="profile-card">
        <!-- 封面横幅 -->
        <div class="p-cover"></div>
        <div class="p-head">
          <el-avatar :size="72" :src="resolveUrl(user.avatarUrl)" class="p-avatar" />
          <div class="p-info">
            <div class="p-name">{{ displayName(user) }}</div>
            <div class="p-sub">{{ maskUsername(user?.username) }}</div>
            <div class="p-tags">
              <el-tag v-if="user.dept" size="small">{{ user.dept }}</el-tag>
              <el-tag v-if="user.grade" size="small" type="info">{{ user.grade }}</el-tag>
            </div>
          </div>
          <div class="p-actions">
            <!-- 自己主页:只显示「编辑资料」(不显示关注/私信,防止"关注自己") -->
            <template v-if="isMe">
              <el-button @click="goSettings">编辑资料</el-button>
            </template>
            <!-- 他人主页:关注 + 发私信 -->
            <template v-else-if="myId">
              <el-button :type="relStatus?.following ? 'default' : 'primary'" :loading="followLoading" @click="toggleFollow">
                <el-icon><Plus /></el-icon> {{ relStatus?.following ? '已关注' : '关注' }}
              </el-button>
              <el-button @click="goChat">
                <el-icon><ChatDotRound /></el-icon> 发私信
              </el-button>
            </template>
          </div>
        </div>

        <el-row class="p-stats">
          <el-col :span="8">
            <div class="stat-num clickable" @click="switchTab('followers')">{{ user.followerCount }}</div>
            <div class="stat-label clickable" @click="switchTab('followers')">粉丝</div>
          </el-col>
          <el-col :span="8">
            <div class="stat-num clickable" @click="switchTab('following')">{{ user.followingCount }}</div>
            <div class="stat-label clickable" @click="switchTab('following')">关注</div>
          </el-col>
          <el-col :span="8"><div class="stat-num">{{ posts.length }}</div><div class="stat-label">动态</div></el-col>
        </el-row>

        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="动态" name="posts">
            <!-- 作者侧:我的审核结果(被驳理由/命中词/AI 预判),仅本人主页显示 -->
            <div v-if="isMe && myReviews.length" class="my-review">
              <div class="my-review-title">
                我的审核记录
                <span class="my-review-sub">共 {{ myReviews.length }} 条</span>
              </div>
              <el-collapse v-model="myReviewActive">
                <el-collapse-item
                  v-for="r in myReviews"
                  :key="r.postId"
                  :name="r.postId"
                >
                  <template #title>
                    <div class="mr-line">
                      <el-tag v-if="r.status === 2" type="danger" size="small">已驳回</el-tag>
                      <el-tag v-else-if="r.status === 0" type="warning" size="small">审核中</el-tag>
                      <el-tag v-else type="success" size="small">已通过</el-tag>
                      <span class="mr-time">{{ r.createTime }}</span>
                    </div>
                  </template>
                  <div class="mr-body">
                    <div v-if="r.hitWords?.length" class="mr-row">
                      <span class="mr-label">命中词</span>
                      <el-tag v-for="w in r.hitWords" :key="w" size="small" type="info" class="mr-hit">{{ w }}</el-tag>
                    </div>
                    <div v-if="r.reason" class="mr-row">
                      <span class="mr-label">驳回理由</span>
                      <span class="mr-text">{{ r.reason }}</span>
                    </div>
                    <div v-if="r.aiVerdict" class="mr-row">
                      <span class="mr-label">AI 机审</span>
                      <el-tag :type="aiTag(r.aiVerdict)" size="small">{{ aiText(r.aiVerdict) }}</el-tag>
                      <span v-if="r.aiReason" class="mr-text">{{ r.aiReason }}</span>
                    </div>
                    <div v-if="r.status === 0" class="mr-tip">
                      内容审核中,通过后即可展示。若长时间未通过,可修改命中词后重新提交。
                    </div>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </div>

            <PostCard v-for="p in posts" :key="p.id" :post="p" @click="goDetail(p.id)" />
            <el-empty v-if="posts.length === 0" description="还没有发过动态" :image-size="80" />
          </el-tab-pane>
          <el-tab-pane label="粉丝" name="followers">
            <UserList :list="followers" :loading="loadingFollowers" />
          </el-tab-pane>
          <el-tab-pane label="关注" name="following">
            <UserList :list="following" :loading="loadingFollowing" />
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Plus } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { me as meApi, userCard } from '@/api/auth'
import { myPosts as myPostsApi } from '@/api/feed'
import { mine as myReviewsApi } from '@/api/review'
import { status, follow as followApi, unfollow as unfollowApi, followers as followersApi, following as followingApi } from '@/api/relation'
import { useUserStore } from '@/stores/user'
import { maskUsername, displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import PostCard from '@/components/PostCard.vue'
import UserList from '@/components/UserList.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const targetId = computed(() => Number(route.params.id))
const myId = computed(() => userStore.userInfo?.id)
/** 是否本人主页(防"关注自己":自己主页只显示编辑资料) */
const isMe = computed(() => !!myId.value && myId.value === targetId.value)

const user = ref(null)
const posts = ref([])
const relStatus = ref(null)
const followLoading = ref(false)          // 关注按钮 loading(原 following)
const followers = ref([])                 // 粉丝列表
const following = ref([])                // 关注列表
const loadingFollowers = ref(false)
const loadingFollowing = ref(false)
const tab = ref('posts')

// 作者侧:我的审核结果(被驳/通过 + 理由 + 命中词 + AI 预判)
const myReviews = ref([])
const myReviewActive = ref([])

function normalize(list) {
  return (list || []).map((p) => ({
    ...p,
    createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
  }))
}

async function load() {
  // 自己:用 /user/me(全字段)+ 查自己的动态;他人:用 /user/{id} 卡片
  if (myId.value === targetId.value) {
    user.value = await meApi()
    const data = await myPostsApi(null, 50)
    posts.value = normalize(data.list)
    loadMyReviews()
  } else {
    user.value = await userCard(targetId.value)
    posts.value = []
    myReviews.value = []
  }
  relStatus.value = await status(targetId.value)
}

// 拉我自己的审核结果(作者侧,只能看自己的)
async function loadMyReviews() {
  try {
    const data = await myReviewsApi(null, 50)
    myReviews.value = (data?.list || []).filter(Boolean)
    myReviewActive.value = [] // 默认全收起,避免一大片展开很吵
  } catch {
    myReviews.value = []
  }
}

// AI 判定文案/标签色(与审核台一致)
function aiText(v) {
  return { approve: '建议通过', reject: '建议驳回', manual: '转人工' }[v] || v || ''
}
function aiTag(v) {
  return { approve: 'success', reject: 'danger', manual: 'warning' }[v] || 'info'
}

// 点数字切 tab 并惰性拉列表(数字改动不触发 el-tabs 的 @tab-change,得手动切 tab.value)
function switchTab(name) {
  tab.value = name
  onTabChange(name)
}

// 点 tab 头(「粉丝/关注」标签)触发惰性加载
function onTabChange(name) {
  if (name === 'followers' && !followers.value.length && !loadingFollowers.value) loadFollowers()
  if (name === 'following' && !following.value.length && !loadingFollowing.value) loadFollowing()
}

async function loadFollowers() {
  loadingFollowers.value = true
  try {
    const data = await followersApi(targetId.value, null, 50)
    followers.value = (data?.list || []).filter(Boolean)
  } finally {
    loadingFollowers.value = false
  }
}

async function loadFollowing() {
  loadingFollowing.value = true
  try {
    const data = await followingApi(targetId.value, null, 50)
    following.value = (data?.list || []).filter(Boolean)
  } finally {
    loadingFollowing.value = false
  }
}

/**
 * 路由变化(从他人主页切到自己/切到别的用户)时,全量重载:
 * 组件被路由复用不会重建,onMounted 只跑一次,靠 watch 重新拉
 * user / relStatus / 粉丝 / 关注,否则按钮(编辑资料 vs 关注)和资料会残留旧 targetId 的值。
 */
watch(targetId, () => {
  user.value = null
  relStatus.value = null
  posts.value = []
  myReviews.value = []
  followers.value = []
  following.value = []
  load()
  loadFollowers()
  loadFollowing()
})

async function toggleFollow() {
  followLoading.value = true
  try {
    if (relStatus.value?.following) {
      await unfollowApi(targetId.value)
      relStatus.value.following = false
      user.value.followerCount = Math.max(0, (user.value.followerCount || 0) - 1)
    } else {
      await followApi(targetId.value)
      relStatus.value.following = true
      user.value.followerCount = (user.value.followerCount || 0) + 1
    }
  } finally {
    followLoading.value = false
  }
}

function goChat() {
  router.push(`/chat?to=${targetId.value}`)
}
function goDetail(id) {
  router.push(`/post/${id}`)
}
function goSettings() {
  router.push('/settings')
}

onMounted(load)
</script>

<style scoped>
.profile-inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
}
.profile-card {
  border-radius: var(--card-radius);
  overflow: hidden;
  padding: 0;
}
.profile-skeleton {
  padding: 24px;
}
.profile-card :deep(.el-card__body) {
  padding: 0;
}
.profile-card :deep(.el-card__body) > * {
  padding: 0 20px;
}
.p-cover {
  height: 96px;
  background: linear-gradient(135deg, var(--el-color-primary-light-7), var(--el-color-primary-light-3));
}
.p-head {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  margin-top: -36px;   /* 头像压到封面下沿,Instagram 式 */
  padding-bottom: 12px;
}
.p-avatar {
  border: 4px solid var(--el-bg-color);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}
.p-name { font-size: 20px; font-weight: 700; }
.p-sub { font-size: 13px; color: var(--el-text-color-secondary); }
.p-tags { display: flex; gap: 6px; margin-top: 8px; }
.p-actions { margin-left: auto; display: flex; gap: 8px; }
.p-stats { margin: 12px 0 8px; text-align: center; }
.stat-num { font-size: 22px; font-weight: 700; }
.stat-label { font-size: 13px; color: var(--el-text-color-secondary); }
.stat-num.clickable, .stat-label.clickable {
  cursor: pointer;
}
.stat-num.clickable:hover, .stat-label.clickable:hover {
  color: var(--el-color-primary);
}

/* 作者侧:我的审核记录 */
.my-review {
  margin: 0 20px 16px;
  padding: 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
}
.my-review-title {
  font-weight: 700;
  font-size: 15px;
  margin-bottom: 8px;
}
.my-review-sub {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 8px;
}
.my-review .el-collapse {
  border: none;
  background: var(--el-bg-color);
}
.mr-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
}
.mr-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
.mr-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mr-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.6;
}
.mr-label {
  flex: 0 0 56px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.mr-text {
  color: var(--el-text-color-regular);
}
.mr-hit {
  margin-right: 4px;
}
.mr-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 6px 8px;
  background: var(--el-color-warning-light-9);
  border-radius: 6px;
}
</style>
