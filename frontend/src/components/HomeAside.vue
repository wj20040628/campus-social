<template>
  <!--
    首页桌面右栏（仅 >=1280px 由 Home.vue 决定渲染，组件内不做断点判断）。

    2026-10-21 重做，四个模块：
      公告       -> GET /announcement      取最新 1 条做强调卡，不再列 3 条标题
      热门话题   -> 由当前 feed 帖子的话题聚合（没有热搜接口，不编造数据）
      推荐圈子   -> GET /circle            补「加入」按钮 + 「换一批」
      社区       -> 由已有响应算：本周新帖 / 活跃圈子 / 集市活动数

    拉取失败全部静默：右栏是增强信息，挂了不该让用户觉得系统坏了
    （与 MainLayout 未读数、纸条数的降级策略一致）。
  -->
  <aside class="has" aria-label="首页侧栏">
    <!-- ============ 公告：单条强调卡 ============ -->
    <section class="has__box">
      <h3 class="has__head">
        <span class="has__sq has__sq--o" aria-hidden="true"></span>公告
        <button type="button" class="has__more" @click="go('/announcement')">全部 ›</button>
      </h3>
      <p v-if="annLoading" class="has__hint">加载中…</p>
      <template v-else>
        <!--
          改版前这里列 3 条「标题 …… 10-12」，等于把公告列表搬进右栏：
          标题被 ellipsis 截断、日期只有 MM-DD、看不出谁发的、也没有「新」的信号。
          现在只取**最新一条**做暖橙强调卡：标题两行完整可读 + 来源与日期 + 未读标记。
          想看更多走「全部 ›」。
        -->
        <button
          v-if="latestAnn"
          type="button"
          class="ann"
          @click="go(`/announcement/${latestAnn.id}`)"
        >
          <span class="ann__t">{{ latestAnn.title }}</span>
          <span class="ann__m">
            <span class="ys-num">{{ shortDate(latestAnn.publishTime) }}</span>
            <template v-if="annSource"> · {{ annSource }}</template>
          </span>
          <span v-if="latestAnn.isRead === false" class="ann__dot" aria-label="未读" />
        </button>
        <p v-else class="has__hint">还没有公告</p>
      </template>
    </section>

    <!-- ============ 热门话题：带排名 ============ -->
    <section class="has__box">
      <h3 class="has__head">
        <span class="has__sq" aria-hidden="true"></span>热门话题
        <span class="has__meta">本周</span>
      </h3>
      <button
        v-for="(t, i) in hotTopics"
        :key="t.name"
        type="button"
        class="trow"
        @click="go(`/topic/${encodeURIComponent(t.name)}`)"
      >
        <!-- 前二用危险色：榜位视觉惯例，也避免整列都是同一个灰 -->
        <span class="trow__rank ys-num" :class="{ 'trow__rank--hot': i < 2 }">{{ i + 1 }}</span>
        <span class="trow__name"># {{ t.name }}</span>
        <!--
          改版前只写一个裸数字「2」—— 看不出是条数、热度还是参与人数。
          补单位「条」，语义才立起来。
        -->
        <span class="trow__cnt ys-num">{{ t.count }} 条</span>
      </button>
      <p v-if="!hotTopics.length" class="has__hint">多发几条,这里就有话题了</p>
    </section>

    <!-- ============ 推荐圈子：可就地加入 ============ -->
    <section class="has__box">
      <h3 class="has__head">
        <span class="has__sq" aria-hidden="true"></span>推荐圈子
        <!-- C15 同款：轮换窗口，按活跃顺序看下一组 -->
        <button v-if="canSwapCircles" type="button" class="has__more" @click="swapCircles">换一批</button>
      </h3>
      <div v-for="c in shownCircles" :key="c.id" class="crow2">
        <InitialAvatar :size="26" :seed="c.id" :name="c.name" class="crow2__av" />
        <button type="button" class="crow2__name" @click="go(`/circles/${c.id}`)">{{ c.name }}</button>
        <!--
          改版前只有「羽毛球 1 人」，要加入必须点进圈子 —— 右栏完全没有行动价值。
          补一个轻量「加入」：不跳页、就地完成，与圈子列表页的加入同一条接口。
        -->
        <button
          type="button"
          class="crow2__join"
          :disabled="joiningId === c.id"
          :aria-label="`加入圈子 ${c.name}`"
          @click="joinCircle(c)"
        >
          加入
        </button>
      </div>
      <p v-if="!circles.length" class="has__hint">还没有可推荐的圈子</p>
    </section>

    <!-- ============ 社区数据：把底部空白填成有信息量的一块 ============ -->
    <section v-if="stats" class="has__box">
      <h3 class="has__head">
        <span class="has__sq" aria-hidden="true"></span>社区
      </h3>
      <!--
        三个数都来自**已经拉过的响应**，不为右栏新增任何请求：
          - 本周新帖：当前 feed 里 7 天内的帖子数
          - 活跃圈子：有 lastPostAt 且在 7 天内的圈子数
          - 集市活动：mktPlaza 的在投放活动数（用户要求用它替代「认证率」）
        刻意不写「认证率」：那个需要遍历全校用户，为一行字加一次全表扫描不值。
      -->
      <div class="stat">
        <span>本周新帖</span><b class="ys-num">{{ stats.weekPosts }}</b>
      </div>
      <div class="stat">
        <span>活跃圈子</span><b class="ys-num">{{ stats.activeCircles }}</b>
      </div>
      <div class="stat">
        <span>集市活动</span><b class="ys-num">{{ stats.mktCount }}</b>
      </div>
      <!--
        三个数全 0 时给一句可解释的话。对着三个 0 用户只会以为社区死了，
        而真实原因可能是「你关注的人这周没发」（关注流走 feed_item，
        与全站新帖是两回事）。不显示一行「暂无」，而是说清口径。
      -->
      <p v-if="stats.allZero" class="has__hint">当前范围内本周还没有新动态</p>
    </section>
  </aside>
