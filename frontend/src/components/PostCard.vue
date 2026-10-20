<template>
  <!--
    A 方向(温暖人文)feed 卡片,2026-10-14 重写。要点:
    自绘卡片(圆角 18 + 暖描边,走 token);无头像用彩色首字母圆;
    点赞/评论是可点胶囊,点赞接现有 like/unlike;搭话复用 GreetButton;
    图片自适应网格(1 图 16:10,2 图双列,3 图以上三列),并修掉旧版
    video 的 poster 误用视频地址的 bug。props/emits 契约不变。
  -->
  <article class="pc" @click="$emit('click', post)">
    <header class="pc__head">
      <span class="pc__ava" @click.stop="goAuthor">
        <InitialAvatar :size="40" :src="post.author?.avatarUrl ? resolveThumb(post.author.avatarUrl) : ''" :seed="post.author?.id" :name="post.author ? displayName(post.author) : ''" />
      </span>
      <span class="pc__who" @click.stop="goAuthor">
        <span class="pc__name">{{ post.author ? displayName(post.author) : '匿名' }}</span>
        <span class="pc__time ys-num">{{ post.createTime }}</span>
      </span>
      <span v-if="post.circleId" class="pc__ctag">圈子</span>
      <span v-if="post.status === 0" class="pc__tag pc__tag--warn">审核中</span>
      <span v-else-if="post.status === 2" class="pc__tag pc__tag--danger">已驳回</span>
      <span v-if="post.isTop" class="pc__tag pc__tag--top">置顶</span>
    </header>

    <div class="pc__body">
      <p class="pc__text">{{ post.content }}</p>
      <div v-if="post.topics?.length" class="pc__topics">
        <button v-for="t in post.topics" :key="t" type="button" class="pc__tpic" @click.stop="goTopic(t)">#{{ t }}</button>
      </div>
      <div v-if="mediaList.length" class="pc__media" :class="'pc__media--n' + Math.min(mediaList.length, 3)">
        <template v-for="(m, i) in mediaList" :key="i">
          <el-image v-if="m.type === 'img'" :src="resolveThumb(m.url)" :preview-src-list="imgList" :initial-index="i" fit="cover" class="pc__img" loading="lazy" @click.stop />
          <video v-else :src="resolveUrl(m.url)" controls class="pc__video" @click.stop></video>
        </template>
      </div>
    </div>

    <footer class="pc__foot">
      <!-- 点赞用自绘 ThumbUp:EP 图标库没有大拇指,而 Star 已定为「收藏」,
           两个星星挨在一起用户分不清「赞」和「存」 -->
      <button
        type="button"
        class="pc__act"
        :class="{ 'pc__act--on': post.liked }"
        :aria-pressed="post.liked ? 'true' : 'false'"
        :aria-label="post.liked ? '取消点赞' : '点赞'"
        @click.stop="toggleLike"
      >
        <ThumbUp :size="16" :filled="!!post.liked" />
        <span class="ys-num">{{ post.likeCount || 0 }}</span>
      </button>
      <button
        type="button"
        class="pc__act"
        :class="{ 'pc__act--on': post.favorited }"
        :aria-pressed="post.favorited ? 'true' : 'false'"
        :aria-label="post.favorited ? '取消收藏' : '收藏'"
        @click.stop="toggleFavorite"
      >
        <el-icon :size="15"><StarFilled v-if="post.favorited" /><Star v-else /></el-icon>
        <span class="ys-num">{{ post.favoriteCount || 0 }}</span>
      </button>
      <button type="button" class="pc__act" aria-label="看评论" @click.stop="goDetail">
        <el-icon :size="15"><ChatLineRound /></el-icon>
        <span class="ys-num">{{ post.commentCount || 0 }}</span>
      </button>
      <span class="pc__view" aria-label="浏览量">
        <el-icon :size="15"><View /></el-icon>
        <span class="ys-num">{{ post.viewCount || 0 }}</span>
      </span>
      <!-- 自己的帖子不渲染搭话;userInfo 未加载完时可能短暂露出,刷新即修正 -->
      <GreetButton v-if="post.author?.id && post.author.id !== userStore.userInfo?.id" :peer-id="post.author.id" class="pc__greet" @need-verify="onNeedVerify" @error="onGreetError" />
    </footer>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Star, StarFilled, ChatLineRound, View } from '@element-plus/icons-vue'
import ThumbUp from '@/icons/ThumbUp.vue'
import GreetButton from '@/components/post/GreetButton.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import { like, unlike, favorite, unfavorite } from '@/api/feed'
import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/feedback'

const props = defineProps({ post: { type: Object, required: true } })
defineEmits(['click', 'topic'])

const router = useRouter()
const userStore = useUserStore()

function goDetail() { router.push(`/post/${props.post.id}`) }
function goAuthor() { if (props.post.author?.id) router.push(`/user/${props.post.author.id}`) }
function goTopic(name) { router.push(`/topic/${encodeURIComponent(name)}`) }

/** feed 内直接点赞。乐观更新 + 失败回滚,与 usePostDetailActions.toggleLike 同逻辑:
 *  回滚恢复改之前的旧值,不做「减一」推算 —— 后端可能有并发点赞,真值以服务端为准 */
async function toggleLike() {
  const wasLiked = !!props.post.liked
  const wasCount = Number(props.post.likeCount) || 0
  props.post.liked = !wasLiked
  props.post.likeCount = Math.max(0, wasCount + (wasLiked ? -1 : 1))
  try {
    if (wasLiked) await unlike(props.post.id)
    else await like(props.post.id)
  } catch {
    props.post.liked = wasLiked
    props.post.likeCount = wasCount
    ElMessage.error('没点上,网络好像断了')
  }
}

