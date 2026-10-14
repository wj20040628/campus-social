<template>
  <div class="mklist">
    <!--
      加载失败:整页替换 + 重试(与 Circles.vue 同纪律)。
      只有首屏失败才到这里;追加翻页失败保留已有内容,只在哨兵区给文案。
    -->
    <StateBlock
      v-if="failed"
      variant="error"
      title="活动列表没拉出来"
      description="可能是网络断了,点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="$emit('retry')"
    />

    <!-- 首屏骨架 -->
    <div v-else-if="loading && !list.length" class="mklist__skeleton" aria-hidden="true">
      <MktCardSkeleton v-for="n in 4" :key="n" />
    </div>

    <!-- 空态(增量④):广场 / 我的发布 各一句主句 + 可点出口 -->
    <StateBlock
      v-else-if="!loading && !list.length"
      variant="empty"
      :title="emptyTitle"
      :description="emptyDesc"
      :action-label="emptyAction"
      :icon="emptyIcon"
      @action="$emit('empty-action')"
    />

    <!-- 卡片网格:等宽自适应列(见下方 CSS),不做瀑布流 -->
    <template v-else>
      <div class="mklist__grid">
        <MktCard
          v-for="a in list"
          :key="a.id"
          :activity="a"
          :variant="variant"
          @open="$emit('open', a.id)"
          @edit="$emit('edit', a)"
          @toggle="$emit('toggle', a)"
          @full="$emit('full', a)"
          @reactivate="$emit('reactivate', a)"
          @delete="$emit('delete', a)"
        />
      </div>

      <!-- 无限滚动哨兵 -->
      <div ref="sentinel" class="mklist__sentinel">
        <span v-if="loadingMore" class="mklist__more">加载中…</span>
        <span v-else-if="!hasMore && list.length" class="mklist__more">到底了</span>
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * 集市活动列表(广场 / 我的发布 共用)
 * ------------------------------------------------------------------
 * 抽成组件的原因:两张卡片网格 + 游标分页 + 三态(空/错/成功)在广场和我的发布
 * 里各出现一次,结构完全同构,只差卡片形态(varant=plaza 纯展示 / my 加状态灯
 * 与操作组)。把形态差异收在 MktCard,分页哨兵收在本组件,页面只传数据。
 *
 * 本组件**自持**游标哨兵与加载更多,不向外泄漏分页状态:
 * 页面通过 props.list 传"当前已渲染的整份列表",通过 @load-more 触发追加,
 * 追加后由页面把新的整份列表传回来。这样本组件永远只有一份数据源。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { Plus, WarningFilled, OfficeBuilding, Tickets } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import MktCard from './MktCard.vue'
import MktCardSkeleton from './MktCardSkeleton.vue'

const props = defineProps({
  /** 'plaza' = 广场纯展示; 'my' = 我的发布(带 status 灯 + 操作组) */
  variant: { type: String, default: 'plaza' },
  loading: { type: Boolean, default: false },
  list: { type: Array, default: () => [] },
  hasMore: { type: Boolean, default: false },
  failed: { type: Boolean, default: false },
})

const emit = defineEmits([
  'open', 'edit', 'toggle', 'full', 'reactivate', 'delete',
  'load-more', 'empty-action', 'retry',
])

/* ---------------- 空态文案(AC-19:必须给可点出口) ---------------- */
const emptyTitle = computed(() =>
  props.variant === 'plaza' ? '暂无进行中的活动' : '你还没有发布过活动')
const emptyDesc = computed(() =>
  props.variant === 'plaza' ? '去发布一条,让别人找得到你' : '点右上角发布,发一条集市活动')
const emptyAction = computed(() => '去发布')
const emptyIcon = computed(() =>
  props.variant === 'plaza' ? OfficeBuilding : Tickets)

/* ---------------- 无限滚动哨兵(与 Circles.vue 同款纪律) ---------------- */
const sentinel = ref(null)
const loadingMore = ref(false)
let observer = null

async function onIntersect() {
  if (loadingMore.value || !props.hasMore || props.loading) return
  loadingMore.value = true
  try {
    emit('load-more')
  } finally {
    loadingMore.value = false
  }
}

onMounted(() => {
  if (!sentinel.value) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting) onIntersect()
    },
    { threshold: 0.1, rootMargin: '200px' }
  )
  observer.observe(sentinel.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<style scoped>
/*
 * 等宽卡片网格(计划 §广场布局):CSS Grid repeat(auto-fill, minmax) 做自适应列数,
 * 卡片等宽、自动换行,不做瀑布流。断点用字面量(本项目无 postcss-custom-media)。
 * 手机 1 列 / 平板(≥768)2 列 / 桌面(≥1280)4 列,由 minmax 下限自然分档。
 */
.mklist__grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--ys-space-4);
}

@media (min-width: 768px) {
  .mklist__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 1280px) {
  .mklist__grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

/* 骨架:与真实卡片同宽,4 个一排跟着网格走 */
.mklist__skeleton {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--ys-space-4);
}

@media (min-width: 768px) {
  .mklist__skeleton {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 1280px) {
  .mklist__skeleton {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

.mklist__sentinel {
  padding: var(--ys-space-4);
  text-align: center;
}

.mklist__more {
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}
</style>
