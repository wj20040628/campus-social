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

      <!-- 加载骨架。形状对齐真实内容:封面 + 头像行 -->
      <div v-else-if="!user" class="profile-skeleton" aria-hidden="true">
        <div class="profile-skeleton__cover"></div>
        <SkeletonList variant="audit" :count="1" />
      </div>

      <template v-else>
        <!-- 封面:整屏图,末段渐到 --ys-bg 与页面底无缝。meta.ownTop 已关掉全局顶栏,故从视口顶铺起 -->
        <ProfileCover
          :is-me="isMe"
          :has-cover="hasCover"
          :bg-style="coverStyle"
          :busy="coverBusy"
          @pick="changeCover"
          @reset="resetCover"
          @more="openGlobalDrawer"
        />

        <!-- 身份头:头像负 margin 压在封面上,结构未改 -->
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
          :avatar-busy="avatarBusy"
          @follow="toggleFollowAndCount"
          @chat="onChatClick"
          @edit="goEditProfile"
          @report="onReportClick"
          @change-avatar="changeAvatar"
        />

        <!-- 拉黑关系提示(设计 §3 权限态)。给出解除出口,不能只告知 -->
        <p v-if="relStatus?.blacklisted" class="p-blocked">
          你已经把 {{ displayName(user) }} 拉黑了
          <button type="button" class="p-blocked__undo" @click="unblock">解除拉黑</button>
        </p>

        <!-- 2026-10-21：VerifyNotice 已上移到 MainLayout（全站可见），此处不再渲染 -->

        <!--
          四项统计:动态 / 获赞 / 粉丝 / 关注。
          粉丝与关注改为可点,点了拉起列表抽屉 —— 原来这两项占着内容 Tab 的两格,
          把「收藏」「赞过」挤没了。
        -->
        <ProfileStats :user="user" @switch="openRelation" />

        <!-- 兴趣标签:单行无限轮播(2026-10-20 从 4 行 wrap 改),自包含组件 -->
        <ProfileTags :target-id="targetId" :is-me="isMe" />

        <!-- 内容 Tab:带数字。他人主页只有「动态」一项,不渲染 Tab 条(单项 Tab 像功能缺失) -->
        <ProfileTabs v-if="TABS.length > 1" :items="tabItems" :active="tab" @switch="switchTab" />

        <!-- 动态:列表拉失败只换内容区,身份头与统计仍可用(设计 §3) -->
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
          收藏 / 赞过(仅本人):两者结构完全一样,只差数据源与文案,
          合并成一套模板按当前 Tab 取数据。写两份会让以后加一个空态出口
          要改两处,漏一处就出现「收藏有按钮、赞过没有」。
        -->
        <div v-show="tab === 'fav' || tab === 'like'" class="p-pane p-pane--posts">
          <StateBlock
            v-if="curFailed"
            variant="error"
            :title="curKind + '没拉出来'"
            description="网络好像断了，再试一次"
            action-label="重试"
            :icon="WarningFilled"
            @action="reloadCurrent"
          />
          <template v-else>
            <PostCard v-for="p in curList" :key="p.id" :post="p" @click="goDetail(p.id)" />
            <StateBlock
              v-if="!curLoading && curList.length === 0"
              variant="empty"
              :title="'还没有' + curKind + '的内容'"
              :description="curEmptyHint"
              action-label="去首页逛逛"
              :icon="Star"
              @action="router.push('/')"
            />
          </template>
        </div>
      </template>
    </div>

    <!-- 粉丝 / 关注列表抽屉 -->
    <RelationSheet
      v-model="relationOpen"
      :tab="relationTab"
      :list="relationTab === 'followers' ? followers : following"
      :loading="relationTab === 'followers' ? loadingFollowers : loadingFollowing"
      :is-me="isMe"
      @empty-action="onRelationEmptyAction"
    />

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
 * 数据与副作用在 useProfileData / useProfileRelations / useProfileCollections 里,
 * 本页只做编排。
 *
 * 2026-10-18 改版:兴趣标签与统计拆成组件、el-tabs 换分段 tab 条。
 * 2026-10-20 沉浸式改版(用户确认的设计稿):
 *   - 路由加 meta.ownTop,不要全局顶栏,封面从视口顶端铺满;
 *   - 去掉 el-card 外框,内容通栏,消除「相框式」留白;
 *   - 统计扩为四项(加「获赞」),粉丝/关注改抽屉,腾出 Tab 给收藏/赞过;
 *   - Tab 带数字;动态卡片排版保持不变。
 */
