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
      <!--
        「N 成员」改成可点入口（2026-10-21）。
        改版前它是一个纯文本 —— 圈子头是全页最显眼的位置，
        却把「里面有哪些人」这个决定加不加入的关键信息做成了不可点的数字。
        现在点它进成员页。
      -->
      <RouterLink
        :to="`/circles/${circle.id}/members`"
        class="cd__count cd__count--link ys-num"
      >
        {{ cap(circle.memberCount) }} 成员
      </RouterLink>
      <!--
        举报圈子（2026-10-29）：自评估报告承诺「用户可对圈子发起举报」。
        圈主不显示——举报自己的圈子没有意义；成员与非成员都可报。
      -->
      <button
        v-if="canReport"
        type="button"
        class="cd__report"
        @click="reportOpen = true"
      >举报</button>
    </div>

    <!-- 举报圈子（2026-10-29）：targetType=9，管理端「解散圈子」= 后端 takedown -->
    <ReportSheet
      v-model="reportOpen"
      :target-type="9"
      :target-id="circle.id"
      sheet-title="举报这个圈子"
      :submit-fn="submitReport"
    />
  </header>
</template>

<script setup>
/**
 * 圈子详情 - 圈子头(从 CircleDetail.vue 抽出)。
 * 只负责展示 + 一个加入按钮,自己不调接口 —— 加入后的状态同步由父组件管,
 * 否则会出现「组件自己把 joined 改了、父组件的 canCreate 却还按旧值算」。
 */
import { computed, ref } from 'vue'
import { Setting } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'
import ReportSheet from '@/components/post/ReportSheet.vue'
import { submitReport } from '@/api/review'

const props = defineProps({
  circle: { type: Object, required: true },
  joining: { type: Boolean, default: false },
  leaving: { type: Boolean, default: false },
})
defineEmits(['join', 'leave', 'manage'])

/** 圈子举报表开合（2026-10-29）；入口在本组件成员数右侧的「举报」 */
const reportOpen = ref(false)

/** myRole:1 圈主 / 2 圈管理 / 3 成员 / 0 非成员。role<=2 露管理入口。 */
const canManage = computed(() => {
  const r = Number(props.circle?.myRole)
  return r === 1 || r === 2
})

/** 圈主不举报自己的圈子；其余角色（含非成员）都可发起举报 */
const canReport = computed(() => Number(props.circle?.myRole) !== 1)

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