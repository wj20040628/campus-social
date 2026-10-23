<template>
  <div class="ann-wrap">
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
    <div
      ref="chipsEl"
      class="chips"
      :class="{ 'is-end': chipsAtEnd }"
      role="tablist"
      aria-label="公告分类"
      @scroll="onChipsScroll"
    >
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

      <!-- 无限滚动:底部骨架 -->
      <SkeletonList v-if="loadingMore" :count="2" variant="panel" />

      <!--
        尾部文案（2026-10-21 改）。
        分三种，不再是清一色的「没有更多了」：
          1. loadingMore → 上面的骨架已表达，这里不重复
          2. hasMore → 可点上拉
          3. 到底了 → 分「这一类本来就这么少」与「翻完了」
        第二种是本轮的重点：只有 1 条公告时，原先在页面正中显示
        一句「没有更多了」，下面 529px 全空 —— 既不好看，也不准确。
      -->
      <p v-else-if="hasMore" class="more-hint" @click="loadMore">上拉看更多</p>
      <div v-else-if="shown.length" class="tail">
        <p class="tail__t">
          {{
            shown.length <= 3
              ? `这一类只有 ${shown.length} 条`
              : '没有更多了'
          }}
        </p>
        <!-- 条数很少时给一个出口：换分类看，而不是让用户盯着空屏 -->
        <button
          v-if="shown.length <= 3 && category !== null"
          type="button"
          class="tail__act"
          @click="pickCategory(null)"
        >
          看全部公告
        </button>
      </div>
    </template>
  </div>

  <!--
    桌面端右栏（>=1280px 才显示，由 CSS 控制）。
    改版前 1440 窗口下内容列只有 640px，右侧 800px 整片空白 ——
    观感像「页面没做完」。这里补三块，**全部由已有数据算出，不新增接口**：
      阅读概况 —— unread / 本周新增 / 最近发布时间
      分类分布 —— 当前已加载条目按 category 聚合
      阅读说明 —— 静态文案，解释红点/置顶/紧急标签的含义
  -->
  <aside class="ann-aside" aria-label="公告信息">
    <section class="panel">
      <h3 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>阅读概况</h3>
      <div class="panel__row">
        <span>未读</span><b class="ys-num">{{ unread }} 条</b>
      </div>
      <div class="panel__row">
        <span>本周新增</span><b class="ys-num">{{ weekCount }} 条</b>
      </div>
      <div class="panel__row">
        <span>最近发布</span><b>{{ latestText }}</b>
      </div>
    </section>

    <section class="panel">
      <h3 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>分类分布</h3>
      <div v-for="c in categoryStats" :key="c.label" class="panel__row">
        <span>{{ c.label }}</span><b class="ys-num">{{ c.count }}</b>
      </div>
      <p v-if="!categoryStats.length" class="panel__hint">还没有可统计的公告</p>
    </section>

    <section class="panel">
      <h3 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>怎么看公告</h3>
      <p class="panel__tip">
        <b>红点是未读</b>，读过之后就会消失。<br />
        <b>置顶的永远排在最上面</b>，到期才降下来。<br />
        紧急公告带<b>红色标签</b>，别错过。
      </p>
    </section>
  </aside>
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
import { relTime } from '@/utils/relTime'
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

/** chip 横滑条已滑到最右端 —— 到了就取消右侧渐隐，避免「一直暗示可滑却滑不动」 */
const chipsEl = ref(null)
const chipsAtEnd = ref(false)

function onChipsScroll() {
  const el = chipsEl.value
  if (!el) return
  // 允许 1px 误差：某些设备/缩放下列表宽度不是整数
  chipsAtEnd.value = el.scrollLeft + el.clientWidth >= el.scrollWidth - 1
}

const topList = computed(() => all.value.filter((a) => a.isTop === 1))
const normalList = computed(() => all.value.filter((a) => a.isTop !== 1))

/**
 * 当前筛选下展示的条目。
 *
 * 【为什么需要这个 computed】原先模板里分散用 topList / normalList，
 * 判断「有没有内容」要写 `topList.length === 0 && normalList.length === 0`，
 * 尾部文案又要用 all.length —— 三处各自算，容易出现「列表空了但尾部还说有内容」。
 * 收敛成一个 computed，判断只有一处。
 */
const shown = computed(() => [...topList.value, ...normalList.value])

function cap(n) {
  return n > 99 ? '99+' : String(n)
}

// ---------------------------------------------------------------- 右栏统计
/**
 * 右栏三个数字全部由**已加载的 all[]** 算出，不新增任何请求。
 *
 * 【口径说明】它们描述的是「当前已加载的公告」，不是全库。
 * 列表是 20 条一页的游标分页，未翻完时统计会偏小 ——
 * 所以文案写「本周新增」而不是「本周全部」，避免误导。
 * 真要全库统计得后端加接口，为三行字加一次全表扫描不值。
 */
const WEEK_MS = 7 * 24 * 3600 * 1000

const weekCount = computed(() => {
  const now = Date.now()
  return all.value.filter((a) => {
    const t = a.publishTime
    if (!t) return false
    const ms = new Date(String(t).replace(' ', 'T')).getTime()
    return !Number.isNaN(ms) && now - ms <= WEEK_MS
  }).length
})

/** 最近一条的发布时间，用相对时间，与列表条目同口径 */
const latestText = computed(() => {
  const t = all.value[0]?.publishTime
  return t ? relTime(t) || '—' : '—'
})

/**
 * 分类分布。按 CATEGORY 的顺序输出，只保留有内容的分类 ——
 * 一排「教务 0 / 后勤 0 / 活动 0」既占地方又没有信息量。
 */
const categoryStats = computed(() => {
  const counter = new Map()
  for (const a of all.value) {
    const c = a.category
    if (c == null) continue
    counter.set(c, (counter.get(c) || 0) + 1)
  }
  return Object.entries(CATEGORY)
    .map(([value, label]) => ({ label, count: counter.get(Number(value)) || 0 }))
    .filter((x) => x.count > 0)
})

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