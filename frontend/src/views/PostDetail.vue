<template>
  <div class="detail">
    <!-- 行 1:顶栏。返回 + 标题 + 更多 -->
    <header class="detail__bar">
      <button type="button" class="detail__back" aria-label="返回" @click="router.back()">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <h1 class="detail__title">帖子详情</h1>
      <!-- 游客态(2026-10-25):扫码进来的游客在本页找不到任何登录入口,
           主动想注册的人反而没门——顶栏给一枚显眼出口,带 returnUrl 闭环 -->
      <button
        v-if="!userStore.hasToken"
        type="button"
        class="detail__login"
        @click="router.push({ path: '/login/form', query: { returnUrl: route.fullPath } })"
      >
        登录
      </button>
      <!-- 分享卡片(2026-10-25):扫码进来的人免登录可读(后端已放游客 GET) -->
      <button
        v-if="post && post.status === 1"
        type="button"
        class="detail__more detail__share"
        :aria-label="`生成分享卡片，已分享 ${post.shareCount || 0} 次`"
        @click="shareOpen = true"
      >
        <el-icon :size="18"><Share /></el-icon>
        <span class="detail__share-n ys-num">{{ cap(post.shareCount || 0) }}</span>
      </button>
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

      <!--
        行 6：操作条。点赞可切换(aria-pressed)，作者本人见编辑/删除。

        2026-10-21 收紧：375px 下四个按钮 + 图标 + 数字本来放不下，
        原来「已赞」占两字宽，会把最右边的「删除」整颗挤出屏幕
        （加了 nowrap 之后不再竖排，但溢出的部分直接被裁掉）。
        现在：
          - 点赞态不再写「已赞」两字，靠**实心图标 + 强调色**表达已赞，
            数字保留（aria-label 仍然完整朗读「已赞」）
          - 图标 20 → 15，与卡片底部互动行的图标口径一致
          - 计数用 tabular-nums，变化时按钮宽度不抖
      -->
      <div class="ops">
        <button
          type="button"
          class="ops__btn"
          :class="{ 'ops__btn--on': post.liked }"
          :aria-pressed="post.liked"
          :aria-label="`${post.liked ? '取消点赞' : '点赞'}，${post.likeCount}`"
          @click="toggleLike"
        >
          <!-- 点赞用自绘 ThumbUp(2026-10-24 对齐 PostCard):Star 已定为「收藏」图标 -->
          <ThumbUp :size="16" :filled="!!post.liked" />
          <span class="ops__count">{{ cap(post.likeCount) }}</span>
        </button>

        <!-- 收藏(2026-10-24 补):Feed 卡有收藏、详情页却没有,不一致。
             点赞同时换成自绘 ThumbUp(对齐 PostCard):Star 已定为「收藏」图标。 -->
        <button
          type="button"
          class="ops__btn"
          :class="{ 'ops__btn--on': post.favorited }"
          :aria-pressed="post.favorited"
          :aria-label="`${post.favorited ? '取消收藏' : '收藏'}，${post.favoriteCount || 0}`"
          @click="toggleFavorite"
        >
          <el-icon :size="15"><StarFilled v-if="post.favorited" /><Star v-else /></el-icon>
          <span class="ops__count">{{ cap(post.favoriteCount || 0) }}</span>
        </button>

        <span class="ops__btn ops__btn--static">
          <el-icon :size="15"><ChatLineSquare /></el-icon>
          <span class="ops__count">{{ cap(post.commentCount) }}</span>
        </span>

        <!-- 作者本人:编辑/删除。他人不渲染(不是置灰,是根本不出现)。
             树洞匿名帖 author 被后端抹掉,本人判定靠 PostVO.mine -->
        <template v-if="isAuthor">
          <button v-if="post.status === 1" type="button" class="ops__btn" @click="goEdit">
            <el-icon :size="15"><EditPen /></el-icon>
            <span>编辑</span>
          </button>
          <button type="button" class="ops__btn ops__btn--danger" @click="remove">
            <el-icon :size="15"><Delete /></el-icon>
            <span>删除</span>
          </button>
        </template>
        <!-- 他人:搭句话主按钮。内部自己判未认证/额度用尽(设计 §3 四态)。
             匿名帖没有可搭话的对象(authorId 为空),不渲染 -->
        <GreetButton v-else-if="authorId" :peer-id="authorId" @need-verify="verifyOpen = true" />
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
        @delete="onDeleteComment"
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

    <!-- 分享卡片:卡片内容由已加载的帖子数据拼装,匿名帖作者行显示「匿名」 -->
    <ShareCardSheet v-model:open="shareOpen" :spec="shareSpec" @shared="onShared" />
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
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import { shareUrl } from '@/utils/shareCard'
import { sharePost } from '@/api/feed'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import CommentComposer from '@/components/post/CommentComposer.vue'
import GreetButton from '@/components/post/GreetButton.vue'
import PostBody from '@/components/post/PostBody.vue'
import { ElMessageBox } from '@/utils/feedback'
import PostComments from '@/components/post/PostComments.vue'
import ThumbUp from '@/icons/ThumbUp.vue'
import PostSheets from '@/components/post/PostSheets.vue'
import ShareCardSheet from '@/components/share/ShareCardSheet.vue'
import {
  ArrowLeft, ChatLineSquare, Delete, EditPen, MoreFilled, Share, Star, StarFilled, WarningFilled } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const { post, comments, loading, loadFailed, authorId, topics, load, toggleLike, toggleFavorite, postComment, remove, removeComment } =
  usePostDetailActions(route, router)

