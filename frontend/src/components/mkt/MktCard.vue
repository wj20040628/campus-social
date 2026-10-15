<template>
  <!--
    集市活动卡片(广场 / 我的发布 同款,形态差异收在内部)
    ------------------------------------------------------------------
    广场(variant=plaza):纯展示,整卡可点 → 详情。
    我的发布(variant=my):左侧一列状态灯 + 右侧操作按钮组;
      重激活仅在 暂停/过期 时可见(计划 §卡片要素)。
    倒计时 1s 间隔,剩余 ≤ 2h 切橙红。
  -->
  <div class="mktcard" :class="variant === 'my' ? 'mktcard--my' : 'mktcard--plaza'">
    <!-- 我的发布:状态灯(图标 + 文字双通道,不只靠颜色) -->
    <div v-if="variant === 'my'" class="mktcard__lamp">
      <span class="mktcard__lamp-dot" :class="`mktcard__lamp-dot--${status}`" aria-hidden="true" />
      <span class="mktcard__lamp-text">{{ statusText }}</span>
    </div>

    <!-- 可点主体:广场整卡,我的发布只点标题区 -->
    <button
      type="button"
      class="mktcard__open"
      @click="$emit('open', activity.id)"
    >
      <!-- 顶行:一级标签 chip ｜ 右对齐倒计时 -->
      <div class="mktcard__top">
        <span class="mktcard__cat">{{ activity.category }}</span>
        <span
          v-if="variant === 'plaza' && timerText"
          class="mktcard__timer"
          :class="{ 'mktcard__timer--warn': timerWarn }"
        >
          {{ timerText }}
        </span>
      </div>

      <!-- 主文案「分类 · 游戏」单行省略 + 内容预览单行 -->
      <p class="mktcard__title">{{ activity.mainText || activity.title }}</p>
      <p class="mktcard__preview">{{ activity.content }}</p>

      <!-- 信息行:地点 / 价格(0 免费)/ 期望人数(缺的隐藏) -->
      <div class="mktcard__info">
        <span v-if="activity.location" class="mktcard__meta">
          <el-icon :size="14"><Location /></el-icon>{{ activity.location }}
        </span>
        <span class="mktcard__meta">
          <el-icon :size="14"><Coin /></el-icon>{{ priceText }}
        </span>
        <span v-if="activity.targetNum" class="mktcard__meta">
          <el-icon :size="14"><User /></el-icon>{{ activity.targetNum }}人
        </span>
        <!-- 满员徽标(有则显示):广场行也可能满员 -->
        <span v-if="Number(activity.isFull) === 1" class="mktcard__full">已满</span>
      </div>
    </button>

    <!-- 我的发布:右侧操作按钮组(编辑 / 开关 / 满员 / 重激活 / 删除) -->
    <div v-if="variant === 'my'" class="mktcard__ops">
      <button type="button" class="mktcard__op" @click="$emit('edit', activity)">
        <el-icon :size="16"><EditPen /></el-icon><span>编辑</span>
      </button>

      <!-- 开关:投放中 → 暂停;暂停/过期(可再投放)→ 开启 -->
      <button
        type="button"
        class="mktcard__op"
        @click="$emit('toggle', activity)"
      >
        <el-icon :size="16"><VideoPause v-if="Number(activity.isEnable) === 1" /><VideoPlay v-else /></el-icon>
        <span>{{ Number(activity.isEnable) === 1 ? '暂停' : '开启' }}</span>
      </button>

      <!-- 满员标记开关 -->
      <button
        type="button"
        class="mktcard__op"
        @click="$emit('full', activity)"
      >
        <el-icon :size="16"><Medal /></el-icon>
        <span>{{ Number(activity.isFull) === 1 ? '取消满员' : '标满员' }}</span>
      </button>

      <!-- 重激活:仅 暂停/过期 时可见(需刷 expire_time) -->
      <button
        v-if="status === 'PAUSED' || status === 'EXPIRED'"
        type="button"
        class="mktcard__op mktcard__op--primary"
        @click="$emit('reactivate', activity)"
      >
        <el-icon :size="16"><RefreshRight /></el-icon><span>重新激活</span>
      </button>

      <button type="button" class="mktcard__op mktcard__op--danger" @click="$emit('delete', activity)">
        <el-icon :size="16"><Delete /></el-icon><span>删除</span>
      </button>
    </div>
  </div>
</template>

<script setup>
/**
 * MktCard.vue —— 集市活动卡片
 *
 * 数据来自 MktActivityVO:category/subTag/title/content/location/price/
 * targetNum/isEnable/isFull/expireTime/(my:status/nickname)。
 * 广场行无 status,my 行有服务端推导的 4 态 status(LAUNCHING/FULL/PAUSED/EXPIRED)。
 */
import { useRemainingCountdown } from '@/composables/useRemainingCountdown'
import {
  Location, Coin, User, EditPen, VideoPause, VideoPlay, Medal, RefreshRight, Delete,
} from '@element-plus/icons-vue'

const props = defineProps({
  activity: { type: Object, required: true },
  /** 'plaza' 纯展示 | 'my' 状态灯 + 操作组 */
  variant: { type: String, default: 'plaza' },
})

defineEmits(['open', 'edit', 'toggle', 'full', 'reactivate', 'delete'])

/* ---------------- 倒计时(1s,≤2h 橙红) ---------------- */
const { remainingText: timerText, warn: timerWarn } = useRemainingCountdown(
  () => props.activity.expireTime)

/* ---------------- 状态灯文案 ---------------- */
const STATUS_TEXT = { LAUNCHING: '投放中', FULL: '满员', PAUSED: '暂停', EXPIRED: '过期' }
const status = props.activity.status || 'LAUNCHING'
const statusText = STATUS_TEXT[status] || status

/* ---------------- 价格/人数文案 ---------------- */
/** 0 与空都显示「免费」;有值显示「¥x」 */
const priceText = (() => {
  const p = props.activity.price
  if (p == null || Number(p) === 0) return '免费'
  return `¥${Number(p)}`
})()
</script>

<style scoped src="./MktCard.css"></style>
