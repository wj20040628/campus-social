<template>
  <div class="detail">
    <!-- 行 1:顶栏。返回 + 标题 + 更多 -->
    <header class="detail__bar">
      <button type="button" class="detail__back" aria-label="返回" @click="router.back()">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <h1 class="detail__title">帖子详情</h1>
      <button
        v-if="post"
        type="button"
        class="detail__more"
        aria-label="更多操作"
        @click="moreOpen = true"
      >
        <el-icon :size="20"><MoreFilled /></el-icon>
      </button>
    </header>

    <!--
      帖子不存在/已下架:整页替换(PostDetail.vue.md §3)

      用 v-if 而不是 v-else-if:上面的 <header> 没有 v-if,
      v-else-if 紧邻 v-if/else 的约束被打破会让整条 else 链拿不到 codegen 节点,
      编译期直接抛 "Cannot read properties of undefined (reading 'type')"。
      loadFailed 与 loading 本就是互斥布尔(拉失败时 loading 必为 false),
      独立判断的语义与 else 链完全一致。
    -->
    <StateBlock
      v-if="loadFailed"
      class="detail__state"
      variant="error"
      title="这篇帖子不见了"
      description="可能已经被删掉，或者链接错了"
      action-label="回首页"
      :icon="WarningFilled"
      @action="router.push('/')"
    />

    <!-- 加载骨架:作者行 + 正文 + 3 条评论。形状对齐真实内容 -->
    <div v-else-if="loading" class="detail__loading" aria-hidden="true">
      <SkeletonList variant="audit" :count="1" />
      <SkeletonList variant="panel" :count="5" />
      <SkeletonList variant="post" :count="3" />
    </div>

    <template v-else-if="post">
      <!-- 行 2~4:作者行 + 正文 + 话题 + 媒体(纯展示,无交互状态) -->
      <PostBody
        :post="post"
        :topics="topics"
        :media-list="mediaList"
        :img-list="imgList"
        :cols="gridCols"
        :img-index="imgIndex"
        @pick-topic="goTopic"
      />

      <!-- 行 6:操作条。点赞可切换(aria-pressed),作者本人见编辑/删除 -->
      <div class="ops">
        <button
          type="button"
          class="ops__btn"
          :class="{ 'ops__btn--on': post.liked }"
          :aria-pressed="post.liked"
          :aria-label="`点赞，${post.likeCount}`"
          @click="toggleLike"
        >
          <el-icon :size="20"><StarFilled v-if="post.liked" /><Star v-else /></el-icon>
          <span>{{ post.liked ? '已赞' : '赞' }}</span>
          <span class="ops__count">{{ cap(post.likeCount) }}</span>
        </button>

        <span class="ops__btn ops__btn--static">
          <el-icon :size="20"><ChatLineSquare /></el-icon>
          <span>评论</span>
          <span class="ops__count">{{ cap(post.commentCount) }}</span>
        </span>

        <!-- 作者本人:编辑/删除。他人不渲染(不是置灰,是根本不出现) -->
        <template v-if="isAuthor">
          <button v-if="post.status === 1" type="button" class="ops__btn" @click="goEdit">
            <el-icon :size="20"><EditPen /></el-icon>
            <span>编辑</span>
          </button>
          <button type="button" class="ops__btn ops__btn--danger" @click="remove">
            <el-icon :size="20"><Delete /></el-icon>
            <span>删除</span>
          </button>
        </template>
        <!-- 他人:搭句话主按钮。内部自己判未认证/额度用尽(设计 §3 四态) -->
        <GreetButton v-else :peer-id="authorId" @need-verify="verifyOpen = true" />
      </div>

      <!-- 行 8:评论区。树在 PostComments 内部建,页面只传扁平列表 -->
      <PostComments
        :comments="comments"
        :total="post.commentCount"
        :my-id="myId"
        :post-id="route.params.id"
        @ask="askComment"
        @go-user="goUser"
        @reply="startReply"
      />

      <!--
        评论列表底部留白:保证最后一条不被吸底输入区盖住。
        键盘弹起时 --kb-h 已经把输入区顶上去了,这里的固定留白会显得多余,
        所以用恒定垫板而不是条件切换 —— 跳动比多留一截空白更伤。
      -->
      <div class="detail__tail" aria-hidden="true" />

      <!-- 行 9:吸底评论输入区。replyTo 承接评论区的「回复」 -->
      <CommentComposer
        ref="composerRef"
        :my-id="myId"
        :reply-to="replyTo"
        :submit="postComment"
        @cancel-reply="replyTo = null"
      />
    </template>

    <!--
      浮层组(更多 / 举报 / 未认证)。三层绑在一起,接力逻辑在组件内部 ——
      「关更多、开举报」这种跨层关系散在页面里要跨几百行才看得清。
    -->
    <PostSheets
      v-model:more-open="moreOpen"
      v-model:report-open="reportOpen"
      v-model:verify-open="verifyOpen"
      :post-id="post?.id"
      :author-id="authorId"
      :is-author="isAuthor"
      @go-verify="router.push('/profile')"
      @author-hidden="router.push('/')"
    />
  </div>
