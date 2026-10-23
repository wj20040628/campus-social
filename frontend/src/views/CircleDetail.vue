<template>
  <div class="cd">
    <!-- 骨架:圈子头 + 3 条帖子。形状对齐真实条目,避免加载完成时 CLS -->
    <template v-if="loading">
      <SkeletonList :count="1" variant="panel" />
      <SkeletonList :count="3" />
    </template>

    <!--
      圈子不存在 / 已解散:整页替换(§3 错误态)。
      不是「圈子为空」——那是另一回事(圈子存在但没帖子)。
    -->
    <StateBlock
      v-else-if="notFound"
      variant="error"
      title="这个圈子不存在,或者已经解散了"
      description="可能链接过期了,回圈子广场看看别的"
      action-label="回圈子广场"
      :icon="WarningFilled"
      @action="router.push('/circles')"
    />

    <StateBlock
      v-else-if="error"
      variant="error"
      title="圈子没拉出来"
      :description="errorMsg || '可能是网络断了,点一下重试'"
      action-label="重试"
      :icon="WarningFilled"
      @action="load()"
    />

    <template v-else-if="circle">
      <!--
        双栏容器（2026-10-21）。
        手机端是单列（右栏 display:none），桌面端变成「主列 640 + 右栏 268」。
        用 CSS Grid 而不是 flex：主列有固定上限，flex 在窄屏下会被右栏挤变形。
      -->
      <div class="cd__layout">
        <div class="cd__main">
      <!-- 行 2:圈子头(圈主/圈管理露「管理」入口;成员露「退出圈子」) -->
      <CircleHeader
        :circle="circle"
        :joining="joining"
        :leaving="leaving"
        @join="join"
        @leave="leave"
        @manage="showManage = true"
      />

      <!-- 圈管理面板:成员处置(封禁/解禁/任命)+ 解散(仅圈主)。仅 myRole<=2 可见 -->
      <CircleManagePanel
        v-if="canManage"
        v-model:visible="showManage"
        :circle-id="circleId"
        :my-role="circle.myRole"
        :me="meId"
        @disband="onDisband"
      />

      <!--
        行 3:自绘分段控件(不用 el-tabs):
        el-tabs 的下划线动画在吸顶时会抖。
        指示条动 transform(§4),内容区只动 opacity。
      -->
      <div class="cd__tabs" role="tablist" aria-label="圈子内容">
        <button
          v-for="t in TABS"
          :key="t.name"
          type="button"
          role="tab"
          class="cd__tab"
          :class="{ 'is-on': tab === t.name }"
          :aria-selected="tab === t.name"
          @click="tab = t.name"
        >
          {{ t.label }}
        </button>
        <span class="cd__tab-ind" :style="{ transform: `translateX(${tab === 'posts' ? '0%' : '100%'})` }" />
      </div>

      <!-- 行 4+5:帖子 Tab -->
      <div v-show="tab === 'posts'" role="tabpanel" class="cd__body">
        <!-- 圈内被封禁(软封禁):保留查看,禁发帖。写口后端 422 CIRCLE_MEMBER_BANNED -->
        <div v-if="canCreate && banned" class="cd__banned" role="alert">
          你已被圈主/圈管理封禁,暂时无法在这个圈子发帖、评论、点赞。仅保留查看。
        </div>

        <!--
          发帖入口（2026-10-21 从常驻框改成触发条）。

          【为什么改】常驻发帖框占了 Tab 正下方 —— 整页最贵的视线落点，
          却放着一个 99% 时间空着的表单。于是「能不能发」压过了「别人说了什么」，
          帖子列表被推下去。现在缩成一行：不抢视线，但随时可点。

          【为什么「有帖子才显示触发条」】没有帖子时下面是空态，
          而空态自带「写第一条」按钮（AC-19 要求）。两个入口同时出现在
          就变成「同一动作两处可点」—— 正是改版前「发第一条」与输入框重复的老问题。
          所以两者只留其一：有内容用触发条，没内容用空态按钮。
        -->
        <button
          v-if="canCreate && !banned && posts.length > 0"
          type="button"
          class="cd__trigger"
          @click="openComposer"
        >
          <span class="cd__trigger-ic" aria-hidden="true">
            <el-icon :size="14"><EditPen /></el-icon>
          </span>
          <span class="cd__trigger-t">在「{{ circle.name }}」分享点什么…</span>
        </button>

        <!--
          非成员:**不渲染发帖框**,改显示说明 + 加入按钮(§3 权限不足)。
          禁止「点了才弹说你没权限」。
        -->
        <div v-else class="cd__gate">
          <span class="cd__gate-text">加入圈子后就能在圈里发帖</span>
          <button type="button" class="cd__gate-btn" @click="join">加入圈子</button>
        </div>

        <!--
          帖子卡（2026-10-21 管理操作从卡片下方的色带改成右上角「⋯」）。

          【为什么改】原来每条帖子下面都挂一条 40px 高的米色操作带（置顶/下架/移出），
          只有圈主与管理员看得到。对这些人来说，帖子流里每隔一条就横一条色带 ——
          翻 10 条帖子就有 10 条色带，真正的内容被切得七零八落。
          现在收进一个「⋯」按钮：位置固定在卡片右上角，不占流，需要时才展开。
        -->
        <div v-for="p in posts" :key="p.id" class="cd__post">
          <PostCard
            :post="p"
            @click="router.push(`/post/${p.id}`)"
          />
          <!--
            管理入口：仅 myRole<=2 且该帖确实属于本圈时渲染。
            判断 p.circleId 是因为圈主也能在别处看到自己圈的帖子（比如首页），
            但「移出该圈」只对圈内帖子有意义。
          -->
          <button
            v-if="canManage && p.circleId"
            type="button"
            class="cd__post-more"
            :aria-label="`管理这条帖子：${p.content?.slice(0, 16) || ''}`"
            @click.stop="openPostMenu(p)"
          >
            <el-icon :size="16"><MoreFilled /></el-icon>
          </button>
        </div>

        <!--
          空态（AC-19：必须有可点行动按钮）。
          【2026-10-21】title 原来两个分支写的是同一句 —— 只有 description 区分，
          等于白写一个三元。现在按角色给不同的标题：
          成员看到的是「你可以发」，非成员看到的是「加入后能做什么」。
        -->
        <StateBlock
          v-if="!postsLoading && posts.length === 0"
          variant="empty"
          :title="canCreate ? '还没有人发过帖子' : '这个圈子还没人说话'"
          :description="canCreate ? '你可以是这个圈子的第一个' : '加入圈子后就能发第一条'"
          :action-label="canCreate ? '写第一条' : '加入圈子'"
          :icon="ChatLineSquare"
          @action="onEmptyAction"
        />
        <SkeletonList v-if="postsLoading" :count="3" />
      </div>

      <!-- 活动 Tab:整块抽到 CircleActivities.vue,那边自带格式化与空态 -->
      <CircleActivities
        v-show="tab === 'activities'"
        role="tabpanel"
        :activities="activities"
        :loading="actsLoading"
        :can-create="canCreate"
        @new="openActForm"
        @open="(a) => router.push(`/activity/${a.id}`)"
        @action="onEmptyAction"
      />
        </div><!-- /.cd__main -->

        <!--
          桌面端右栏（2026-10-21 新增）。
          手机端由 CSS 隐藏 —— 窄屏没有横向空间，硬塞会变成上下堆叠、
          把内容推到屏幕外。数据全部来自 CircleVO 与已加载列表，不新增请求。
        -->
        <CircleDetailAside
          :member-count="circle.memberCount"
          :post-count="circle.postCount"
          :act-count="activities.length"
          :create-time="circle.createTime"
          :owner-id="circle.owner?.id ?? circle.ownerId"
          :owner-nickname="circle.owner?.nickname || ''"
          :owner-avatar="circle.owner?.avatarUrl || ''"
        />
      </div><!-- /.cd__layout -->
    </template>

    <!--
      发帖浮层（手机底部抽屉 / 桌面居中弹窗）。
      与上面那个触发条共享同一份 form 状态，所以放在页面末尾也没关系 ——
      它靠 v-model 开合，不占布局位置。
    -->
    <CircleComposer
      v-if="circle"
      ref="composerRef"
      v-model="composerOpen"
      v-model:content="postContent"
      v-model:media="postMedia"
      v-model:uploading="uploading"
      :circle-name="circle.name"
      :posting="posting"
      :error="postError"
      @submit="submitPost"
    />

    <ActivityForm
      v-model="showActForm"
      :form="actForm"
      :submitting="creatingAct"
      :error="actError"
      @submit="submitActivity"
    />

    <!--
      帖子管理操作表（2026-10-21 取代卡片下方那条常驻色带）。
      复用全站的 ActionSheet：它自带遮罩、Esc 关闭、取消区与底部安全区，
      与「长按消息」「举报」等所有操作表长得一样，用户不需要重新学。
    -->
    <ActionSheet v-model="postMenuOpen" title="管理这条帖子">
      <button type="button" class="asheet__item" @click="runPostAction('top')">
        {{ postMenuTarget?.isTop ? '取消置顶' : '置顶这条' }}
      </button>
      <button type="button" class="asheet__item" @click="runPostAction('takedown')">
        下架这条
      </button>
      <button type="button" class="asheet__item asheet__item--danger" @click="runPostAction('move')">
        移出该圈
      </button>
    </ActionSheet>
  </div>
