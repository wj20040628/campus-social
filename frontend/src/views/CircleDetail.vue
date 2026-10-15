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

        <CircleComposer
          v-else-if="canCreate"
          ref="composerRef"
          v-model:content="postContent"
          v-model:media="postMedia"
          v-model:uploading="uploading"
          :circle-name="circle.name"
          :posting="posting"
          :error="postError"
          @submit="submitPost"
        />

        <!--
          非成员:**不渲染发帖框**,改显示说明 + 加入按钮(§3 权限不足)。
          禁止「点了才弹说你没权限」。
        -->
        <div v-else class="cd__gate">
          <span class="cd__gate-text">加入圈子后就能在圈里发帖</span>
          <button type="button" class="cd__gate-btn" @click="join">加入圈子</button>
        </div>

        <div v-for="p in posts" :key="p.id">
          <PostCard
            :post="p"
            @click="router.push(`/post/${p.id}`)"
          />
          <!-- 吧主级操作条:仅 myRole<=2 且该帖为圈帖时露出 -->
          <div v-if="canManage && p.circleId" class="cd__post-actions">
            <button type="button" @click="act.top(p)">{{ p.isTop ? '取消置顶' : '置顶' }}</button>
            <button type="button" @click="act.takedown(p)">下架</button>
            <button type="button" @click="act.move(p, null)">移出该圈</button>
          </div>
        </div>

        <!-- 空态:必须有可点行动按钮(AC-19)。原 el-empty 默认描述违反此条 -->
        <StateBlock
          v-if="!postsLoading && posts.length === 0"
          variant="empty"
          :title="canCreate ? '这个圈子还没人说话' : '这个圈子还没人说话'"
          :description="canCreate ? '发第一条,别人就有得看了' : '加入圈子后就能发第一条'"
          :action-label="canCreate ? '发第一条' : '加入圈子'"
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
    </template>

    <ActivityForm
      v-model="showActForm"
      :form="actForm"
      :submitting="creatingAct"
      :error="actError"
      @submit="submitActivity"
    />
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
  WarningFilled, ChatLineSquare,
} from '@element-plus/icons-vue'
import { useCircleWrites } from '@/composables/useCircleWrites'
import { useCircleAdminActions } from '@/composables/useCircleAdminActions'
import { useCircleDetail } from '@/composables/useCircleDetail'
import { useUserStore } from '@/stores/user'
import PostCard from '@/components/PostCard.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import CircleHeader from '@/components/circle/CircleHeader.vue'
import CircleComposer from '@/components/circle/CircleComposer.vue'
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

const composerRef = ref(null)

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
  postContent, postMedia, posting, uploading, postError, submitPost,
  showActForm, creatingAct, actError, actForm, openActForm, submitActivity,
} = useCircleWrites(circleId, canCreate, loadPosts, loadActivities, () => {
  tab.value = 'activities'
})

/** 空态的按钮:成员去发帖/发活动,非成员去加入 */
function onEmptyAction() {
  if (!canCreate.value) {
    join()
    return
  }
  if (tab.value === 'activities') {
    openActForm()
    return
  }
  // 聚焦发帖框而不是只滚过去:空态按钮的承诺是「开始写」
  composerRef.value?.focus()
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
