<template>
  <div class="topic-page">
    <div class="topic-inner">
      <div class="topic-head">
        <div class="topic-title"># {{ name }}</div>
        <el-button @click="goPublish">发个「{{ name }}」的帖子</el-button>
      </div>

      <div class="topic-list">
        <PostCard v-for="p in posts" :key="p.id" :post="p" @click="goDetail(p.id)" @topic="onTopic" />
        <StateBlock
          v-if="!loading && posts.length === 0"
          variant="empty"
          title="这个话题下还没有动态"
          :description="`发一条带「${name}」的帖子,这个话题就有内容了`"
          action-label="发第一条"
          :icon="ChatLineSquare"
          @action="goPublish"
        />
        <div ref="sentinelEl" class="sentinel">
          <el-text v-if="loading" type="info">加载中…</el-text>
          <el-text v-else-if="!hasMore && posts.length" type="info">到底了</el-text>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import PostCard from '@/components/PostCard.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import { ChatLineSquare } from '@element-plus/icons-vue'
import { topicFeed } from '@/api/feed'

const route = useRoute()
const router = useRouter()

const name = ref(decodeURIComponent(route.params.name || ''))
const posts = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
const sentinelEl = ref(null)
let observer = null

function normalize(list) {
  return (list || []).map((p) => ({
    ...p,
    /**
     * 保留原始 ISO 时间，不要在这里格式化成 'MM-DD HH:mm'（2026-10-21 修）。
     * 格式化属于展示层：PostCard 内部用 relTime() 显示相对时间，
     * 数据层先把值压成字符串会让 relTime 拿到无法解析的文本而失效，
     * 列表就退回显示 "09-15 11:35" 这种机器格式。
     */
    createTime: p.createTime,
    media: p.media || null,
  }))
}

async function load(reset = false) {
  if (reset) {
    posts.value = []
    cursor.value = null
    hasMore.value = true
  }
  if (!hasMore.value || loading.value) return
  loading.value = true
  try {
    const data = await topicFeed(name.value, cursor.value, 20)
    posts.value = reset ? normalize(data.list) : [...posts.value, ...normalize(data.list)]
    cursor.value = data.hasMore ? data.nextCursor : null
    hasMore.value = data.hasMore
  } catch {
    // request.js 已弹错
  } finally {
    loading.value = false
    nextTick(setupObserver)
  }
}

function setupObserver() {
  observer?.disconnect()
  if (!sentinelEl.value) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && !loading.value && hasMore.value) load(false)
    },
    { threshold: 0.1, rootMargin: '200px' }
  )
  observer.observe(sentinelEl.value)
}

function goDetail(id) {
  router.push(`/post/${id}`)
}
function goPublish() {
  router.push({ path: '/publish', query: { topic: name.value } })
}
// 话题标签点击:切到另一个话题(同页内更新,不重建)
function onTopic(newName) {
  if (newName === name.value) return
  router.push({ name: 'topic', params: { name: newName }, query: {} })
}

// 路由复用(点另一个话题标签时不重建组件):watch name 全量重置重载
watch(name, () => {
  posts.value = []
  cursor.value = null
  hasMore.value = true
  load(true)
})

onMounted(async () => {
  await load(true)
  nextTick(setupObserver)
})

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<style scoped>
.topic-page {
  min-height: 100%;
}
.topic-inner {
  max-width: 640px;
  margin: 0 auto;
  padding: 20px;
}
.topic-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
/* --ys-accent 不用 --el-color-primary:业务代码直连 --el-* 违反 token 硬规则 2 */
.topic-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--ys-accent);
}
/* A 方向:话题页主动作「发个这个话题的帖子」统一胶囊 */
.topic-head :deep(.el-button) {
  border-radius: var(--ys-radius-full);
}
.sentinel {
  text-align: center;
  padding: 16px;
}
</style>