const moreOpen = ref(false)
const reportOpen = ref(false)
const verifyOpen = ref(false)
const composerRef = ref(null)
/** 正在回复的评论节点。由评论区 emit 上来,原样传给 CommentComposer 显示「回复 @xxx」 */
const replyTo = ref(null)

const myId = computed(() => userStore.userInfo?.id)
/*
 * 本人判定:常规帖比 author.id;树洞匿名帖的 author 被后端整体抹掉,
 * 靠服务端下发的 mine 维持编辑/删除入口(mine 只有作者本人为 true)。
 * myId 必须先判空:游客(未登录)两者都是 undefined,严格相等成立,
 * 会把匿名帖误判成「我是作者」而渲染编辑/删除(2026-10-25 游客实测)。
 */
const isAuthor = computed(() => {
  if (!post.value || !myId.value) return false
  return post.value.author?.id === myId.value || post.value.mine === true
})

// 媒体解析仍在本页:PostBody 只收已解析好的 mediaList/cols/imgList,
// 不再自己调 usePostMedia —— 解析规则(几个图排几列)是数据问题不是展示问题。
const { mediaList, imgList, cols: gridCols, imgIndex } = usePostMedia(post)

/* 分享卡片:从已加载的帖子拼 spec;匿名帖作者行写「匿名」,卡片上同样不露身份 */
const shareOpen = ref(false)
const shareSpec = computed(() => {
  const p = post.value
  if (!p) return null
  const firstImg = mediaList.value.find((m) => m.type === 'img')
  return {
    kind: 'post',
    title: String(p.content || '').slice(0, 120),
    authorName: p.anonymous ? '匿名' : (p.author ? displayName(p.author) : ''),
    topics: p.topics || [],
    image: firstImg ? resolveUrl(firstImg.url) : null,
    qrText: shareUrl(`/post/${p.id}`),
    fileName: `yousheng-post-${p.id}.png`,
  }
})

function goEdit() {
  router.push(`/post/${route.params.id}/edit`)
}

/**
 * 保存/分享卡片成功后 +1。
 *
 * 只在成功保存或系统分享返回后触发；打开面板不算。
 * 游客也能分享，但不计数（接口需要登录，避免匿名刷数）。
 * 先本地 +1 再发请求，失败回滚到改之前的旧值。
 */
async function onShared() {
  if (!userStore.hasToken || !post.value?.id) return
  const before = post.value.shareCount || 0
  post.value.shareCount = before + 1
  try {
    await sharePost(post.value.id)
  } catch {
    post.value.shareCount = before
  }
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
/**
 * 删除自己的评论(2026-10-24):确认后调接口。
 * 文案明示子回复一起移除——树级删除的后果必须让用户在确认时就知情。
 */
async function onDeleteComment(node) {
  try {
    await ElMessageBox.confirm(
      '这条评论和它下面的回复都会被删除,无法恢复。',
      '删除评论',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '再想想' },
    )
  } catch {
    return
  }
  await removeComment(node.id)
}

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