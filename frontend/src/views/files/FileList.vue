<template>
  <div class="files">
    <div class="files__bar">
      <div class="files__tabs" role="tablist" aria-label="文件范围">
        <button
          v-for="t in TABS"
          :key="t.value"
          type="button"
          role="tab"
          class="files__tab"
          :class="{ 'is-active': tab === t.value }"
          :aria-selected="tab === t.value"
          @click="switchTab(t.value)"
        >
          {{ t.label }}
        </button>
      </div>
      <button type="button" class="files__upload" @click="goUpload">
        <el-icon :size="17"><Plus /></el-icon>
        上传
      </button>
    </div>

    <div class="files__tools">
      <div class="files__searchbox">
        <input
          v-model="keyword"
          class="files__search"
          type="search"
          maxlength="40"
          placeholder="搜标题或文件名"
          aria-label="搜文件"
          @keyup.enter="applySearch"
        />
        <button
          v-if="keyword"
          type="button"
          class="files__search-x"
          aria-label="清空搜索"
          @click="clearSearch"
        >
          &#10005;
        </button>
      </div>
      <div class="files__sort" role="group" aria-label="排序">
        <button
          type="button"
          :class="{ 'is-active': sort === 'latest' }"
          @click="setSort('latest')"
        >
          最新
        </button>
        <button
          type="button"
          :class="{ 'is-active': sort === 'hot' }"
          @click="setSort('hot')"
        >
          最多下载
        </button>
      </div>
    </div>

    <div class="files__cats" role="group" aria-label="分类">
      <button
        type="button"
        class="files__cat"
        :class="{ 'is-active': !category }"
        @click="pickCategory('')"
      >
        全部
      </button>
      <button
        v-for="c in FILE_CATEGORIES"
        :key="c.value"
        type="button"
        class="files__cat"
        :class="{ 'is-active': category === c.value }"
        @click="pickCategory(c.value)"
      >
        {{ c.label }}
      </button>
    </div>

    <template v-if="tab === 'mine'">
      <FileQuotaBar :quota="quotaInfo" />
      <div class="files__sub" role="tablist" aria-label="我的文件">
        <button
          v-for="s in SUB_TABS"
          :key="s.value"
          type="button"
          role="tab"
          class="files__subbtn"
          :class="{ 'is-active': sub === s.value }"
          :aria-selected="sub === s.value"
          @click="switchSub(s.value)"
        >
          {{ s.label }}
        </button>
      </div>
    </template>

    <div v-if="loading && !list.length" class="files__skeleton" aria-hidden="true">
      <div v-for="i in 4" :key="i" class="files__sk">
        <span class="files__sk-badge" />
        <span class="files__sk-lines">
          <span class="files__sk-line" />
          <span class="files__sk-line files__sk-line--short" />
        </span>
      </div>
    </div>

    <StateBlock
      v-else-if="loadFailed"
      variant="error"
      title="文件列表没加载出来"
      description="可能是网络断了，点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="reload"
    />

    <StateBlock
      v-else-if="!list.length"
      variant="empty"
      :title="emptyCopy.title"
      :description="emptyCopy.description"
      :action-label="emptyCopy.action"
      :icon="emptyCopy.icon"
      @action="onEmptyAction"
    />

    <div v-else class="files__list">
      <FileCard
        v-for="f in list"
        :key="f.id"
        :file="f"
        :show-status="tab === 'mine'"
        @open="openDetail"
      />
    </div>

    <div v-if="list.length" class="files__tail">
      <button v-if="hasMore" type="button" class="files__more" :disabled="loading" @click="loadMore">
        {{ loading ? '加载中' : '加载更多' }}
      </button>
      <span v-else class="files__total ys-num">共 {{ total }} 个文件</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, WarningFilled, FolderOpened, Search, Star } from '@element-plus/icons-vue'
import FileCard from '@/components/files/FileCard.vue'
import FileQuotaBar from '@/components/files/FileQuotaBar.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import * as fileApi from '@/api/files'
import { FILE_CATEGORIES } from '@/constants/fileCategories'

const route = useRoute()
const router = useRouter()

const TABS = [
  { value: 'all', label: '全部' },
  { value: 'mine', label: '我的' },
]
const SUB_TABS = [
  { value: 'uploads', label: '我上传的' },
  { value: 'favorites', label: '我收藏的' },
]