import { ref, computed, inject, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatLineSquare, WarningFilled, Star } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import ProfileHead from '@/components/profile/ProfileHead.vue'
import ProfileCover from '@/components/profile/ProfileCover.vue'
import ProfileTabs from '@/components/profile/ProfileTabs.vue'
import ProfileTags from '@/components/profile/ProfileTags.vue'
import ProfileStats from '@/components/profile/ProfileStats.vue'
import RelationSheet from '@/components/profile/RelationSheet.vue'
import ReportSheet from '@/components/post/ReportSheet.vue'
import { useProfileData } from '@/composables/useProfileData'
import { useProfileRelations } from '@/composables/useProfileRelations'
import { useProfileActions } from '@/composables/useProfileActions'
import { useProfileCollections } from '@/composables/useProfileCollections'
import { useProfileMedia } from '@/composables/useProfileMedia'
import { useVerifyStatus } from '@/composables/useVerifyStatus'
import { useUserStore } from '@/stores/user'
import { useDictStore } from '@/stores/dict'
import { useIsMobile } from '@/composables/useBreakpoint'
import { submitReport } from '@/api/review'
import { displayName } from '@/utils/mask'
import PostCard from '@/components/PostCard.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const dict = useDictStore()
const isMobile = useIsMobile()
const targetId = computed(() => Number(route.params.id))
const myId = computed(() => userStore.userInfo?.id)
/** 是否本人主页(防"关注自己":自己主页只显示编辑资料) */
const isMe = computed(() => !!myId.value && myId.value === targetId.value)

/**
 * 打开全局导航抽屉(能力由 MainLayout provide)。
 * 本页用了 meta.ownTop,TopBar 的汉堡不再渲染,不接这里「更多」点了没反应。
 */
const openGlobalDrawer = inject('openDrawer', () => {})

const {
  user, posts, loadFailed, postsFailed,
  load, loadPosts, identity, identityLine, verified,
} = useProfileData(targetId, myId, dict)

/**
 * 点头像换头像 / 点封面换封面(2026-10-20)。
 * 上传与落库都在 composable 里,这里只拿状态与三个动作 ——
 * 它同时要写页面上的 user 与全局 userStore,摊在视图里必漏一处。
 */
const { avatarBusy, coverBusy, hasCover, coverStyle, changeAvatar, changeCover, resetCover }
  = useProfileMedia(user)

/*
  关注关系 + 粉丝/关注列表:状态与副作用都在 composable 里,
  切人时的重置由 composable 内部的 watch 负责,这里不重复做。
*/
const {
  relStatus, followLoading, followers, following,
  loadingFollowers, loadingFollowing,
  loadRelation, toggleFollow, loadFollowers, loadFollowing,
} = useProfileRelations(targetId)

/**
 * 内容 Tab。
 * 他人主页只有「动态」:收藏和赞过是私人数据,不该出现在别人能看到的页面。
 */
const TABS = computed(() => (isMe.value
  ? [
      { name: 'posts', label: '动态' },
      { name: 'fav', label: '收藏' },
      { name: 'like', label: '赞过' },
    ]
  : [{ name: 'posts', label: '动态' }]))

const tab = ref('posts')
const verifyOpen = ref(false)
const reportOpen = ref(false)

/** 粉丝/关注抽屉 */
const relationOpen = ref(false)
const relationTab = ref('followers')

/** 收藏/赞过(懒加载,失败只在对应 Tab 内报错) */
const {
  favorites, liked, favLoading, likedLoading, favFailed, likedFailed,
  loadFavorites, loadLiked, reset: resetCollections,
} = useProfileCollections(isMe)

