<template>
  <div class="profile">
    <div class="profile-inner">
      <!--
        资料级失败:用户不存在 / 已注销(设计 §3 错误态,整页替换)。
        原来这里是 `v-if="!user"` 一路走到骨架屏 —— load() 失败时 user 保持 null,
        页面永远转圈,用户既看不到内容也看不到错误。
      -->
      <StateBlock
        v-if="loadFailed"
        class="profile-state"
        variant="error"
        title="这个用户不见了"
        description="可能已经注销，或者链接错了"
        action-label="回首页"
        :icon="WarningFilled"
        @action="router.push('/')"
      />

      <!-- 加载骨架。形状对齐真实内容:身份头(圆 + 三行条)+ 三统计(设计 §3) -->
      <div v-else-if="!user" class="profile-skeleton" aria-hidden="true">
        <SkeletonList variant="audit" :count="1" />
        <div class="profile-skeleton__stats">
          <SkeletonList variant="panel" :count="3" />
        </div>
      </div>

      <el-card v-else class="profile-card">
        <!--
          封面横幅:移动端隐藏(设计 §1.1)。
          它在本项目里是一个纯灰空块,不承载信息,在 375px 下吃掉 80-120px 首屏高度,
          把真正重要的身份信息推到折叠线以下。桌面端恢复 —— 桌面上横幅不占首屏成本,
          且它是个人主页的身份感来源。
        -->
        <div class="p-cover"></div>

        <!-- 身份头(设计 §1.1 的核心改造)独立成组件 -->
        <ProfileHead
          :user="user"
          :identity="identity"
          :identity-line="identityLine"
          :verified="verified"
          :is-me="isMe"
          :following="!!relStatus?.following"
          :follow-loading="followLoading"
          :show-actions="!!myId && !isMe"
          :avatar-size="avatarSize"
          @follow="toggleFollowAndCount"
          @chat="onChatClick"
          @settings="goSettings"
        />

        <!-- 拉黑关系提示(设计 §3 权限态)。给出解除出口,不能只告知 -->
        <p v-if="relStatus?.blacklisted" class="p-blocked">
          你已经把 {{ displayName(user) }} 拉黑了
          <button type="button" class="p-blocked__undo" @click="unblock">解除拉黑</button>
        </p>

        <!--
          三统计:栅格一律 CSS Grid(设计 §1.2)。
          可点项是<button>、不可点的「动态」是 <div>(设计 §5)——
          用同一个元素靠 class 区分会让读屏器把「动态」也念成可点的。
        -->
        <div class="p-stats">
          <button
            v-for="s in clickableStats"
            :key="s.label"
            type="button"
            class="p-stat p-stat--btn"
            @click="switchTab(s.tab)"
          >
            <span class="p-stat__num">{{ cap(s.value) }}</span>
            <span class="p-stat__label">{{ s.label }}</span>
          </button>
          <div class="p-stat">
            <span class="p-stat__num">{{ cap(posts.length) }}</span>
            <span class="p-stat__label">动态</span>
          </div>
        </div>

        <el-tabs v-model="tab" @tab-change="onTabChange">
          <el-tab-pane label="动态" name="posts">
            <!-- 作者侧:我的审核结果(被驳理由/命中词/AI 预判),仅本人主页显示 -->
            <MyReviewBlock v-if="isMe" :list="myReviews" />

            <!-- 动态列表拉失败:只换内容区,身份头与统计仍可用(设计 §3) -->
            <StateBlock
              v-if="postsFailed"
              variant="error"
              title="动态没拉出来"
              description="网络好像断了，再试一次"
              action-label="重试"
              :icon="WarningFilled"
              @action="loadPosts"
            />
            <template v-else>
              <PostCard v-for="p in posts" :key="p.id" :post="p" @click="goDetail(p.id)" />
              <!--AC-19:空态出口按「我的主页 / 看别人」分开 ——
              看别人的主页时让他去发帖子是没意义的,该引导去关注或回广场。-->
              <StateBlock
                v-if="posts.length === 0"
                variant="empty"
                :title="isMe ? '你还没发过动态' : 'TA 还没发过动态'"
                :description="isMe ? '发一条，同校的同学就能看到你' : '关注 TA，发新动态时你会收到提醒'"
                :action-label="isMe ? '发一条' : '回圈子广场'"
                :icon="ChatLineSquare"
                @action="isMe ? router.push('/publish') : router.push('/circles')"
              />
            </template>
          </el-tab-pane>
          <!--
            空态三条各给一个出口(设计 §3)。原实现三条 el-empty 全无按钮,违反 AC-19。
            文案也按设计改:粉丝空要说「多发几条，就有人来了」而不是「会出现在这儿」——
            后者只是描述现象,前者给行动。
          -->
          <el-tab-pane label="粉丝" name="followers">
            <UserList
              :list="followers"
              :loading="loadingFollowers"
              empty-title="还没有人关注 TA"
              empty-desc="多发几条，就有人来了"
              empty-action="回首页"
              @empty-action="router.push('/')"
            />
          </el-tab-pane>
          <el-tab-pane label="关注" name="following">
            <UserList
              :list="following"
              :loading="loadingFollowing"
              empty-title="TA 还没关注任何人"
              empty-desc="去圈子里看看大家都在聊什么"
              empty-action="去逛逛"
              @empty-action="router.push('/circles')"
            />
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>

    <!-- AC-11:未认证点「发私信」的说明 -->
    <ActionSheet v-model="verifyOpen" title="发私信">
      <div class="vfy">
        <p class="vfy__why">要先认证校内身份才能搭话</p>
        <p class="vfy__how">认证只需要校园邮箱或学号，一分钟就好</p>
        <button type="button" class="vfy__btn" @click="goVerify">去认证</button>
      </div>
    </ActionSheet>
  </div>
