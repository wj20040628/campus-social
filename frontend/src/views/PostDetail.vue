<template>
  <div class="detail">
    <div class="detail-inner">
      <el-page-header @back="goBack" :title="'帖子详情'" />

      <div v-if="post" class="post-full">
        <div class="author">
          <el-avatar :size="44" :src="resolveUrl(post.author?.avatarUrl)" />
          <div>
            <div class="nick">{{ post.author ? displayName(post.author) : '' }}</div>
            <div class="time">{{ post.createTime }}</div>
          </div>
          <el-tag v-if="post.status === 0" type="warning">审核中</el-tag>
          <el-tag v-else-if="post.status === 2" type="danger">已驳回</el-tag>
        </div>

        <p class="content">{{ post.content }}</p>
        <div v-if="mediaList.length" class="media-grid">
          <template v-for="(m, i) in mediaList" :key="i">
            <el-image v-if="m.type === 'img'" :src="resolveUrl(m.url)" :preview-src-list="imgList" fit="cover" class="media-img" />
            <video v-else :src="resolveUrl(m.url)" controls class="media-video"></video>
          </template>
        </div>

        <!-- 操作条:点赞/编辑/删除归到底部,与作者行信息解耦 -->
        <div class="post-actions">
          <el-button @click="toggleLike">
            <el-icon><Star /></el-icon>
            {{ post.liked ? '已赞' : '赞' }} {{ post.likeCount }}
          </el-button>
          <template v-if="post.author?.id === myId">
            <el-button v-if="post.status === 1" @click="goEdit">
              <el-icon><EditPen /></el-icon> 编辑
            </el-button>
            <el-button type="danger" plain @click="remove">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </div>

        <el-divider />

        <!-- 评论区 -->
        <div class="comments">
          <div class="c-head">
            <span>评论 ({{ comments.length }})</span>
          </div>
          <div class="c-list">
            <div v-for="c in comments" :key="c.id" :id="`comment-${c.id}`" class="c-item">
              <el-avatar :size="32" :src="resolveUrl(c.user?.avatarUrl)" />
              <div class="c-body">
                <div class="c-nick">
                  {{ c.user ? displayName(c.user) : '匿名' }}
                  <span v-if="c.replyToNickname" class="c-reply">回复 {{ c.replyToNickname }}</span>
                </div>
                <div class="c-text">
                  <template v-for="(seg, si) in renderComment(c)" :key="si">
                    <a v-if="seg.link" class="c-mention" @click.stop="goUser(seg.link)">@{{ seg.text }}</a>
                    <span v-else>{{ seg.text }}</span>
                  </template>
                </div>
                <div class="c-time">{{ c.createTime }}</div>
              </div>
            </div>
            <el-empty v-if="comments.length === 0" description="暂无评论" :image-size="60" />
          </div>

          <div class="c-input">
            <!-- 快手式 @:横排头像(点 @ 按钮弹我关注的/粉丝,输入 @ 则按关键字搜) -->
            <div v-if="mentionOpen" class="mention-pop">
              <div v-if="mentionList.length" class="mention-strip">
                <div
                  v-for="u in mentionList"
                  :key="u.id"
                  class="m-cell"
                  @mousedown.prevent="pickMention(u)"
                >
                  <el-avatar :size="52" :src="resolveUrl(u.avatarUrl)" />
                  <div class="m-name">{{ displayName(u) }}</div>
                </div>
              </div>
              <div v-else class="mention-empty">
                {{ mentionKw ? '无匹配用户' : '还没有可 @ 的人(关注/粉丝为空),键入搜索试试' }}
              </div>
            </div>
            <div class="c-editor-row">
              <el-input
                ref="cInputRef"
                v-model="cContent"
                type="textarea"
                :rows="2"
                :placeholder="mentionOpen && !mentionKw ? '@输入搜索' : '写下你的评论,点 @ 提及同学…'"
                maxlength="2000"
                @input="onContentInput"
                @blur="onMentionBlur"
              />
              <!-- @ 按钮:再点一次收起浮层 -->
              <el-button circle :icon="mentionOpen ? 'Close' : 'At'" class="at-btn" @click="toggleMention" />
            </div>
            <el-button type="primary" :disabled="!cContent.trim()" @click="submitComment">发表评论</el-button>
          </div>
        </div>
      </div>
      <el-empty v-else description="帖子不存在或已下架" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { detail, like, unlike, deletePost, comments as commentsApi, comment as commentApi } from '@/api/feed'
import { mentionCandidates } from '@/api/auth'
import { following as followingApi, followers as followersApi } from '@/api/relation'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import { Star, EditPen, Delete } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const myId = computed(() => userStore.userInfo?.id)

const post = ref(null)
const comments = ref([])
const cContent = ref('')
const loadingC = ref(false)

// ---------- @ 提及(快手式:点按钮=推荐人,输 @ =关键字搜) ----------
const cInputRef = ref(null)                 // 模板 ref → ref(null),勿用同名 let(D2)
const mentionOpen = ref(false)
const mentionList = ref([])
const mentionKw = ref('')                    // 当前搜索关键字(空=推荐模式)
const mentionIds = ref([])                   // 已选被@用户 id(唯一,提交时带上)
let mentionSeq = 0                           // 防竞态:旧搜索响应不覆盖新输入
let mentionLoaded = false                    // 推荐人只拉一次
const recommendedCache = []                  // 推荐人缓存(敲 @ 空字时复用)

