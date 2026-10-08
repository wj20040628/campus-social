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
      description="可能是网络断了,点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="load()"
    />

    <template v-else-if="circle">
      <!-- 行 2:圈子头 -->
      <CircleHeader :circle="circle" :joining="joining" @join="join" />

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
        <CircleComposer
          v-if="canCreate"
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

        <PostCard
          v-for="p in posts"
          :key="p.id"
          :post="p"
          @click="router.push(`/post/${p.id}`)"
        />

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
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import {
  WarningFilled, ChatLineSquare,
} from '@element-plus/icons-vue'
import {
  detail as detailApi,
  join as joinApi,
  circlePosts,
  activitiesByCircle,
} from '@/api/circle'
import { ElMessage } from '@/utils/feedback'
import { useCircleWrites } from '@/composables/useCircleWrites'
import PostCard from '@/components/PostCard.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import CircleHeader from '@/components/circle/CircleHeader.vue'
import CircleComposer from '@/components/circle/CircleComposer.vue'
import CircleActivities from '@/components/circle/CircleActivities.vue'
import ActivityForm from '@/components/circle/ActivityForm.vue'

const route = useRoute()
const router = useRouter()

const TABS = [
  { name: 'posts', label: '帖子' },
  { name: 'activities', label: '活动' },
]

const circle = ref(null)
const posts = ref([])
const activities = ref([])
const tab = ref('posts')

const loading = ref(true)
const error = ref(false)
const notFound = ref(false)
const postsLoading = ref(false)
const actsLoading = ref(false)
const joining = ref(false)

const composerRef = ref(null)

/** 只有圈内成员能发帖/发活动(§3 权限不足) */
const canCreate = computed(() => !!circle.value?.joined)

const circleId = computed(() => route.params.id)

async function load() {
  loading.value = true
  error.value = false
  notFound.value = false
  try {
    circle.value = await detailApi(circleId.value)
    // 圈子存在但接口返回 null:同样按「不存在」处理,不然整个页面永远空着
    if (!circle.value) {
      notFound.value = true
      return
    }
  } catch (e) {
    // 404 与网络错误分开:前者是「圈子没了」,后者是「没加载出来」
    if (e?.response?.status === 404) notFound.value = true
    else error.value = true
    return
  } finally {
    loading.value = false
  }
  // 圈子加载成功后再拉内容,两个并发
  await Promise.allSettled([loadPosts(), loadActivities()])
}

async function loadPosts() {
  postsLoading.value = true
  try {
    const data = await circlePosts(circleId.value, null, 50)
    posts.value = (data?.list || []).map((p) => ({
      ...p,
      createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
    }))
  } catch {
    // 内容拉失败保留空列表 + 让空态的出口是「重试」而不是「发第一条」
    posts.value = []
    ElMessage.error('帖子没拉出来,下拉重试')
  } finally {
    postsLoading.value = false
  }
}

async function loadActivities() {
  actsLoading.value = true
  try {
    const data = await activitiesByCircle(circleId.value, null, 50)
    activities.value = data?.list || []
  } catch {
    activities.value = []
  } finally {
    actsLoading.value = false
  }
}

async function join() {
  joining.value = true
  try {
    await joinApi(circleId.value)
    // 本地改 joined 而不是重拉整个圈子:一次加入不该让页面闪一下
    circle.value.joined = true
    ElMessage.success('已加入圈子')
  } catch {
    ElMessage.error('没加上,网络好像断了')
  } finally {
    joining.value = false
  }
}

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

watch(circleId, load)
onMounted(load)
</script>

<style scoped src="./CircleDetail.css"></style>
