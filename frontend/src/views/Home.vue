<template>
  <div class="home">
    <div class="home-inner">
      <!-- 关注 / 全部 tab(A1:读 feed_item 的写扩散收件箱) -->
      <el-tabs v-model="tab" class="feed-tabs">
        <el-tab-pane label="关注" name="following" />
        <el-tab-pane label="全部" name="all" />
      </el-tabs>

      <!-- 搜索 + 筛选/排序 工具条(仅"全部"tab 有筛选;关注 tab 只看关注的人) -->
      <div class="feed-tools">
        <el-input
          v-model="searchQ"
          placeholder="搜索帖子 / 用户 / 圈子"
          class="feed-search"
          clearable
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button @click="doSearch">搜索</el-button>
          </template>
        </el-input>

        <template v-if="tab === 'all'">
          <el-select v-model="sortBy" class="feed-filter" @change="onFilterChange">
            <el-option label="按时间" value="time" />
            <el-option label="按点赞量" value="like" />
            <el-option label="按评论数" value="comment" />
            <el-option label="按浏览量" value="view" />
          </el-select>

          <el-select v-model="sortOrder" class="feed-order" @change="onFilterChange">
            <el-option label="倒序" value="desc" />
            <el-option label="正序" value="asc" />
          </el-select>
        </template>
      </div>

      <div class="feed-list">
        <!-- 首次加载骨架屏 -->
        <template v-if="loading && posts.length === 0">
          <el-card class="skeleton-card" v-for="i in 3" :key="'sk' + i">
            <el-skeleton :rows="3" animated />
          </el-card>
        </template>
        <template v-else>
          <PostCard
            v-for="p in posts"
            :key="p.id"
            :post="p"
            @click="goDetail(p.id)"
          />
          <el-empty
            v-if="!loading && posts.length === 0"
            :description="tab === 'following' ? '你还没关注任何人,先关注一些同学吧' : '还没有动态,去发第一条吧'"
          />
          <div v-if="tab === 'following' && !posts.length" class="follow-hint">
            <el-button link type="primary" @click="goFollow">去关注</el-button>
          </div>
          <div ref="sentinelEl" class="sentinel">
            <el-text v-if="loading" type="info">加载中…</el-text>
            <el-text v-else-if="!hasMore && posts.length" type="info">到底了</el-text>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import PostCard from '@/components/PostCard.vue'
import { feed as feedApi, feedFollowing as followingApi } from '@/api/feed'

const router = useRouter()
const tab = ref('following') // 默认"关注"
const posts = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
let observer = null
const sentinelEl = ref(null)

// 搜索 + 筛选/排序(全部 tab)
const searchQ = ref('')
const sortBy = ref('time')    // time | like | comment | view
const sortOrder = ref('desc') // desc | asc

function normalize(list) {
  return (list || []).map((p) => ({
    ...p,
    createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
    media: p.media || null,
  }))
}

// 搜索框回车 → 跳搜索结果页
function doSearch() {
  const kw = searchQ.value.trim()
  if (!kw) return
  router.push({ path: '/search', query: { kw } })
}
function goFollow() {
  router.push('/search') // 搜索页找用户 → 进主页关注
}

// 拉 feed(按当前 tab / 筛选 / 方向)
async function loadFeed(reset = false) {
  if (reset) {
    posts.value = []
    cursor.value = null
    hasMore.value = true
  }
  if (!hasMore.value || loading.value) return
  loading.value = true
  try {
    if (tab.value === 'following') {
      const data = await followingApi(cursor.value, 20)
      posts.value = reset ? normalize(data.list) : [...posts.value, ...normalize(data.list)]
      cursor.value = data.hasMore ? data.nextCursor : null
      hasMore.value = data.hasMore
    } else {
      // 全部 tab:非默认筛选(非 time/desc)只看第一页,不叠加无限刷
      const filtered = sortBy.value !== 'time' || sortOrder.value !== 'desc'
      const data = await feedApi(filtered ? (reset ? null : cursor.value) : cursor.value, 20, {
        by: sortBy.value,
        order: sortOrder.value,
      })
      posts.value = !filtered && cursor.value && !reset
        ? [...posts.value, ...normalize(data.list)]
        : normalize(data.list)
      cursor.value = data.hasMore && !filtered ? data.nextCursor : null
      hasMore.value = data.hasMore && !filtered
    }
  } catch {
    // request.js 已弹错
  } finally {
    loading.value = false
    nextTick(setupObserver)
  }
}

// 筛选/排序变化 → 重置拉第一页
function onFilterChange() {
  loadFeed(true)
}

watch(tab, () => loadFeed(true))

function goDetail(id) {
  router.push(`/post/${id}`)
}

function setupObserver() {
  observer?.disconnect()
  if (!sentinelEl.value) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && !loading.value && hasMore.value) loadFeed(false)
    },
    { threshold: 0.1, rootMargin: '200px' }
  )
  observer.observe(sentinelEl.value)
}

onMounted(async () => {
  await loadFeed(true)
  nextTick(setupObserver)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<style scoped>
.home-inner {
  max-width: 640px;
  margin: 0 auto;
  padding: 20px;
}
.feed-tabs {
  margin-bottom: 8px;
}
.feed-tools {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}
.feed-search {
  flex: 1;
}
.feed-filter {
  width: 130px;
}
.feed-order {
  width: 100px;
}
.feed-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.skeleton-card {
  width: 100%;
}
.follow-hint {
  text-align: center;
  padding: 8px 0 16px;
}
.sentinel {
  text-align: center;
  padding: 16px;
}
</style>