const tab = ref(route.query.tab === 'mine' ? 'mine' : 'all')
const sub = ref('uploads')
const category = ref('')
const keyword = ref('')
const sort = ref('latest')

const list = ref([])
const total = ref(0)
const page = ref(1)
const hasMore = ref(false)
const loading = ref(false)
const loadFailed = ref(false)
const quotaInfo = ref(null)

let requestSeq = 0

const searching = computed(() => Boolean(keyword.value.trim() || category.value))

const emptyCopy = computed(() => {
  if (searching.value) {
    return {
      title: '没找到这类文件',
      description: '换个关键词或分类试试，也可以自己传一份',
      action: '清空筛选',
      icon: Search,
    }
  }
  if (tab.value === 'mine' && sub.value === 'favorites') {
    return {
      title: '还没有收藏',
      description: '看到有用的资料点个收藏，就会出现在这儿',
      action: '去逛逛',
      icon: Star,
    }
  }
  if (tab.value === 'mine') {
    return {
      title: '你还没上传过文件',
      description: '把模板、讲义、真题传上来，帮同学少找一会儿',
      action: '上传文件',
      icon: FolderOpened,
    }
  }
  return {
    title: '还没有文件',
    description: '传一份你手头的资料，让同学少找一会儿',
    action: '上传文件',
    icon: FolderOpened,
  }
})

function queryParams() {
  return {
    tab: tab.value,
    sub: tab.value === 'mine' ? sub.value : undefined,
    category: category.value || undefined,
    keyword: keyword.value.trim() || undefined,
    sort: sort.value,
    page: page.value,
    size: 20,
  }
}

async function load(reset = false) {
  if (reset) {
    page.value = 1
    list.value = []
    hasMore.value = false
    loadFailed.value = false
  }
  if (loading.value && !reset) return
  loading.value = true
  const seq = ++requestSeq
  try {
    const data = await fileApi.list(queryParams())
    if (seq !== requestSeq) return // 切 tab/筛选太快时丢弃旧响应
    const items = data?.list ?? []
    list.value = reset ? items : [...list.value, ...items]
    total.value = Number(data?.total) || 0
    hasMore.value = data?.hasMore === true
    loadFailed.value = false
  } catch {
    if (seq === requestSeq) loadFailed.value = list.value.length === 0
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function reload() {
  load(true)
  if (tab.value === 'mine') loadQuota()
}

function loadMore() {
  if (!hasMore.value || loading.value) return
  page.value += 1
  load(false)
}

async function loadQuota() {
  try {
    quotaInfo.value = await fileApi.quota()
  } catch {
    quotaInfo.value = null
  }
}

function refreshRoute() {
  const q = { ...route.query }
  if (tab.value === 'mine') q.tab = 'mine'
  else delete q.tab
  router.replace({ path: '/files', query: q }).catch(() => {})
}

function switchTab(value) {
  if (tab.value === value) return
  tab.value = value
  sub.value = 'uploads'
  page.value = 1
  refreshRoute()
  load(true)
  if (value === 'mine') loadQuota()
}

function switchSub(value) {
  if (sub.value === value) return
  sub.value = value
  load(true)
}

function pickCategory(value) {
  category.value = value
  load(true)
}

function setSort(value) {
  if (sort.value === value) return
  sort.value = value
  load(true)
}

function applySearch() {
  load(true)
}

function clearSearch() {
  keyword.value = ''
  load(true)
}

function onEmptyAction() {
  if (searching.value) {
    keyword.value = ''
    category.value = ''
    load(true)
    return
  }
  if (tab.value === 'mine' && sub.value === 'favorites') {
    switchTab('all')
    return
  }
  goUpload()
}

function goUpload() {
  router.push('/files/upload')
}

function openDetail(file) {
  router.push(`/files/${file.id}`)
}

onMounted(() => {
  load(true)
  if (tab.value === 'mine') loadQuota()
  // 通知点击 /files?tab=mine 进入时，把 URL 状态对齐
  if (route.query.tab === 'mine') refreshRoute()
})

// 已经在本页时再点通知/分享链接，query 可能变而组件不重挂
watch(
  () => route.query.tab,
  (value) => {
    const next = value === 'mine' ? 'mine' : 'all'
    if (next === tab.value) return
    tab.value = next
    sub.value = 'uploads'
    load(true)
    if (next === 'mine') loadQuota()
  }
)
</script>

<style scoped src="./FileList.css"></style>