// 本人主页才拉认证状态:看别人的主页时拉了也白拉(还多一跳)
const { load: loadVerifyStatus } = useVerifyStatus()

/**
 * el-avatar 的 :size 是属性,媒体查询改不动(设计 §7)。
 * 移动端 76(封面 260 下压 40 的视觉平衡)/ 桌面端 88。
 */
const avatarSize = computed(() => (isMobile.value ? 76 : 88))

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
 * Tab 上的数字,随视角算好交给 ProfileTabs。
 * 优先用后端下发的总数(/user/me 里的 postCount / favoriteCount / likedCount),
 * 因为列表接口只返回一页,拿页长度当总数在超过一页后就会永远停在 50。
 */
const tabItems = computed(() => TABS.value.map((t) => ({
  ...t,
  count: t.name === 'posts' ? (user.value?.postCount ?? posts.value.length)
    : t.name === 'fav' ? (user.value?.favoriteCount ?? favorites.value.length)
      : (user.value?.likedCount ?? liked.value.length),
})))

/** 切 Tab:收藏/赞过首次进入才拉,之后走缓存 */
function switchTab(name) {
  tab.value = name
  if (name === 'fav') loadFavorites()
  else if (name === 'like') loadLiked()
}

/*
  收藏 / 赞过共用一个面板,下面四个 computed 负责「当前看的是哪一份」。
  用 computed 而不是在 template 里写三元:模板里写三元要重复四遍,
  改一处漏一处就会出现「标题说收藏、列表显示赞过」。
*/
const isFavTab = computed(() => tab.value === 'fav')
const curList = computed(() => (isFavTab.value ? favorites.value : liked.value))
const curLoading = computed(() => (isFavTab.value ? favLoading.value : likedLoading.value))
const curFailed = computed(() => (isFavTab.value ? favFailed.value : likedFailed.value))
const curKind = computed(() => (isFavTab.value ? '收藏' : '赞过'))
const curEmptyHint = computed(() => (isFavTab.value
  ? '看到想留着以后看的，点一下星星'
  : '给喜欢的动态点个赞，会出现在这里'))

/** 失败态点「重试」:先清掉 failed 再重来,否则按钮点了没有任何反馈 */
function reloadCurrent() {
  if (isFavTab.value) {
    favFailed.value = false
    loadFavorites()
  } else {
    likedFailed.value = false
    loadLiked()
  }
}

/** 打开粉丝/关注抽屉(从统计行点进来) */
function openRelation(name) {
  relationTab.value = name
  relationOpen.value = true
  // 进主页已预拉过,这里补一次是为了「列表曾经失败」时用户再点还能重试
  if (name === 'followers') loadFollowers()
  else loadFollowing()
}

/** 抽屉空态的出口:粉丝空态去发一条,关注空态去广场 */
function onRelationEmptyAction() {
  relationOpen.value = false
  router.push(relationTab.value === 'followers' ? '/publish' : '/circles')
}

// 路由变化(从他人主页切到自己/切到别的用户)时全量重载:
// 组件被路由复用不会重建,onMounted 只跑一次,靠 watch 重新拉
watch(targetId, () => {
  user.value = null
  tab.value = 'posts'
  resetCollections()
  load()
  // 切人时粉丝/关注列表与关系状态一起重拉,避免残留上一个用户的数据
  loadRelation().catch(() => {})
})

onMounted(async () => {
  // 字典与资料并发拉,不串行 —— 串行会让首屏多等一个往返。
  // 认证状态一并并发:只在本人主页拉(看别人主页时这条提示不出现)
  if (isMe.value) loadVerifyStatus()
  await Promise.all([dict.ensureLoaded(), load(), loadRelation().catch(() => {})])
  // 粉丝/关注列表进主页就预拉(和头部计数同源),消除「点数字才拉、空态抢跑」的时序竞态
  loadFollowers()
  loadFollowing()
})
</script>

<style scoped src="./Profile.css"></style>