</template>

<script setup>
/**
 * 首页桌面右栏。四个模块数据各自独立加载，互不阻塞。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import * as announcementApi from '@/api/announcement'
import * as circleApi from '@/api/circle'
import { mktPlaza } from '@/api/mkt'
import { ElMessage } from '@/utils/feedback'
import InitialAvatar from '@/components/common/InitialAvatar.vue'

const props = defineProps({
  /** 当前 feed 的帖子(Home 传入),用于聚合热搜话题与「本周新帖」 */
  posts: { type: Array, default: () => [] },
})

const router = useRouter()

const anns = ref([])
const annLoading = ref(true)
const circles = ref([])
const mktCount = ref(0)

/** 只取最新一条做强调卡，其余交给「全部 ›」 */
const latestAnn = computed(() => anns.value[0] || null)

/**
 * 公告来源：优先用后端下发的 publisher 昵称。
 * 后端当前 nickname 可能为 null（老账号未设昵称），此时退化成不发来源，
 * 不要用「管理员」这类编出来的词 —— 编一个身份比不显示更糟。
 */
const annSource = computed(() => {
  const p = latestAnn.value?.publisher
  return p?.nickname || ''
})

// ============ 推荐圈子：轮换窗口 ============
/**
 * 推荐位数量。
 *
 * 原先取 3，但实测：库里 7 个圈、已加入 5 个 → 只剩 2 个可推荐，
 * 「换一批」永不出现（canSwap 要求 > 3）。所以取 2 ——
 * 让推荐位数量对齐「实际可推荐的规模」，避免一个永远不出现的按钮。
 *
 * 真正该扩的是圈子总量，不是这个数字：有更多圈时这里自然会变成 3 个一排。
 */
const CIRCLE_BATCH = 2
const circleOffset = ref(0)

const shownCircles = computed(() => {
  const all = circles.value
  if (all.length <= CIRCLE_BATCH) return all
  const start = circleOffset.value % all.length
  return [...all.slice(start), ...all.slice(0, start)].slice(0, CIRCLE_BATCH)
})

const canSwapCircles = computed(() => circles.value.length > CIRCLE_BATCH)