</template>

<script setup>
/**
 * 个人主页(Profile.vue.md)
 * 数据与副作用在 useProfileData / useProfileRelations 里,
 * 本页只做身份头与三个 Tab 的编排。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatLineSquare, WarningFilled } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import MyReviewBlock from '@/components/common/MyReviewBlock.vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import ProfileHead from '@/components/profile/ProfileHead.vue'
import { useProfileData } from '@/composables/useProfileData'
import { useProfileRelations } from '@/composables/useProfileRelations'
import { useUserStore } from '@/stores/user'
import { useDictStore } from '@/stores/dict'
import { useIsMobile } from '@/composables/useBreakpoint'
import { unblacklist } from '@/api/relation'
import { capCount as cap } from '@/composables/usePostDetail'
import { displayName, isIdentityVerified } from '@/utils/mask'
import PostCard from '@/components/PostCard.vue'
import UserList from '@/components/UserList.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const dict = useDictStore()
const isMobile = useIsMobile()
const targetId = computed(() => Number(route.params.id))
const myId = computed(() => userStore.userInfo?.id)
/** 是否本人主页(防"关注自己":自己主页只显示编辑资料) */
const isMe = computed(() => !!myId.value && myId.value === targetId.value)

const {
  user, posts, myReviews, loadFailed, postsFailed,
  load, loadPosts, identity, identityLine, verified,
} = useProfileData(targetId, myId, dict)

/*
  关注关系 + 粉丝/关注列表:状态与副作用都在 composable 里。
  切人时的重置由 composable 内部的 watch 负责,这里不重复做。
*/
const {
  relStatus, followLoading, followers, following,
  loadingFollowers, loadingFollowing,
  loadRelation, toggleFollow, onTabChange,
} = useProfileRelations(targetId)

const tab = ref('posts')
const verifyOpen = ref(false)

/**
 * el-avatar 的 :size 是属性,媒体查询改不动(设计 §7)。
 * 移动端 56 / 桌面端 72,必须走 computed 而不是写死 :size="72"。
 */
const avatarSize = computed(() => (isMobile.value ? 56 : 72))

/** 粉丝 / 关注两项可点(跳对应 Tab),「动态」不可点(数字来源是当前已加载的列表) */
const clickableStats = computed(() => [
  { label: '粉丝', value: user.value?.followerCount ?? 0, tab: 'followers' },
  { label: '关注', value: user.value?.followingCount ?? 0, tab: 'following' },
])

/**
 * 关注按钮 + 粉丝数联动。
 *
 * 计数在前端同步改而不是等下一次往返:用户看到「关注成功了但粉丝数没变」
 * 会比慢 300ms 更困惑。
 *
 * 只有拿到明确的三态结果才动计数 —— 失败(null)时不动,
 * 否则一次网络失败会让粉丝数凭空少 1。
 */
async function toggleFollowAndCount() {
  const result = await toggleFollow()
  if (!result || !user.value) return
  user.value.followerCount = Math.max(
    0,
    (user.value.followerCount || 0) + (result === 'followed' ? 1 : -1),
  )
}

/** 解除拉黑。失败时请求层已弹错,关系状态维持原样 */
async function unblock() {
  try {
    await unblacklist(targetId.value)
    relStatus.value.blacklisted = false
    await loadRelation().catch(() => {})
  } catch {
    /* 留在原地,用户可再点一次 */
  }
}

/** AC-11:未认证不跳私信,给说明与出口 */
function onChatClick() {
  if (!isIdentityVerified(userStore.userInfo)) {
    verifyOpen.value = true
    return
  }
  router.push(`/chat?to=${targetId.value}`)
}

function goVerify() {
  verifyOpen.value = false
  router.push('/onboarding')
}

function goDetail(id) {
  router.push(`/post/${id}`)
}

function goSettings() {
  router.push('/settings')
}

/**
 * 点数字切 Tab:数字改动不触发 el-tabs 的 @tab-change,得手动改 tab.value 再补拉列表。
 */
function switchTab(name) {
  tab.value = name
  onTabChange(name)
}

// 路由变化(从他人主页切到自己/切到别的用户)时全量重载:
// 组件被路由复用不会重建,onMounted 只跑一次,靠 watch 重新拉
watch(targetId, () => {
  user.value = null
  load()
})

onMounted(async () => {
  // 字典与资料并发拉,不串行 —— 串行会让首屏多等一个往返
  await Promise.all([dict.ensureLoaded(), load(), loadRelation().catch(() => {})])
})
</script>

<style scoped src="./Profile.css"></style>