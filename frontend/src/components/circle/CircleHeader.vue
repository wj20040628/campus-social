<template>
  <header class="cd__banner">
    <div class="cd__title-row">
      <h1 class="cd__name">{{ circle.name }}</h1>
      <!--
        已加入状态用「图标 + 文字 + 颜色」三通道,不能只靠绿色(§1.1)。
        aria-live 播报状态变化(§5)。
      -->
      <span v-if="circle.joined" class="cd__joined" aria-live="polite">
        <el-icon :size="16"><CircleCheck /></el-icon>
        已加入
      </span>
      <button v-else type="button" class="cd__join" :disabled="joining" @click="$emit('join')">
        {{ joining ? '加入中' : '加入圈子' }}
      </button>
    </div>
    <div class="cd__meta">
      <!--
        圈主头像用「名字首字 + 圆形底」的文字头像,不用通用 user 图标 ——
        通用图标在列表里看不出是谁。
      -->
      <span class="cd__avatar" aria-hidden="true">
        {{ circle.owner ? ownerName.slice(0, 1) : '圈' }}
      </span>
      <span class="cd__owner">{{ ownerName }}</span>
      <span class="cd__dot">·</span>
      <span class="cd__count ys-num">{{ cap(circle.memberCount) }} 成员</span>
    </div>
  </header>
</template>

<script setup>
/**
 * 圈子详情 - 圈子头(从 CircleDetail.vue 抽出)。
 * 只负责展示 + 一个加入按钮,自己不调接口 —— 加入后的状态同步由父组件管,
 * 否则会出现「组件自己把 joined 改了、父组件的 canCreate 却还按旧值算」。
 */
import { computed } from 'vue'
import { CircleCheck } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'

const props = defineProps({
  circle: { type: Object, required: true },
  joining: { type: Boolean, default: false },
})
defineEmits(['join'])

const ownerName = computed(() =>
  props.circle.owner ? displayName(props.circle.owner) : '暂无圈主',
)

/** 成员数 999 封顶:五位数在 375px 下会把「成员」挤出去 */
function cap(n) {
  const v = Number(n) || 0
  return v > 999 ? '999+' : String(v)
}
</script>

<style scoped src="./CircleHeader.css"></style>