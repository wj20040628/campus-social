<template>
  <div class="ann-page">
    <!-- 未读横条:仅未读数 > 0 时渲染。未读数拉取失败静默降级(它是增强信息) -->
    <div v-if="unread > 0" class="unread-bar" role="status">
      <el-icon :size="16"><Promotion /></el-icon>
      <span class="unread-bar__text">还有 {{ cap(unread) }} 条没看</span>
      <button type="button" class="unread-bar__act" @click="jumpFirstUnread">
        跳到第一条
      </button>
    </div>

    <!-- 分类筛选:横滑 chip -->
    <div class="chips" role="tablist" aria-label="公告分类">
      <button
        v-for="c in CATEGORIES"
        :key="c.value"
        type="button"
        role="tab"
        class="chip"
        :class="{ 'is-on': category === c.value }"
        :aria-selected="category === c.value"
        @click="pickCategory(c.value)"
      >
        {{ c.label }}
      </button>
    </div>

    <!-- 筛选请求失败:保留上一次结果并说明,不白屏 -->
    <p v-if="filterFailed" class="notice notice--warn">
      筛选没生效,现在显示的是全部公告
    </p>

    <!-- 加载骨架:形状对齐真实条目(徽章块 + 两行标题 + 一行 meta) -->
    <SkeletonList v-if="loading" :count="5" />

    <!-- 错误态:整页替换,带重试 -->
    <StateBlock
      v-else-if="error"
      variant="error"
      title="公告没拉出来"
      description="可能是网络断了,点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="load(true)"
    />

    <!-- 空态:分「全库无公告」与「该分类下无公告」两种,各自给一个出口 -->
    <StateBlock
      v-else-if="topList.length === 0 && normalList.length === 0"
      variant="empty"
      :title="category === null ? '还没有官方公告' : '这个分类下还没有公告'"
      :description="
        category === null
          ? '学校一发通知,这里就会第一时间出现'
          : '换个分类看看,或者直接看全部'
      "
      :action-label="category === null ? '回首页' : '看全部'"
      :primary="category === null"
      :icon="Bell"
      @action="primaryAction"
    />

    <template v-else>
      <!-- 置顶分组:靠分组 + 图钉 + 组头文字三重传达,不靠彩色左边框 -->
      <section v-if="topList.length" class="group">
        <h2 class="group__head">
          <el-icon :size="16"><Top /></el-icon>
          置顶
          <span class="group__count ys-num">{{ topList.length }}</span>
        </h2>
        <AnnouncementItem
          v-for="a in topList"
          :key="a.id"
          :item="a"
          :to="`/announcement/${a.id}`"
          :show-read-count="userStore.isAdmin"
        />
      </section>

      <section v-if="normalList.length" class="group">
        <h2 class="group__head">
          {{ category === null ? '全部公告' : CATEGORY_LABEL[category] }}
          <span class="group__count ys-num">{{ normalList.length }}</span>
        </h2>
        <AnnouncementItem
          v-for="a in normalList"
          :key="a.id"
          :item="a"
          :to="`/announcement/${a.id}`"
          :show-read-count="userStore.isAdmin"
        />
      </section>

      <!-- 无限滚动:底部 3 条骨架 -->
      <SkeletonList v-if="loadingMore" :count="2" variant="panel" />
      <p v-else-if="hasMore" class="more-hint" @click="loadMore">上拉看更多</p>
      <p v-else-if="all.length" class="more-hint more-hint--end">没有更多了</p>
    </template>
  </div>
</template>

<script setup>
/**
 * 公告列表(Announcement.vue.md)
 *
 * 关键判断:
 * - 未读/已读**不靠底色**区分(底色差异会让整页像斑马纹,破坏分区秩序),
 *   靠「左侧 6px 红点 + 标题字重 500」。
 * - 置顶靠「分组 + 图钉图标 + 组头文字」三重传达,
 *   到期后静默降入普通分组,不弹提示(避免用户以为公告被删了)。
 * - 未读数拉取失败**静默**:它是增强信息,不该让用户以为系统坏了。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Promotion, Top, WarningFilled, Bell } from '@element-plus/icons-vue'
import * as api from '@/api/announcement'
import { useUserStore } from '@/stores/user'
import AnnouncementItem from '@/components/AnnouncementItem.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

const router = useRouter()
const userStore = useUserStore()

const CATEGORY = { 1: '教务', 2: '后勤', 3: '活动', 4: '社团', 5: '紧急', 6: '其他' }
const CATEGORY_LABEL = CATEGORY
const CATEGORIES = [{ label: '全部', value: null }, ...Object.entries(CATEGORY).map(([value, label]) => ({ value: Number(value), label }))]

const all = ref([])
const category = ref(null)
const unread = ref(0)
const firstUnreadId = ref(null)
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref(false)
const filterFailed = ref(false)

const topList = computed(() => all.value.filter((a) => a.isTop === 1))
const normalList = computed(() => all.value.filter((a) => a.isTop !== 1))

function cap(n) {
  return n > 99 ? '99+' : String(n)
}

async function load(reset = false) {
  if (reset) {
    error.value = false
    loading.value = true
  } else {
    loadingMore.value = true
  }
  try {
    const data = await api.list({
      category: category.value,
      cursor: reset ? null : cursor.value,
      size: 20,
    })
    all.value = reset ? data.list || [] : [...all.value, ...(data.list || [])]
    cursor.value = data.nextCursor ?? null
    hasMore.value = !!data.hasMore
    filterFailed.value = false
  } catch {
    // 首次加载失败 = 整页错误态;翻页/筛选失败 = 保留上次结果 + 顶部说明
    if (reset) error.value = true
    else filterFailed.value = true
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  await load(false)
}

async function loadUnread() {
  try {
    const data = await api.unreadCount()
    unread.value = data?.unreadCount ?? 0
    firstUnreadId.value = data?.latestAnnouncementId ?? null
  } catch {
    unread.value = 0
  }
}

function pickCategory(v) {
  if (category.value === v) return
  category.value = v
  all.value = []
  cursor.value = null
  hasMore.value = false
  load(true)
}

function jumpFirstUnread() {
  const target = firstUnreadId.value || all.value.find((a) => !a.isRead)?.id
  if (target) router.push(`/announcement/${target}`)
}

function primaryAction() {
  if (category.value === null) router.push('/')
  else pickCategory(null)
}

onMounted(() => {
  load(true)
  loadUnread()
})
</script>

<style scoped src="./Announcement.css"></style>