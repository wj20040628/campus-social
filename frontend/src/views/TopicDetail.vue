<template>
  <div class="topic-page">
    <div class="topic-inner">
      <div class="topic-head">
        <div class="topic-title"># {{ name }}</div>
        <el-button @click="goPublish">发个「{{ name }}」的帖子</el-button>
      </div>

      <div class="topic-list">
        <PostCard v-for="p in posts" :key="p.id" :post="p" @click="goDetail(p.id)" @topic="onTopic" />
        <el-empty v-if="!loading && posts.length === 0" description="这个话题下还没有动态,来发第一条吧" />
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
    createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
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
.topic-title {
  font-size: 22px;
  font-weight: 700;
  color: var(--el-color-primary);
}
.sentinel {
  text-align: center;
  padding: 16px;
}
</style>
