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
      <!--
        历史匿名帖(2026-10-25 引入,2026-10-27 匿名发布下线):后端不下发
        author/authorId,这里统一渲染「匿名」标签;头像/名字不挂点击(点了也没主页可去)。
      -->
      <span class="pc__ava" :class="{ 'pc__anon': isAnon }" @click.stop="goAuthor">
        <InitialAvatar :size="38" :src="isAnon ? '' : (post.author?.avatarUrl ? resolveThumb(post.author.avatarUrl) : '')" :seed="post.author?.id" :name="isAnon ? '匿' : (post.author ? displayName(post.author) : '')" />
      </span>
      <span class="pc__who" :class="{ 'pc__anon': isAnon }">
        <span class="pc__name" @click.stop="goAuthor">{{ isAnon ? '匿名' : (post.author ? displayName(post.author) : '匿名') }}</span>
        <span class="pc__time ys-num">{{ timeText }}</span>
      </span>
      <span v-if="isAnon" class="pc__tag pc__tag--anon">匿名</span>
      <!--
        圈子标签：**必须有圈名**才渲染。
        改版前只写死「圈子」两个字（后端那时只下发 circleId、没有 circleName），
        用户看得出「这条来自某个圈」却不知道是哪个、也点不进去 —— 等于没有信息量。
        现在圈名由服务端按页批量下发；帖子所属圈已解散/被封禁时服务端不给名字，
        这里随之不渲染，避免给一个点进去 404 的链接。
      -->
      <button
        v-if="post.circleId && post.circleName"
        type="button"
        class="pc__ctag"
        :title="`来自圈子：${post.circleName}`"
        @click.stop="goCircle"
      >
        <el-icon :size="12"><Files /></el-icon>
        <span class="pc__ctag-txt">{{ post.circleName }}</span>
      </button>
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
          <div v-if="m.type === 'img' && brokenMedia.has(i)" class="pc__img pc__img--broken" @click.stop>
            图片已失效
          </div>
          <el-image v-else-if="m.type === 'img'" :src="resolveThumb(m.url)" :preview-src-list="imgList" :initial-index="i" fit="cover" class="pc__img" loading="lazy" @click.stop @error="onMediaError(i)" />
          <PostVideo v-else :src="m.url" :poster="m.poster" class="pc__video" @click.stop />
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
      <!--
        这里**没有**「搭句话」按钮（2026-10-21 移除）。
        理由：点卡片本身就进详情、详情页有同一个按钮，列表页这个不提供任何
        不可替代的能力；而一个 44px 实心青主按钮压在一行 13px 灰数字旁边，
        视觉权重差 5 个层级，把 赞/藏/评/浏览 挤成一片。
        搭话是「读完内容后」的决策动作，属于详情页与个人主页，不属于列表。
        GreetButton 组件本身保留 —— PostDetail 与 Profile 仍在用。
      -->
    </footer>
  </article>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Star, StarFilled, ChatLineRound, View, Files } from '@element-plus/icons-vue'
import ThumbUp from '@/icons/ThumbUp.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { displayName } from '@/utils/mask'
import { relTime } from '@/utils/relTime'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import PostVideo from '@/components/post/PostVideo.vue'
import { like, unlike, favorite, unfavorite } from '@/api/feed'
import { ElMessage } from '@/utils/feedback'

const props = defineProps({ post: { type: Object, required: true } })
defineEmits(['click', 'topic'])

const router = useRouter()

/**
 * 加载失败的图片下标集合（2026-10-21）。
 *
 * 帖子的 media 可能指向已删除的磁盘文件（实测 post 168/169：
 * upload_file 有记录、文件已不在）。不改 props 里的 mediaList ——
 * 那是父组件的数据；「这张图坏了」是纯展示层事实，留在这里最合适。
 * 用 Set 判断 O(1)；整体替换才能触发 Vue 的响应式。
 */
const brokenMedia = ref(new Set())

function onMediaError(i) {
  const next = new Set(brokenMedia.value)
  next.add(i)
  brokenMedia.value = next
}

/**
 * 时间显示：**相对时间**，不是后端的原始 "yyyy-MM-dd HH:mm"。
 *
 * 改版前直接渲染 `post.createTime`，列表里满眼都是 "10-20 12:23" 这种机器格式 ——
 * 读者要自己算「这是几天前」，而信息流最需要的恰恰是「新不新」。
 * relTime 全站已有（CircleRow 在用同一份），这里复用，不另写一套。
 *
 * 1 小时内给「N 分钟前」、当天「N 小时前」、7 天内「N 天前」、
 * 再往前退化成「9 月 15 日」（年份不同则带年份）。
 */
const timeText = computed(() => relTime(props.post.createTime) || props.post.createTime || '')

