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

      <!-- 加载骨架。形状对齐真实内容:装饰带+头像行+三统计(设计 §3) -->
      <div v-else-if="!user" class="profile-skeleton" aria-hidden="true">
        <div class="p-cover"></div>
        <SkeletonList variant="audit" :count="1" />
      </div>

      <el-card v-else class="profile-card">
        <!--
          装饰带:移动端也显示(72px)。旧版隐藏是因为它是纯灰空块不承载信息;
          现在它是品牌色 + 头像的叠放锚点(小红书式),72px 不吃首屏。
          桌面端 140px(身份感来源)。
        -->
        <div class="p-cover"></div>

        <!-- 身份头(hero 化):头像叠装饰带,操作按钮独立成行 -->
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
          @edit="goEditProfile"
          @report="onReportClick"
        />

        <!-- 拉黑关系提示(设计 §3 权限态)。给出解除出口,不能只告知 -->
        <p v-if="relStatus?.blacklisted" class="p-blocked">
          你已经把 {{ displayName(user) }} 拉黑了
          <button type="button" class="p-blocked__undo" @click="unblock">解除拉黑</button>
        </p>

        <!-- 认证状态提示条(仅本人主页)。组件内部自行判断要不要渲染 -->
        <VerifyNotice v-if="isMe" />

        <!-- 兴趣标签:自包含组件,自己拉数据(2026-10-18 从本页抽出) -->
        <ProfileTags :target-id="targetId" :is-me="isMe" />

        <!-- 三统计:粉丝/关注可点跳 Tab(2026-10-18 抽成组件) -->
        <ProfileStats :user="user" @switch="switchTab" />

        <!--
          分段 tab 条(2026-10-18 替代 el-tabs):
          粉丝/关注列表已进主页预拉(loadFollowers / loadFollowing),
          v-show 切换无请求竞态。
        -->
        <div class="p-tabbar" role="tablist" aria-label="内容分区">
          <button
            v-for="t in TABS"
            :key="t.name"
            type="button"
            role="tab"
            class="p-tabbar__item"
            :class="{ 'p-tabbar__item--on': tab === t.name }"
            :aria-selected="tab === t.name"
            @click="switchTab(t.name)"
          >
            {{ t.label }}
          </button>
        </div>

        <!-- 动态:列表拉失败只换内容区,身份头与统计仍可用(设计 §3);
             p-pane--posts 供桌面双列瀑布流定位(Profile.css) -->
        <div v-show="tab === 'posts'" class="p-pane p-pane--posts">
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
        </div>
        <!--
          空态三条各给一个出口(设计 §3,原三条 el-empty 全无按钮违反 AC-19);
          粉丝空态文案说「多发几条，就有人来了」,给行动而不是描述现象。
        -->
        <div v-show="tab === 'followers'" class="p-pane">
          <UserList
            :list="followers"
            :loading="loadingFollowers"
            empty-title="还没有人关注 TA"
            empty-desc="多发几条，就有人来了"
            empty-action="回首页"
            @empty-action="router.push('/')"
          />
        </div>
        <div v-show="tab === 'following'" class="p-pane">
          <UserList
            :list="following"
            :loading="loadingFollowing"
            empty-title="TA 还没关注任何人"
            empty-desc="去圈子里看看大家都在聊什么"
            empty-action="去逛逛"
            @empty-action="router.push('/circles')"
          />
        </div>
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

    <!-- 举报该用户 -->
    <ReportSheet
      v-model="reportOpen"
      :target-type="1"
      :target-id="targetId"
      sheet-title="举报这个用户"
      :submit-fn="submitReport"
    />
  </div>
</template>

