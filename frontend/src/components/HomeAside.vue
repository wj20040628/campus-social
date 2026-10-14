<template>
  <!--
    首页桌面右栏(仅 >=1280px 由 Home.vue 决定渲染,组件内不做断点判断)。
    三个模块全部复用已有接口,不加新页面:
      公告速览 -> GET /announcement        (announcement.list)
      热搜话题 -> 由当前 feed 帖子的话题聚合(没有热搜接口,不编造数据)
      推荐圈子 -> GET /circle               (circle.list)
    拉取失败全部静默:右栏是增强信息,挂了不该让用户觉得系统坏了
    (与 MainLayout 未读数、纸条数的降级策略一致)。
  -->
  <aside class="has" aria-label="首页侧栏">
    <section class="has__box">
      <h3 class="has__head"><span class="has__sq has__sq--o" aria-hidden="true"></span>公告速览</h3>
      <p v-if="annLoading" class="has__hint">加载中…</p>
      <template v-else>
        <button v-for="a in anns" :key="a.id" type="button" class="has__row" @click="go(`/announcement/${a.id}`)">
          <span class="has__t">{{ a.title }}</span>
          <span class="has__m ys-num">{{ shortDate(a.publishTime) }}</span>
        </button>
        <p v-if="!anns.length" class="has__hint">还没有公告</p>
      </template>
    </section>

    <section class="has__box">
      <h3 class="has__head"><span class="has__sq" aria-hidden="true"></span>热搜话题</h3>
      <button v-for="t in hotTopics" :key="t.name" type="button" class="has__row" @click="go(`/topic/${encodeURIComponent(t.name)}`)">
        <span class="has__t"># {{ t.name }}</span>
        <span class="has__m ys-num">{{ t.count }}</span>
      </button>
      <p v-if="!hotTopics.length" class="has__hint">多发几条,这里就有话题了</p>
    </section>

    <section class="has__box">
      <h3 class="has__head"><span class="has__sq" aria-hidden="true"></span>推荐圈子</h3>
      <button v-for="c in circles" :key="c.id" type="button" class="has__row" @click="go(`/circles/${c.id}`)">
        <span class="has__t">{{ c.name }}</span>
        <span class="has__m ys-num">{{ c.memberCount }} 人</span>
      </button>
    </section>
  </aside>
</template>

<script setup>
/**
 * 首页桌面右栏。sticky 跟随滚动;数据各自独立加载,互不阻塞。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import * as announcementApi from '@/api/announcement'
import * as circleApi from '@/api/circle'

const props = defineProps({
  /** 当前 feed 的帖子(Home 传入),仅用于聚合热搜话题 */
  posts: { type: Array, default: () => [] },
})

const router = useRouter()

const anns = ref([])
const annLoading = ref(true)
const circles = ref([])

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
      const data = await circleApi.list(null, 5)
      circles.value = data?.list || []
    } catch {
      /* 同上 */
    }
  }
  await Promise.all([loadAnn(), loadCircles()])
})
</script>

<style scoped>
.has {
  position: sticky;
  top: var(--ys-space-6);
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-3);
}

.has__box {
  background: var(--ys-surface);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
  padding: var(--ys-space-3) var(--ys-space-4);
  box-shadow: var(--ys-shadow-1);
}

.has__head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  margin-bottom: var(--ys-space-1);
}

/* 小色块当模块标识:公告用辅色橙,话题/圈子用主色青 */
.has__sq {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  background: var(--ys-accent);
  flex: none;
}
.has__sq--o {
  background: var(--ys-accent-2);
}

.has__row {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-2);
  width: 100%;
  padding: var(--ys-space-2) 0;
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  text-align: left;
  cursor: pointer;
  border-top: 1px solid var(--ys-border-soft);
}
.has__row:first-of-type {
  border-top: 0;
}
.has__row:hover .has__t {
  color: var(--ys-accent);
}

.has__t {
  flex: 1;
  min-width: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-fg-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color var(--ys-motion-fast) var(--ys-ease-out);
}

.has__m {
  flex: none;
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}

.has__hint {
  padding: var(--ys-space-2) 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}
</style>