</template>

<script setup>
/**
 * 圈子详情(CircleDetail.vue.md)
 * 原 372 行的三处硬编码已改:
 *   - el-dialog width="480px"(:105)-> 移动端 el-drawer 全屏,桌面保留 480px
 *   - 两个 el-date-picker 并排(:115,118)-> 各占满一行
 *   - label-position 默认 left -> 手机 top(红线 3)
 *
 * 2026-10-15 拆分:数据加载(圈子/帖子/活动)与加入/退出搬去
 * composables/useCircleDetail.js(失败语义原样搬迁),本文件从 351 行
 * 降到约 220 行,只留编排:tab、权限计算、两个写操作 composable 的接线。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  WarningFilled, ChatLineSquare, EditPen, MoreFilled,
} from '@element-plus/icons-vue'
import { useCircleWrites } from '@/composables/useCircleWrites'
import { useCircleAdminActions } from '@/composables/useCircleAdminActions'
import { useCircleDetail } from '@/composables/useCircleDetail'
import { useUserStore } from '@/stores/user'
import PostCard from '@/components/PostCard.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import CircleHeader from '@/components/circle/CircleHeader.vue'
import CircleComposer from '@/components/circle/CircleComposer.vue'
import CircleDetailAside from '@/components/circle/CircleDetailAside.vue'
import CircleActivities from '@/components/circle/CircleActivities.vue'
import ActivityForm from '@/components/circle/ActivityForm.vue'
import CircleManagePanel from '@/components/circle/CircleManagePanel.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { name: 'posts', label: '帖子' },
  { name: 'activities', label: '活动' },
]

const tab = ref('posts')

/** 发帖浮层开合（2026-10-21：从常驻框改成按需打开） */
const composerOpen = ref(false)
const composerRef = ref(null)