</template>

<script setup>
/**
 * 帖子详情(PostDetail.vue.md)—— 本页只做**编排**。
 *
 * 拆出去的四块,各自的**真实**理由(不是「行数超标就切」):
 *   PostComments.vue  评论区空态的视觉权重刻意低于页面级空态,混在一起改样式会改串
 *   PostSheets.vue    三层浮层的开关是接力关系(关上层开下层),绑在一起才看得清
 *   PostBody.vue      作者行/正文/话题/媒体是纯展示,没有任何交互状态
 *   usePostDetailActions.js  请求与失败语义
 *   CommentComposer   @ 提及输入状态机 + 键盘让位
 *   usePostDetail.js  媒体解析、计数封顶、评论切片等纯计算
 */
import { ref, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { capCount as cap, usePostMedia } from '@/composables/usePostDetail'
import { usePostDetailActions } from '@/composables/usePostDetailActions'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import CommentComposer from '@/components/post/CommentComposer.vue'
import GreetButton from '@/components/post/GreetButton.vue'
import PostBody from '@/components/post/PostBody.vue'
import PostComments from '@/components/post/PostComments.vue'
import PostSheets from '@/components/post/PostSheets.vue'
import {
  ArrowLeft, ChatLineSquare, Delete, EditPen, MoreFilled, Star, StarFilled, WarningFilled,
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const { post, comments, loading, loadFailed, authorId, topics, load, toggleLike, postComment, remove } =
  usePostDetailActions(route, router)

const moreOpen = ref(false)
const reportOpen = ref(false)
const verifyOpen = ref(false)
const composerRef = ref(null)
/** 正在回复的评论节点。由评论区 emit 上来,原样传给 CommentComposer 显示「回复 @xxx」 */
const replyTo = ref(null)

const myId = computed(() => userStore.userInfo?.id)
const isAuthor = computed(() => !!post.value && post.value.author?.id === myId.value)

// 媒体解析仍在本页:PostBody 只收已解析好的 mediaList/cols/imgList,
// 不再自己调 usePostMedia —— 解析规则(几个图排几列)是数据问题不是展示问题。
const { mediaList, imgList, cols: gridCols, imgIndex } = usePostMedia(post)

function goEdit() {
  router.push(`/post/${route.params.id}/edit`)
}

function goUser(id) {
  if (id) router.push(`/user/${id}`)
}

function goTopic(name) {
  router.push(`/topic/${encodeURIComponent(name)}`)
}

/**
 * 开始回复。层级不在这里算 —— 算它要回溯整棵树,
 * 由 postComment 在提交时用最新列表算(见 usePostDetailActions)。
 * 这里只做两件事:记住目标、把输入框叫醒。
 */
function startReply(node) {
  replyTo.value = node
  composerRef.value?.focus()
}

/** 空态的「说一句」:光标送进吸底输入框,并把最后一条评论滚进视野 */
function askComment() {
  replyTo.value = null
  composerRef.value?.focus()
  document.querySelector('.detail__tail')?.scrollIntoView({ behavior: 'smooth' })
}

// 帖子换页(路由复用同一个组件实例)时清掉回复态,否则会拿着上一篇的节点去回复
watch(() => route.params.id, () => { replyTo.value = null })

onMounted(load)
</script>

<style scoped src="./PostDetail.css"></style>