/** 推荐人:我关注的 + 粉丝(去重,关注优先),点 @ 按钮首次打开时拉 */
async function loadRecommended() {
  const me = myId.value
  if (!me) return
  try {
    const [f, s] = await Promise.all([
      followingApi(me, null, 30),
      followersApi(me, null, 30),
    ])
    const seen = new Set()
    const merged = [...(f?.list || []), ...(s?.list || [])].filter(Boolean).filter((u) => {
      if (seen.has(u.id)) return false
      seen.add(u.id)
      return true
    }).slice(0, 24)
    mentionList.value = merged
    recommendedCache.length = 0
    recommendedCache.push(...merged)
    mentionKw.value = ''
    mentionLoaded = true
  } catch {
    mentionList.value = []
  }
}

function onContentInput() {
  // 光标前最近的 @ 后到光标的内容 = 搜索关键字;没有 @ 就关浮层
  const el = cInputRef.value?.textarea
  const pos = el ? el.selectionStart : cContent.value.length
  const before = cContent.value.slice(0, pos)
  const at = before.lastIndexOf('@')
  if (at === -1 || before.slice(at + 1).includes('\n')) {
    mentionOpen.value = false
    return
  }
  const kw = before.slice(at + 1)
  mentionKw.value = kw
  mentionOpen.value = true
  if (kw === '') {
    // 刚敲 @ 还没输字:弹推荐人(我关注+粉丝),不查全库
    if (!mentionLoaded) loadRecommended()
    else { mentionList.value = recommendedCache.slice() }
  } else {
    // 键入了实际字符:走关键字搜索
    mentionSeq++
    mentionCandidates(kw).then((list) => {
      if (mentionSeq === 0) return
      mentionList.value = Array.isArray(list) ? list : []
    }).catch(() => { mentionList.value = [] })
  }
}

/** @ 按钮:关→开推荐面板;开→关(再点一次收起) */
function toggleMention() {
  if (mentionOpen.value) {
    mentionOpen.value = false
    return
  }
  mentionOpen.value = true
  if (!mentionLoaded) loadRecommended()
  else { mentionKw.value = ''; cInputRef.value?.textarea?.focus() }
}

function pickMention(u) {
  // 两种进入方式:① 输入 @ 后关键字搜(替换 " @kw");② 点 @ 按钮弹推荐人(在光标处直接插)
  const el = cInputRef.value?.textarea
  const pos = el ? el.selectionStart : cContent.value.length
  const before = cContent.value.slice(0, pos)
  const after = cContent.value.slice(pos)
  const at = before.lastIndexOf('@')
  // 光标前紧邻一个 @ 且 @ 后无其它内容 → 替换该 @;否则在光标处直接插入
  const isFreshAt = at !== -1 && before.slice(at + 1) === ''
  const head = isFreshAt ? before.slice(0, at) : before
  const insert = '@' + (u.nickname || u.username) + ' '
  cContent.value = head + insert + after
  if (!mentionIds.value.includes(u.id)) mentionIds.value.push(u.id)
  mentionOpen.value = false
  mentionKw.value = ''
  nextTick(() => {
    const np = head.length + insert.length
    el.focus()
    el.setSelectionRange(np, np)
  })
}

function onMentionBlur() {
  // 失焦略延迟关浮层,让候选项的 @mousedown 先触发
  setTimeout(() => { mentionOpen.value = false }, 150)
}

const mediaList = computed(() => {
  let m = post.value?.media
  if (!m) return []
  if (typeof m === 'string') { try { m = JSON.parse(m) } catch { return [] } }
  return Array.isArray(m) ? m : []
})
const imgList = computed(() => mediaList.value.filter(x => x.type === 'img').map(x => resolveUrl(x.url)))

async function load() {
  const data = await detail(route.params.id)
  post.value = { ...data, createTime: dayjs(data.createTime).format('MM-DD HH:mm') }
  loadComments()
}

async function loadComments() {
  const data = await commentsApi(route.params.id, null, 50)
  comments.value = data.list.map(c => ({ ...c, createTime: dayjs(c.createTime).format('MM-DD HH:mm') }))
  // 从通知跳来带 ?comment={id}:滚动并高亮到那条评论
  const cid = route.query.comment
  if (cid) nextTick(() => scrollToComment(cid))
}

function scrollToComment(cid) {
  const el = document.getElementById(`comment-${cid}`)
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  el.classList.add('comment-highlight')
  setTimeout(() => el.classList.remove('comment-highlight'), 2500)
}

async function toggleLike() {
  if (post.value.liked) {
    await unlike(route.params.id)
    post.value.liked = false
    post.value.likeCount = Math.max(0, post.value.likeCount - 1)
  } else {
    await like(route.params.id)
    post.value.liked = true
    post.value.likeCount = (post.value.likeCount || 0) + 1
  }
}