/** GreetButton 回执:未认证给可操作的提示,发送失败给一句人话 */
function onNeedVerify() { ElMessage.warning('先完成学号认证才能搭话,去「我的 - 编辑资料」') }
function onGreetError() { ElMessage.error('没搭上,对方可能关了陌生人消息') }

/**
 * 收藏/取消收藏。与 toggleLike 完全同构:乐观更新 + 失败回滚到改之前的旧值。
 *
 * 回滚用「旧值」而不是「减一」:后端可能同时有别人在点,
 * 减一推算出来的数未必等于数据库真值,回滚必须回到自己改之前看到的那个数。
 */
async function toggleFavorite() {
  const wasFav = !!props.post.favorited
  const wasCount = Number(props.post.favoriteCount) || 0
  props.post.favorited = !wasFav
  props.post.favoriteCount = Math.max(0, wasCount + (wasFav ? -1 : 1))
  try {
    if (wasFav) await unfavorite(props.post.id)
    else await favorite(props.post.id)
  } catch {
    props.post.favorited = wasFav
    props.post.favoriteCount = wasCount
    ElMessage.error('没收藏上,网络好像断了')
  }
}

const mediaList = computed(() => {
  if (!props.post.media) return []
  let m = props.post.media
  if (typeof m === 'string') {
    try { m = JSON.parse(m) } catch { return [] }
  }
  return Array.isArray(m) ? m : []
})
const imgList = computed(() => mediaList.value.filter((x) => x.type === 'img').map((x) => resolveUrl(x.url)))
</script>

<style scoped>
.pc {
  background: var(--ys-surface);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-2xl);
  box-shadow: var(--ys-shadow-1);
  padding: var(--ys-space-4);
  cursor: pointer;
  transition: box-shadow var(--ys-motion-fast) var(--ys-ease-out), border-color var(--ys-motion-fast) var(--ys-ease-out);
}
.pc:hover { border-color: var(--ys-border-control); box-shadow: var(--ys-shadow-2); }

.pc__head { display: flex; align-items: center; gap: var(--ys-space-2); }
.pc__ava { flex: none; cursor: pointer; display: inline-flex; }
.pc__who { flex: 1; min-width: 0; display: flex; flex-direction: column; cursor: pointer; }
.pc__name { font-size: 15px; font-weight: var(--ys-weight-semibold); line-height: 1.3; color: var(--ys-fg); }
.pc__name:hover { color: var(--ys-accent); }
.pc__time { font-size: var(--ys-text-xs); color: var(--ys-meta); margin-top: 1px; }

/* 圈子标签:辅色橙胶囊;置顶用暖墨徽章(官方感),不用彩色描边 */
.pc__ctag, .pc__tag { flex: none; font-size: 11px; line-height: 1; padding: 5px 10px; border-radius: var(--ys-radius-full); }
.pc__ctag { color: var(--ys-accent-2); background: var(--ys-accent-2-soft); }
.pc__tag--warn { color: var(--ys-warning); background: var(--ys-warning-soft); }
.pc__tag--danger { color: var(--ys-danger); background: var(--ys-danger-soft); }
.pc__tag--top { color: var(--ys-accent-on); background: var(--ys-fg); }

.pc__body { margin-top: var(--ys-space-3); }
.pc__text { margin: 0; font-size: var(--ys-text-md); line-height: var(--ys-lh-md); color: var(--ys-fg); white-space: pre-wrap; }
.pc__topics { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--ys-space-2); }
.pc__tpic {
  border: 0; font-family: var(--ys-font-body); font-size: var(--ys-text-xs);
  color: var(--ys-accent); background: var(--ys-accent-soft);
  border-radius: var(--ys-radius-full); padding: 4px 10px; cursor: pointer;
}
.pc__tpic:hover { color: var(--ys-accent-hover); }

.pc__media { display: grid; gap: 6px; margin-top: var(--ys-space-3); }
.pc__media--n1 { grid-template-columns: 1fr; }
.pc__media--n2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.pc__media--n3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.pc__media--n1 .pc__img { aspect-ratio: 16 / 10; }
.pc__media--n2 .pc__img, .pc__media--n3 .pc__img { aspect-ratio: 1; }
.pc__img { width: 100%; border-radius: var(--ys-radius-lg); display: block; }
.pc__video { grid-column: 1 / -1; width: 100%; max-height: 360px; border-radius: var(--ys-radius-lg); background: #000; }

.pc__foot {
  display: flex; flex-wrap: wrap; align-items: center; gap: 2px;
  margin-top: var(--ys-space-3); padding-top: var(--ys-space-2);
  border-top: 1px solid var(--ys-border-soft);
}
/*
  四项互动(赞/收藏/评论/浏览)改为无边框「图标 + 数字」。
  原来每项是带描边的胶囊,两项变四项后 390px 宽度放不下会挤成两行;
  去掉边框后总宽约 235px,和右侧「搭话」按钮正好并排。
  min-height 仍保 44px —— 视觉变轻但触摸目标不能变小。
*/
.pc__act {
  display: inline-flex; align-items: center; justify-content: center; gap: 4px;
  min-height: var(--ys-touch-min); min-width: 44px; padding: 0 8px;
  border: 0; border-radius: var(--ys-radius-full);
  background: none; font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm); color: var(--ys-fg-2); cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out), color var(--ys-motion-fast) var(--ys-ease-out);
}
.pc__act:hover { background: var(--ys-surface-2); }
.pc__act--on { color: var(--ys-accent); }
.pc__view {
  display: inline-flex; align-items: center; justify-content: center; gap: 4px;
  min-height: var(--ys-touch-min); min-width: 44px; padding: 0 8px;
  font-size: var(--ys-text-sm); color: var(--ys-meta);
}
.pc__greet { margin-left: auto; }
</style>