/** 历史匿名帖:后端已抹掉 author,前端只负责统一视觉与禁跳转 */
const isAnon = computed(() => !!props.post.anonymous)

function goDetail() { router.push(`/post/${props.post.id}`) }
function goAuthor() {
  if (isAnon.value) return // 匿名帖没有可去的主页
  if (props.post.author?.id) router.push(`/user/${props.post.author.id}`)
}
function goTopic(name) { router.push(`/topic/${encodeURIComponent(name)}`) }
/** 圈子标签直接进圈 —— 标签既然给出了圈名，就必须能点进那个圈 */
function goCircle() {
  if (props.post.circleId) router.push(`/circles/${props.post.circleId}`)
}

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
  /* 只精修留白:上下 12、左右保持 16,短内容不再显得中间空一块 */
  padding: 12px var(--ys-space-4) 12px;
  cursor: pointer;
  /*
   * 屏外卡片跳过布局与绘制（配合 useHomeFeed 的 200 条上限做）。
   *
   * 列表是「只追加」的，刷到几百条后每一次布局都要把全部卡片算一遍、
   * 每张图都要解码，低端机上就是滑动掉帧。content-visibility:auto
   * 让浏览器只渲染视口附近的卡片，跳过的部分既不算布局也不进绘制。
   *
   * contain-intrinsic-size 必须给：被跳过的元素量不出高度，不给就会
   * 在滚动条里塌成 0，页面高度和滚动位置一起乱跳。
   * 卡片高度从 150px（纯文字）到 400px（单图 16:10）不等，固定值只能
   * 取中间；先写固定值给老浏览器兜底，再写 auto 形式让现代浏览器
   * 记住每张卡的真实高度（后者不认时整条声明被丢弃，前者仍在）。
   */
  content-visibility: auto;
  contain-intrinsic-size: 220px;
  contain-intrinsic-size: auto 220px;
  transition: box-shadow var(--ys-motion-fast) var(--ys-ease-out), border-color var(--ys-motion-fast) var(--ys-ease-out);
}
.pc:hover { border-color: var(--ys-border-control); box-shadow: var(--ys-shadow-2); }

.pc__head { display: flex; align-items: center; gap: var(--ys-space-2); }
.pc__ava { flex: none; cursor: pointer; display: inline-flex; }
/* 作者跳转只挂在头像与昵称上：昵称列 flex:1 占满整行，整块可点会让
   用户点卡片中上部（时间/空白处）误进作者主页。 */
