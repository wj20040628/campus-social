<template>
  <!--
    四项统计(2026-10-20 从三项扩为四项,新增「获赞」)。
    顺序:动态 → 获赞 → 粉丝 → 关注。
    这是「我产出什么 → 我获得什么 → 谁在看我 → 我在看谁」的递进,
    与抖音(获赞/互关/关注/粉丝)、快手(粉丝/关注/获赞)的排法同源:
    数据紧贴身份头,中间不插别的模块,形成「人物档案」的整体印象。

    粉丝/关注是可点 button(打开对应列表抽屉),
    「动态」「获赞」是纯 div —— 不能用同一元素靠 class 区分,
    读屏器会把不可点项也念成可点的。
  -->
  <div class="pstats">
    <div v-for="s in staticStats" :key="s.label" class="pstats__item">
      <span class="pstats__num">{{ cap(s.value) }}</span>
      <span class="pstats__label">{{ s.label }}</span>
    </div>
    <button
      v-for="s in clickableStats"
      :key="s.label"
      type="button"
      class="pstats__item pstats__item--btn"
      @click="$emit('switch', s.tab)"
    >
      <span class="pstats__num">{{ cap(s.value) }}</span>
      <span class="pstats__label">{{ s.label }}</span>
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { capCount as cap } from '@/composables/usePostDetail'

const props = defineProps({
  user: { type: Object, required: true },
})

defineEmits(['switch'])

/** 不可点的两项:动态(我发的)、获赞(别人给的) */
const staticStats = computed(() => [
  { label: '动态', value: props.user?.postCount ?? 0 },
  { label: '获赞', value: props.user?.likeCount ?? 0 },
])

/** 可点的两项:点开各自的列表抽屉(不再占用内容 Tab) */
const clickableStats = computed(() => [
  { label: '粉丝', value: props.user?.followerCount ?? 0, tab: 'followers' },
  { label: '关注', value: props.user?.followingCount ?? 0, tab: 'following' },
])
</script>

<style scoped>
/* 四统计:等分栅格,无卡片无竖线,数字等宽防跳动 */
.pstats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ys-space-1);
  margin: var(--ys-space-3) 0 0;
}
.pstats__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ys-space-1);
  min-height: var(--ys-touch-min);
  padding: var(--ys-space-2) 0;
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  color: var(--ys-fg);
  text-align: center;
}
.pstats__item--btn {
  cursor: pointer;
  transition: color var(--ys-motion-fast) var(--ys-ease-out);
}
.pstats__item--btn:hover {
  color: var(--ys-accent);
}
.pstats__item--btn:active {
  opacity: 0.7;
}
.pstats__num {
  font-size: var(--ys-text-lg);
  line-height: var(--ys-lh-lg);
  font-weight: var(--ys-weight-semibold);
  font-family: var(--ys-font-mono);
  font-variant-numeric: tabular-nums;
}
.pstats__label {
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}
</style>
