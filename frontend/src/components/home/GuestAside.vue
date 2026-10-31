<template>
  <!--
    游客桌面右栏（2026-10-31）。
    与登录用户的 HomeAside 分开：公告、加入圈子等动作对游客要么不可见、
    要么点了只弹登录提示，混在一起会把「能看什么」和「不能做什么」搅乱。
    这里只放游客确实能点开的公开内容。
  -->
  <aside class="gas" aria-label="游客浏览">
    <section class="gas__box">
      <h3 class="gas__head">
        推荐圈子
        <button v-if="canSwapCircles" type="button" class="gas__more gas__swap" @click="swapCircles">换一批</button>
      </h3>
      <p v-if="circlesLoading" class="gas__hint">加载中…</p>
      <p v-else-if="circlesFailed" class="gas__hint">
        <button type="button" class="gas__retry" @click="loadCircles">加载失败，点此重试</button>
      </p>
      <template v-else>
        <button
          v-for="c in shownCircles"
          :key="c.id"
          type="button"
          class="gas__row"
          @click="router.push(`/circles/${c.id}`)"
        >
          <InitialAvatar :size="30" :seed="c.id" :name="c.name" />
          <span class="gas__row-main">
            <b>{{ c.name }}</b>
            <span>{{ c.memberCount || 0 }} 人</span>
          </span>
          <el-icon class="gas__arrow" :size="14"><ArrowRight /></el-icon>
        </button>
        <p v-if="!circles.length" class="gas__hint">还没有公开圈子</p>
        <button v-else type="button" class="gas__foot" @click="router.push('/circles')">
          查看全部圈子 ›
        </button>
      </template>
    </section>

    <section class="gas__box">
      <h3 class="gas__head">
        集市正在发生
        <button type="button" class="gas__more" @click="router.push('/mkt')">全部 ›</button>
      </h3>
      <p v-if="mktLoading" class="gas__hint">加载中…</p>
      <p v-else-if="mktFailed" class="gas__hint">
        <button type="button" class="gas__retry" @click="loadMkt">加载失败，点此重试</button>
      </p>
      <template v-else>
        <button
          v-for="a in mkt"
          :key="a.id"
          type="button"
          class="gas__row gas__row--mkt"
          @click="router.push(`/mkt/${a.id}`)"
        >
          <span class="gas__tag">{{ a.category }}</span>
          <span class="gas__row-main">
            <b>{{ mktTitle(a) }}</b>
            <span>{{ mktMeta(a) }}</span>
          </span>
        </button>
        <p v-if="!mkt.length" class="gas__hint">集市还没有正在进行的活动</p>
      </template>
    </section>

    <section class="gas__box">
      <h3 class="gas__head">热门话题</h3>
      <button
        v-for="(t, i) in hotTopics"
        :key="t.name"
        type="button"
        class="gas__topic"
        @click="router.push(`/topic/${encodeURIComponent(t.name)}`)"
      >
        <span class="gas__rank" :class="{ 'gas__rank--hot': i < 2 }">{{ i + 1 }}</span>
        <span class="gas__topic-name"># {{ t.name }}</span>
        <span class="gas__count">{{ t.count }} 条</span>
      </button>
      <p v-if="!hotTopics.length" class="gas__hint">等大家发帖后，这里会出现话题</p>
    </section>
  </aside>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import * as circleApi from '@/api/circle'
import { mktPlaza } from '@/api/mkt'

const props = defineProps({
  /** 当前 feed 帖子，用于聚合话题；不额外请求热搜接口 */
  posts: { type: Array, default: () => [] },
})

const router = useRouter()
const circles = ref([])
const mkt = ref([])
const circlesLoading = ref(true)
const mktLoading = ref(true)
const circlesFailed = ref(false)
const mktFailed = ref(false)

/** 推荐位一屏 4 个；多于 4 个时给「换一批」，轮换而不是重新请求 */
const CIRCLE_BATCH = 4
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

