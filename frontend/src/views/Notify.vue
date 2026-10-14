<template>
  <div class="notify">
    <div class="notify-inner">
      <el-card>
        <template #header>
          <div class="n-head">
            <span>通知</span>
            <el-button v-if="list.some((n) => !n.isRead)" link type="primary" @click="readAll">
              全部已读
            </el-button>
          </div>
        </template>
        <div class="n-list">
          <!-- 首屏加载骨架:列表还没回来时占位,避免进入页面纯白 -->
          <template v-if="loading && list.length === 0">
            <div v-for="i in 4" :key="'ns' + i" class="n-skeleton">
              <el-skeleton :rows="1" animated />
            </div>
          </template>
          <div
            v-for="n in list"
            :key="n.id"
            class="n-item"
            :class="{ unread: !n.isRead }"
            @click="openAndRead(n)"
          >
            <el-avatar :size="40" :src="resolveUrl(n.actor?.avatarUrl)" class="n-avatar" />
            <div class="n-body">
              <div class="n-text">
                <b>{{ n.actor ? displayName(n.actor) : '用户' }}</b>
                {{ n.summary }}
              </div>
              <div class="n-time">{{ n.createTime }}</div>
            </div>
            <el-tag v-if="!n.isRead" type="danger" size="small" effect="dark" class="n-badge">新</el-tag>
          </div>
          <StateBlock v-if="list.length === 0" variant="empty" title="没有新通知" description="有人点赞、评论或关注你时会出现在这儿" :icon="Bell" />
        </div>
        <div class="n-sentinel">
          <el-text v-if="loading" type="info">加载中…</el-text>
          <el-text v-else-if="!hasMore && list.length" type="info">没有更多了</el-text>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from '@/utils/feedback'
import dayjs from 'dayjs'
import { list as listApi, read as readApi, readAll as readAllApi } from '@/api/notify'
import { getWsClient } from '@/utils/ws'
import StateBlock from '@/components/common/StateBlock.vue'
import { Bell } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import { useUserStore } from '@/stores/user'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()
const ws = getWsClient()

const list = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
let observer = null

function fmt(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

async function load() {
  loading.value = true
  try {
    const data = await listApi(cursor.value, 20)
    const items = data.list.map((n) => ({ ...n, createTime: fmt(n.createTime) }))
    list.value = cursor.value ? [...list.value, ...items] : items
    cursor.value = data.hasMore ? data.nextCursor : null
    hasMore.value = data.hasMore
  } finally {
    loading.value = false
    nextTick(setupObserver)
  }
}

function setupObserver() {
  observer?.disconnect()
  const el = document.querySelector('.n-sentinel')
  if (!el) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && !loading.value && hasMore.value) load()
    },
    { threshold: 0.1, rootMargin: '200px' }
  )
  observer.observe(el)
}

async function openAndRead(n) {
  // 跳转:点赞/评论/被关注 → 关联对象。
  // 注:评论(type=3)的 targetId 存的是帖子 id,后端未存评论 id,
  // 故目前只能跳到帖子;「定位到具体那条评论」需后端加字段(二级优先,非阻塞)
  const jump = () => {
    // 点赞/评论:优先用 postId(被指向的帖子 id);
    // 旧数据无 postId 时回退 targetId(点赞的 targetId 本就是帖子 id,评论的是评论 id,跳不了就退回首页)
    if (n.type === 2 || n.type === 3) {
      const pid = n.postId ?? (n.type === 2 ? n.targetId : null)
      if (pid) {
        // 评论(type=3)定位到那条评论(后端已把评论 id 存入 targetId),点赞跳帖子顶部
        const query = n.type === 3 ? { comment: n.targetId } : {}
        router.push({ path: `/post/${pid}`, query })
      } else {
        router.push('/')
      }
    } else if (n.type === 1) {
      // 被关注 → 关注你的人
      // actorId 可能为空(后端 NotifyVO.actorId 是 Long 包装类型,
      // 系统类通知没有「谁触发的」这个概念),拼出 /user/undefined 这种坏 URL
      // 会让 vue-router 推一个不存在的路由。TopBar.onUserCommand 已修过同一类,
      // 这里是第二个入口。拿不到 id 就落到首页,不给用户点不动的入口。
      if (n.actorId != null) router.push(`/user/${n.actorId}`)
      else router.push('/')
    } else if (n.type === 4) {
      router.push(`/chat`)
    } else if (n.type === 6 || n.type === 7 || n.type === 8) {
      // 内容审核结果通知:跳被审核的帖子详情
      const pid = n.postId ?? n.targetId
      if (pid) router.push(`/post/${pid}`)
    } else if (n.type === 9) {
      // 被 @:targetId 是评论 id,跳帖子并定位到那条评论
      const pid = n.postId ?? null
      if (pid) router.push({ path: `/post/${pid}`, query: { comment: n.targetId } })
    }
  }
  if (!n.isRead) {
    await readApi(n.id)
  }
  jump()
  markRead(n.id)
}

