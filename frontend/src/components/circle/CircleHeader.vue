<template>
  <header class="cd__banner">
    <div class="cd__title-row">
      <h1 class="cd__name">{{ circle.name }}</h1>
      <!-- 圈主/圈管理(role<=2):露「管理」入口;成员(3)/非成员(0) 不露 -->
      <button
        v-if="canManage"
        type="button"
        class="cd__manage"
        @click="$emit('manage')"
      >
        <el-icon :size="14"><Setting /></el-icon>
        管理
      </button>
      <!--
        已加入状态用「图标 + 文字 + 颜色」三通道,不能只靠绿色(§1.1)。
        aria-live 播报状态变化(§5)。
      -->
      <template v-if="circle.joined">
      
        <!--
          退圈入口:仅普通成员(role=3)可退。
          圈主(myRole=1)后端拦「创建者不可退出」;圈管理(role=2)当前后端 leave 只处理
          role=3,同样退不出——故 1/2 都不露,避免点了必 422。
        -->
        <button
          v-if="circle.myRole === 3"
          type="button"
          class="cd__leave"
          :disabled="leaving"
          @click="$emit('leave')"
        >{{ leaving ? '退出中' : '退出圈子' }}</button>
      </template>
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
import { CircleCheck, Setting } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'

const props = defineProps({
  circle: { type: Object, required: true },
  joining: { type: Boolean, default: false },
  leaving: { type: Boolean, default: false },
})
defineEmits(['join', 'leave', 'manage'])

/** myRole:1 圈主 / 2 圈管理 / 3 成员 / 0 非成员。role<=2 露管理入口。 */
const canManage = computed(() => {
  const r = Number(props.circle?.myRole)
  return r === 1 || r === 2
})

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