function mktTitle(a) {
  return a.title || a.mainText || a.content || '集市活动'
}

function mktMeta(a) {
  const parts = []
  if (a.location) parts.push(a.location)
  const remain = remainText(a.expireTime)
  if (remain) parts.push(remain)
  return parts.join(' · ') || '正在投放'
}

function remainText(t) {
  if (!t) return ''
  const ms = new Date(String(t).replace(' ', 'T')).getTime()
  if (Number.isNaN(ms)) return ''
  const diff = ms - Date.now()
  if (diff <= 0) return ''
  const mins = Math.ceil(diff / 60000)
  return mins < 60 ? `剩 ${mins} 分钟` : `剩 ${Math.ceil(mins / 60)} 小时`
}

async function loadCircles() {
  circlesLoading.value = true
  circlesFailed.value = false
  try {
    // 右栏失败只在卡片内提示，不弹全站 toast
    const data = await circleApi.list(null, 20, { silent: true })
    circles.value = data?.list || []
  } catch {
    // 失败与「没有圈子」必须分开：把网络故障说成空数据，用户会以为社区没人
    circlesFailed.value = true
  } finally {
    circlesLoading.value = false
  }
}

async function loadMkt() {
  mktLoading.value = true
  mktFailed.value = false
  try {
    const data = await mktPlaza(null, null, 4, { silent: true })
    mkt.value = (data?.list || []).slice(0, 4)
  } catch {
    mktFailed.value = true
  } finally {
    mktLoading.value = false
  }
}

onMounted(() => {
  loadCircles()
  loadMkt()
})
</script>

<style scoped>
.gas {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-4);
}

.gas__box {
  padding: var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
  background: var(--ys-surface);
}

.gas__head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.gas__more {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ys-muted);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}

.gas__more:hover {
  color: var(--ys-accent);
}

.gas__hint {
  margin: 0;
  padding: var(--ys-space-2) 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.gas__retry {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}

.gas__retry:hover {
  text-decoration: underline;
}

.gas__row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  width: 100%;
  padding: var(--ys-space-2) 0;
  border: 0;
  border-top: 1px solid var(--ys-border-soft);
  background: transparent;
  font-family: var(--ys-font-body);
  text-align: left;
  cursor: pointer;
}

.gas__row:first-of-type {
  border-top: 0;
}

.gas__row:hover .gas__row-main b {
  color: var(--ys-accent);
}

.gas__row-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.gas__row-main b {
  overflow: hidden;
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gas__row-main span {
  overflow: hidden;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gas__arrow {
  flex: none;
  color: var(--ys-meta);
}

.gas__row--mkt {
  align-items: flex-start;
}

.gas__tag {
  flex: none;
  margin-top: 2px;
  padding: 2px 6px;
  border-radius: var(--ys-radius-sm);
  background: var(--ys-accent-2-soft);
  color: var(--ys-accent-2);
  font-size: 10.5px;
  white-space: nowrap;
}

.gas__topic {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  width: 100%;
  padding: var(--ys-space-2) 0;
  border: 0;
  border-top: 1px solid var(--ys-border-soft);
  background: transparent;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  text-align: left;
  cursor: pointer;
}

.gas__topic:first-of-type {
  border-top: 0;
}

.gas__topic:hover .gas__topic-name {
  color: var(--ys-accent);
}

.gas__rank {
  flex: none;
  width: 16px;
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}

.gas__rank--hot {
  color: var(--ys-danger);
  font-weight: var(--ys-weight-semibold);
}

.gas__topic-name {
  overflow: hidden;
  flex: 1;
  min-width: 0;
  color: var(--ys-fg);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gas__count {
  flex: none;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.gas__foot {
  margin-top: var(--ys-space-2);
  padding: var(--ys-space-2) 0 0;
  border: 0;
  border-top: 1px solid var(--ys-border-soft);
  background: transparent;
  color: var(--ys-muted);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}

.gas__foot:hover {
  color: var(--ys-accent);
}
</style>