async function remove() {
  await deletePost(route.params.id)
  ElMessage.success('已删除')
  router.push('/')
}

function goUser(id) {
  if (id) router.push(`/user/${id}`)
}

/**
 * 把评论正文拆成片段,@昵称 若命中 c.mentioned(昵称→id)则变成可点跳主页的 link。
 * 昵称可能重名,故用后端存下的 昵称→id 映射精确定位,而非靠正文反查。
 */
function renderComment(c) {
  const text = c.content || ''
  if (!c.mentioned || Object.keys(c.mentioned).length === 0) {
    return [{ text }]
  }
  const segs = []
  // 收集正文里出现的 @昵称 片段(昵称本身来自 mentioned 的 key)
  const keys = Object.keys(c.mentioned)
  let i = 0
  let buf = ''
  while (i < text.length) {
    let hit = null
    for (const k of keys) {
      const marker = '@' + k
      if (text.startsWith(marker, i)) {
        // 边界:标记后不能紧跟非空白?(简单:匹配即可,昵称不含空格)
        if (i + marker.length >= text.length || /\s/.test(text[i + marker.length])) {
          hit = { k, len: marker.length }
          break
        }
      }
    }
    if (hit) {
      if (buf) segs.push({ text: buf })
      segs.push({ text: hit.k, link: c.mentioned[hit.k] })
      i += hit.len
      buf = ''
    } else {
      buf += text[i]
      i++
    }
  }
  if (buf) segs.push({ text: buf })
  return segs.length ? segs : [{ text }]
}

async function submitComment() {
  if (!cContent.value.trim()) return
  loadingC.value = true
  try {
    // 被@绑定的是选中时的唯一 id,后端按 id 精准发 type=9 通知(昵称可能重名,不靠正文反查)
    await commentApi(route.params.id, { content: cContent.value, level: 1, mentionUserIds: mentionIds.value })
    cContent.value = ''
    mentionIds.value = []
    mentionOpen.value = false
    post.value.commentCount = (post.value.commentCount || 0) + 1
    await loadComments()
  } finally {
    loadingC.value = false
  }
}

function goEdit() {
  router.push(`/post/${route.params.id}/edit`)
}

function goBack() {
  router.back()
}

onMounted(load)
</script>

<style scoped>
.detail-inner {
  max-width: 680px;
  margin: 0 auto;
  padding: 20px;
}
.author {
  display: flex;
  align-items: center;
  gap: 12px;
}
.author .nick { font-weight: 600; }
.author .time { font-size: 12px; color: var(--el-text-color-secondary); }
.content {
  white-space: pre-wrap;
  line-height: 1.7;
  font-size: 16px;
}
.media-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}
.media-img {
  width: 200px; height: 200px; border-radius: 8px;
}
.media-video {
  width: 100%; max-width: 400px; border-radius: 8px;
}
.post-actions {
  display: flex;
  gap: 8px;
  margin: 14px 0;
  flex-wrap: wrap;
}
.post-actions .el-button {
  border-radius: var(--el-border-radius-base);
}
.c-head { font-weight: 600; margin-bottom: 12px; }
.c-item {
  display: flex;
  gap: 10px;
  padding: 10px;
  margin: 0 -10px;
  border-radius: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  scroll-margin: 80px;
}
.c-item.comment-highlight {
  animation: comment-flash 2.5s ease;
}
@keyframes comment-flash {
  0%, 20% { background: var(--el-color-primary-light-8); }
  100% { background: transparent; }
}
.c-nick { font-weight: 500; font-size: 14px; }
.c-reply { font-size: 12px; color: var(--el-color-primary); margin-left: 6px; }
.c-text { margin: 4px 0; line-height: 1.7; }
.c-mention {
  color: var(--el-color-primary);
  font-weight: 500;
  cursor: pointer;
  text-decoration: none;
}
.c-mention:hover { text-decoration: underline; }
.c-time { font-size: 12px; color: var(--el-text-color-secondary); }
.c-input {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  position: relative;   /* @ 候选浮层锚点 */
}
.c-input .el-button { align-self: flex-end; }
.c-editor-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.c-editor-row .el-input { flex: 1; }
.at-btn {
  margin-top: 4px;
  color: var(--el-text-color-regular);
}
/* @ 提及候选浮层:横排头像 strip(快手式) */
.mention-pop {
  position: absolute;
  bottom: 100%;
  left: 0;
  margin-bottom: 4px;
  width: 420px;
  max-width: 100%;
  max-height: 200px;
  overflow-x: auto;
  overflow-y: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  box-shadow: 0 -4px 16px rgba(0, 0, 0, 0.08);
  z-index: 10;
  padding: 10px 4px;
}
.mention-strip {
  display: flex;
  gap: 10px;
}
.m-cell {
  flex: 0 0 auto;
  width: 64px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  text-align: center;
  padding: 4px 2px;
  border-radius: 8px;
}
.m-cell:hover { background: var(--el-fill-color-light); }
.m-cell .m-name {
  font-size: 12px;
  line-height: 1.2;
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-primary);
}
.mention-empty {
  padding: 12px 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