function swapCircles() {
  const n = circles.value.length
  if (n <= CIRCLE_BATCH) return
  circleOffset.value = (circleOffset.value + CIRCLE_BATCH) % n
}

/** 正在加入的圈子 id：防连点重复请求 */
const joiningId = ref(null)

async function joinCircle(c) {
  if (joiningId.value) return
  joiningId.value = c.id
  try {
    await circleApi.join(c.id)
    ElMessage.success(`已加入「${c.name}」`)
    // 加入后从推荐里移除 —— 推荐位应该一直在推「你还没加入的」
    circles.value = circles.value.filter((x) => x.id !== c.id)
    if (circleOffset.value >= circles.value.length) circleOffset.value = 0
  } catch {
    // request.js 已按 status 弹过错误；这里不再叠加第二条 toast
  } finally {
    joiningId.value = null
  }
}

// ============ 热搜话题 ============
/** 热搜 = 当前 feed 里出现次数最多的话题。真实数据,不是编的热搜榜 */
const hotTopics = computed(() => {
  const counter = new Map()
  for (const p of props.posts) {
    for (const t of p.topics || []) {
      counter.set(t, (counter.get(t) || 0) + 1)
    }
  }
  return [...counter.entries()]
    .map(([name, count]) => ({ name, count }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
    .slice(0, 5)
})

// ============ 社区数据 ============
const WEEK_MS = 7 * 24 * 3600 * 1000

/**
 * 三个指标全部由**已有响应**算出，不新增请求。
 *
 * 口径说明（重要）：`weekPosts` 数的是**当前 feed 里**7 天内的帖子，
 * 而首页有「关注 / 全部」两个 tab —— 关注流走 feed_item 收件箱，
 * 里面可能全是更早的历史帖（实测该账号关注流最新一条是 9 月 15 日）。
 * 此时显示「本周新帖 0」是**正确的**，但它描述的是「你关注的人这一周没发」，
 * 不是「全站这一周没新帖」。
 *
 * 所以标签写「本周新帖」而不是「全站新帖」，并在 0 值时给出可解释的空态文案，
 * 避免用户以为社区死寂（见模板里的 statsHint）。
 */
const stats = computed(() => {
  const now = Date.now()
  const withinWeek = (t) => {
    if (!t) return false
    const ms = new Date(String(t).replace(' ', 'T')).getTime()
    return !Number.isNaN(ms) && now - ms <= WEEK_MS
  }
  const weekPosts = props.posts.filter((p) => withinWeek(p.createTime)).length
  const activeCircles = circles.value.filter((c) => withinWeek(c.lastPostAt)).length
  if (!props.posts.length && !circles.value.length) return null
  return {
    weekPosts,
    activeCircles,
    mktCount: mktCount.value,
    // 三个数全 0 时给一句解释，不让人对着 0 猜
    allZero: weekPosts === 0 && activeCircles === 0 && mktCount.value === 0,
  }
})

function shortDate(t) {
  return t ? dayjs(t).format('MM-DD') : ''
}

function go(path) {
  router.push(path)
}

onMounted(async () => {
  const loadAnn = async () => {
    try {
      const data = await announcementApi.list({ size: 3 })
      anns.value = data?.list || []
    } catch {
      /* 静默:右栏挂了不打扰用户 */
    } finally {
      annLoading.value = false
    }
  }
  const loadCircles = async () => {
    try {
      // 多取一些：要过滤掉已加入的，剩下的才够凑 3 个推荐位
      const data = await circleApi.list(null, 12)
      circles.value = (data?.list || []).filter((c) => !c.joined)
    } catch {
      /* 同上 */
    }
  }
  const loadMkt = async () => {
    try {
      // 广场只返回「投放中且未满员」的，正是「现在有几个活动可参加」的口径
      const data = await mktPlaza(null, null, 20)
      mktCount.value = (data?.list || []).length
    } catch {
      /* 同上 */
    }
  }
  await Promise.all([loadAnn(), loadCircles(), loadMkt()])
})
</script>

<style scoped src="./HomeAside.css"></style>