// 单条标已读:重赋值整条对象保证响应式,避免「新」残留
function markRead(id) {
  list.value = list.value.map((n) => (n.id === id ? { ...n, isRead: true } : n))
}

async function readAll() {
  await readAllApi()
  list.value = list.value.map((n) => ({ ...n, isRead: true }))
  ElMessage.success('已全部标为已读')
}

// WS 实时通知
function onNotify(payload) {
  // 刷新未读计数,拉新通知
  load()
}

onMounted(async () => {
  ws.on('notify', onNotify)
  ws.connect('/ws/notify')
  await load()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  ws.off('notify', onNotify)
})
</script>

<style scoped>
/*
 * 变量纪律:本文件原写法混用 --el-* 与 --card-radius。
 * --el-* 虽经 Bridge 最终指向 --ys-*,但业务代码直接写 --el-* 违反
 * design-tokens.css 头部硬规则 2(要改 EP 表现改 Bridge,不改业务侧),
 * 且下一任改样式的人会不知道该追哪一层。统一收回 --ys-*。
 */
.notify-inner {
  max-width: 680px;
  margin: 0 auto;
  padding: 20px;
}
.notify-inner .el-card {
  border-radius: var(--ys-radius-xl);
}
.n-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
/*
 * 「全部已读」用 EP 的 link button,而 link 态在 EP 里没有内边距 ——
 * 375px 实测只有 62x20,AC-15 要求 44x44。给它补最小热区。
 * 用 min-height/min-width 而不是 padding:改 padding 会把文字与标题挤开,
 * 视觉上「全部已读」四个字离左边框的距离会变。
 */
.n-head :deep(.el-button.is-link) {
  min-width: var(--ys-touch-min);
  min-height: var(--ys-touch-min);
}
.n-list {
  display: flex;
  flex-direction: column;
}
.n-skeleton {
  padding: 12px 4px;
  border-bottom: 1px solid var(--ys-border-soft);
}
.n-item {
  display: flex;
  gap: 12px;
  padding: 14px 12px;
  margin: 0 -12px;
  border-bottom: 1px solid var(--ys-border-soft);
  cursor: pointer;
  border-radius: var(--ys-radius-lg);
}
/* 未读:青浅底 + 左侧 3px 青条。不用灰底 —— 未读是全页最重要的状态 */
.n-item.unread {
  background: var(--ys-accent-soft);
  position: relative;
}
.n-item.unread::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  bottom: 14px;
  width: 3px;
  border-radius: 2px;
  background: var(--ys-accent);
}
.n-item:hover {
  opacity: 0.85;
}
.n-avatar {
  flex-shrink: 0;
}
.n-body {
  flex: 1;
}
.n-text {
  font-size: 14px;
}
.n-time {
  font-size: 12px;
  color: var(--ys-meta);
  margin-top: 4px;
}
.n-badge {
  flex-shrink: 0;
}
.n-sentinel {
  text-align: center;
  padding: 12px;
}
</style>