<script setup>
/**
 * 个人主页(Profile.vue.md)
 * 数据与副作用在 useProfileData / useProfileRelations 里,本页只做编排。
 * 2026-10-18 改版:兴趣标签(ProfileTags)与三统计(ProfileStats)拆成组件、
 * el-tabs 换分段 tab 条 —— 原 326 行超门禁,拆完回到 300 内。
 * 2026-10-20:本人视角移除编辑/设置按钮(入口在全局抽屉);
 * 同日的竖卡瀑布流重设计经用户反馈已回退,动态恢复 PostCard 单列全宽卡。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatLineSquare, WarningFilled } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import VerifyNotice from '@/components/common/VerifyNotice.vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import ProfileHead from '@/components/profile/ProfileHead.vue'
import ProfileTags from '@/components/profile/ProfileTags.vue'
import ProfileStats from '@/components/profile/ProfileStats.vue'
import ReportSheet from '@/components/post/ReportSheet.vue'
import { useProfileData } from '@/composables/useProfileData'
import { useProfileRelations } from '@/composables/useProfileRelations'
import { useProfileActions } from '@/composables/useProfileActions'
import { useVerifyStatus } from '@/composables/useVerifyStatus'
import { useUserStore } from '@/stores/user'
import { useDictStore } from '@/stores/dict'
import { useIsMobile } from '@/composables/useBreakpoint'
import { submitReport } from '@/api/review'
import { displayName } from '@/utils/mask'
import PostCard from '@/components/PostCard.vue'
import UserList from '@/components/UserList.vue'

/** 分段 tab 三项:动态 / 粉丝 / 关注(name 与旧 el-tabs 一致) */
const TABS = [
  { name: 'posts', label: '动态' },
  { name: 'followers', label: '粉丝' },
  { name: 'following', label: '关注' },
]

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
  user, posts, loadFailed, postsFailed,
  load, loadPosts, identity, identityLine, verified,
} = useProfileData(targetId, myId, dict)

/*
  关注关系 + 粉丝/关注列表:状态与副作用都在 composable 里,
  切人时的重置由 composable 内部的 watch 负责,这里不重复做。
*/const {
  relStatus, followLoading, followers, following,
  loadingFollowers, loadingFollowing,
  loadRelation, toggleFollow, onTabChange,
  loadFollowers, loadFollowing,
} = useProfileRelations(targetId)

const tab = ref('posts')
const verifyOpen = ref(false)
const reportOpen = ref(false)

// 本人主页才拉认证状态:看别人的主页时拉了也白拉(还多一跳)
const { load: loadVerifyStatus } = useVerifyStatus()

/**
 * el-avatar 的 :size 是属性,媒体查询改不动(设计 §7)。
 * 移动端 64 / 桌面端 84,必须走 computed 而不是写死。
 */
const avatarSize = computed(() => (isMobile.value ? 64 : 84))

/*
  关系动作(关注带计数联动 / 解除拉黑 / 发私信):
  2026-10-15 拆去 composables/useProfileActions.js
*/
const { toggleFollowAndCount, unblock, onChatClick } = useProfileActions({
  targetId, user, relStatus, toggleFollow, loadRelation,
  onNeedVerify: () => { verifyOpen.value = true },
})

function goVerify() {
  verifyOpen.value = false
  // 认证是提交学号,学号字段在编辑资料页 /profile
  router.push('/profile')
}

function goDetail(id) {
  router.push(`/post/${id}`)
}

/** 「编辑资料」:空介绍文案可点进编辑页;按钮入口已收进全局抽屉(2026-10-20) */
function goEditProfile() {
  router.push('/profile')
}

/** 点「举报」打开举报操作表(目标类型 1=用户) */
function onReportClick() {
  reportOpen.value = true
}

/**
 * 点数字/tab 切换:自定义 tab 没有 el-tabs 的 @tab-change,
 * 手动改 tab.value 再补拉列表。
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
  // 切人时粉丝/关注列表与关系状态一起重拉,避免残留上一个用户的数据
  loadRelation().catch(() => {})
})

onMounted(async () => {
  // 字典与资料并发拉,不串行 —— 串行会让首屏多等一个往返。
  // 认证状态一并并发:只在本人主页拉(看别人主页时这条提示不出现)
  if (isMe.value) loadVerifyStatus()
  await Promise.all([dict.ensureLoaded(), load(), loadRelation().catch(() => {})])
  // 粉丝/关注列表进主页就预拉(和头部计数同源),消除「点 tab 才拉、空态抢跑」的时序竞态
  loadFollowers()
  loadFollowing()
})
</script>

<style scoped src="./Profile.css"></style>