/** 帖子管理操作表：当前操作的那条 + 开合（2026-10-21） */
const postMenuOpen = ref(false)
const postMenuTarget = ref(null)

function openPostMenu(p) {
  postMenuTarget.value = p
  postMenuOpen.value = true
}

/**
 * 执行管理动作。
 *
 * 先关表再执行：动作（置顶/下架/移出）成功后列表会重拉，
 * 如果表还开着，用户会看到「操作表浮在已经刷新的列表上」，
 * 分不清刚才那一下到底生效没有。
 */
function runPostAction(kind) {
  const p = postMenuTarget.value
  postMenuOpen.value = false
  if (!p) return
  if (kind === 'top') act.top(p)
  else if (kind === 'takedown') act.takedown(p)
  else if (kind === 'move') act.move(p, null)
}

/** 点触发条打开浮层 */
function openComposer() {
  composerOpen.value = true
}

const circleId = computed(() => route.params.id)

/* ---- 圈子数据 + 成员操作(2026-10-15 拆去 useCircleDetail) ---- */
const {
  circle, posts, activities,
  loading, error, errorMsg, notFound, postsLoading, actsLoading, joining, leaving,
  load, loadPosts, loadActivities, join, leave,
} = useCircleDetail(circleId)

/** 只有圈内成员能发帖/发活动(§3 权限不足) */
const canCreate = computed(() => !!circle.value?.joined)

// ---------------- 圈管理 / 吧主级权限(role<=2) ----------------
const showManage = ref(false)
const userStore = useUserStore()
const meId = computed(() => userStore.userInfo?.id || 0)
/** myRole:1 圈主 / 2 圈管理;3 成员 / 0 非成员无管理入口 */
const canManage = computed(() => {
  const r = Number(circle.value?.myRole)
  return r === 1 || r === 2
})
/** 圈内被封禁(软封禁):保留查看,禁发帖/评论/点赞。写口后端 422 */
const banned = computed(() => !!circle.value?.myBanned)
/** 帖子操作:置顶/下架/移帖,成功后重载圈内帖子 */
const act = useCircleAdminActions(circleId, loadPosts, null)

const {
  postContent, postMedia, posting, uploading, postError, submitPost: rawSubmitPost,
  showActForm, creatingAct, actError, actForm, openActForm, submitActivity,
} = useCircleWrites(circleId, canCreate, loadPosts, loadActivities, () => {
  tab.value = 'activities'
})

/**
 * 发布成功后关掉浮层（2026-10-21）。
 *
 * 为什么包一层而不是改 useCircleWrites：那个 composable 不知道「浮层」这回事 ——
 * 它只负责发请求、清草稿、刷新列表。开合是视图的状态，留在视图里。
 *
 * 失败时**不关**：草稿还在，用户要能马上再点一次发布（composer 内的错误文案就在眼前）。
 */
async function submitPost() {
  await rawSubmitPost()
  if (!postError.value) composerOpen.value = false
}

/**
 * 空态按钮：成员去发帖/发活动，非成员去加入。
 *
 * 2026-10-21：发帖入口从「聚焦常驻输入框」改成「打开浮层」——
 * 输入框已经不在页面里了，focus() 无从谈起。
 * 但仍保留「点完就能开始写」的承诺：浮层打开后会自动聚焦 textarea
 * （见 CircleComposer 的 watch），用户点完直接打第一个字。
 */
function onEmptyAction() {
  if (!canCreate.value) {
    join()
    return
  }
  if (tab.value === 'activities') {
    openActForm()
    return
  }
  openComposer()
}

/** 解散成功:圈子已软删,留在原地会进 notFound 卡住,跳回圈子列表 */
function onDisband() {
  showManage.value = false
  router.push('/circles')
}

watch(circleId, load)
onMounted(load)
</script>

<style scoped src="./CircleDetail.css"></style>