.pc__who { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.pc__name {
  align-self: flex-start; /* 点击热区收缩到昵称文字本身，不占满整行 */
  font-size: 14px; font-weight: var(--ys-weight-semibold); line-height: 1.3;
  color: var(--ys-fg); cursor: pointer;
}
.pc__name:hover { color: var(--ys-accent); }
.pc__time { font-size: var(--ys-text-xs); color: var(--ys-meta); margin-top: 1px; }

/* 状态标签(审核中/已驳回/置顶)：只读，不可点 */
.pc__tag { flex: none; font-size: 11px; line-height: 1; padding: 5px 10px; border-radius: var(--ys-radius-full); }
/*
 * 圈子胶囊：可点，带圈名。
 *
 * 与 pc__tag 分开写而不是共用一条规则：这里是 <button>，需要
 * 重置 border/background/font（按钮有 UA 默认样式），且要处理长圈名截断；
 * 而 pc__tag 是 <span> 纯展示，两者只会越改越不像。
 *
 * max-width:158px —— 圈名最长 7 个汉字（建圈校验 3~7 字），中文 7 字约
 * 11.5px * 7 ≈ 80px + 图标 12 + 间距 4 + padding 20 ≈ 116px，158px 留足余量；
 * 万一历史数据有更长的名字，省略号兜底，不把作者昵称挤掉。
 */
.pc__ctag {
  flex: none;
  display: inline-flex; align-items: center; gap: 4px;
  max-width: 158px;
  padding: 6px 10px;
  border: 0; border-radius: var(--ys-radius-full);
  background: var(--ys-accent-2-soft);
  color: var(--ys-accent-2);
  font-family: var(--ys-font-body);
  font-size: 11.5px; font-weight: var(--ys-weight-semibold); line-height: 1;
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}
.pc__ctag:hover { background: var(--ys-accent-2); color: var(--ys-accent-on); }
/* 文字部分才做截断：图标本身不该被 ellipsis 吃掉 */
.pc__ctag-txt { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pc__tag--warn { color: var(--ys-warning); background: var(--ys-warning-soft); }
.pc__tag--danger { color: var(--ys-danger); background: var(--ys-danger-soft); }
.pc__tag--top { color: var(--ys-accent-on); background: var(--ys-fg); }
/* 匿名标签:中性信息色,与审核/驳回(警示系)区分开——匿名不是异常状态 */
.pc__tag--anon { color: var(--ys-info); background: var(--ys-info-soft); }
/* 匿名帖的头像/名字不可点:去掉手型,避免「点了没反应」的可点暗示 */
.pc__anon { cursor: default; }

/* 12 → 8：见 .pc 的 padding 注释 */
.pc__body { margin-top: 8px; }
.pc__text {
  margin: 0; font-size: var(--ys-text-base); line-height: var(--ys-lh-base); color: var(--ys-fg);
  white-space: pre-wrap;
  /* 长串（URL / 连续字母数字）必须折行，否则会把整张卡片撑破 ——
     详情页 PostBody.css 早就这么做了，卡片这边漏了同一道防护 */
  overflow-wrap: anywhere;
  word-break: break-word;
}
.pc__topics { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
/*
 * 话题 chip：长话题折行而不是撑破卡片。
 *
 * 后端上限已收紧到 15 字，但**存量数据里存在 40+ 字的旧话题**
 * （旧上限 64 时入库的），前端必须兜住 —— 服务端的上限只对新数据生效。
 * overflow-wrap:anywhere 允许在任意字符间断开，这是唯一能处理
 * 「#1111…（无空格长数字串）」的方式；折行后胶囊会变高，属可接受代价，
 * 总好过把整张卡片顶宽、右侧内容被裁掉。
 */
.pc__tpic {
  border: 0; font-family: var(--ys-font-body); font-size: var(--ys-text-xs);
  color: var(--ys-accent); background: var(--ys-accent-soft);
  border-radius: var(--ys-radius-full); padding: 4px 10px; cursor: pointer;
  max-width: 100%;
  overflow-wrap: anywhere; word-break: break-word;
  text-align: left; line-height: 1.45;
  font-weight: var(--ys-weight-medium); /* 500：accent 落 accent-soft 上仅 4.59:1，加字重提升清晰度 */
  /*
   * 触控热区（2026-10-21 新增）。
   *
   * 原样式 padding 4px/10px，实际高度只有 24~25px，远低于 44px 基线（AC-15）。
   * 但话题芯片通常一排好几个，直接撑到 44×44 会把卡片挤成三行 ——
   * 视觉代价远大于收益。
   *
   * 折中：用 ::after 向上下各溢出，把**可点高度**补到 44
   * 而不占布局空间（absolute 不参与流）。
   * 宽度方向不动：芯片本身已有 51px+，横向够用。
   * 效果是手指竖着压偏一点也能命中，而卡片高度一个像素都不变。
   */
  position: relative;
}
.pc__tpic::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  height: 44px;
  transform: translateY(-50%);
}
.pc__tpic:hover { color: var(--ys-accent-hover); }

.pc__media { display: grid; gap: 6px; margin-top: var(--ys-space-3); }
/* minmax(0,1fr) 而非 1fr：1fr 的隐含最小尺寸是 auto，图片的自然宽度会撑破列，
   导致整个卡片横向溢出（详情页 media-grid 同样的坑） */
.pc__media--n1 { grid-template-columns: minmax(0, 1fr); }
.pc__media--n2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.pc__media--n3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.pc__media--n1 .pc__img { aspect-ratio: 16 / 10; }
.pc__media--n2 .pc__img, .pc__media--n3 .pc__img { aspect-ratio: 1; }
.pc__img { width: 100%; border-radius: var(--ys-radius-lg); display: block; }
/*
 * 图片失效占位（2026-10-21）。
 * 与正常图片同位同高（aspect-ratio 由上面 .pc__media--nN 规则给），
 * 用暖米底 + 虚线边 + 小字：它是「这张图没了」，不是「操作失败了」，
 * 所以不做成刺眼的报错样式。
 */
.pc__img--broken {
  display: grid;
  place-items: center;
  background: var(--ys-surface-2);
  border: 1px dashed var(--ys-border);
  color: var(--ys-meta);
  font-size: var(--ys-text-xs);
}
.pc__video { grid-column: 1 / -1; width: 100%; max-height: 360px; border-radius: var(--ys-radius-lg); background: #000; }

.pc__foot {
  display: flex; align-items: center; gap: 2px;
  margin-top: 8px; padding-top: 8px;
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
/* 浏览数推到行尾：四项里唯一的纯只读指标，右对齐把「可操作」与「只读」分开。 */
.pc__view {
  display: inline-flex; align-items: center; justify-content: center; gap: 4px;
  min-height: var(--ys-touch-min); min-width: 44px; padding: 0 8px;
  margin-left: auto;
  font-size: var(--ys-text-sm); color: var(--ys-meta);
}
</style>
