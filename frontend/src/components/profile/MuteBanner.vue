<template>
  <!--
    账号禁言提示（2026-10-31）。只在本人主页出现：
    禁言是账号状态，不是公开信息；他人主页不该看到。
    数据源 userStore.userInfo.muteUntil（/user/me 下发），到期后自动不再渲染。
  -->
  <div v-if="muted" class="mute-banner" role="status">
    <div class="mute-banner__body">
      <p class="mute-banner__title">账号禁言中 · 至 {{ untilText }}</p>
      <p class="mute-banner__text">
        期间可以浏览，但不能发帖、评论、私信和发送群消息；到期自动解除。
      </p>
    </div>
    <button type="button" class="mute-banner__btn" @click="openFeedback">申诉</button>
  </div>
  <FeedbackDialog ref="feedbackRef" />
</template>

<script setup>
import { computed, ref } from 'vue'
import dayjs from 'dayjs'
import FeedbackDialog from '@/views/settings/FeedbackDialog.vue'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  /** 只在本人主页渲染：muteUntil 来自 /user/me，是「我」的状态 */
  isMe: { type: Boolean, default: false },
})

const userStore = useUserStore()
const feedbackRef = ref(null)

const until = computed(() => userStore.userInfo?.muteUntil || null)
const muted = computed(() => !!props.isMe && !!until.value && dayjs(until.value).isAfter(dayjs()))
const untilText = computed(() => (until.value ? dayjs(until.value).format('YYYY-MM-DD HH:mm') : ''))

function openFeedback() {
  feedbackRef.value?.open()
}
</script>

<style scoped>
.mute-banner {
  margin: var(--ys-space-3) var(--ys-space-4);
  padding: var(--ys-space-3);
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  border: 1px solid var(--ys-accent-2);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface);
}

.mute-banner__body {
  flex: 1;
  min-width: 0;
}

.mute-banner__title {
  margin: 0;
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-accent-2);
}

.mute-banner__text {
  margin: 2px 0 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.mute-banner__btn {
  flex: none;
  min-height: 32px;
  padding: 0 var(--ys-space-4);
  border: 1px solid var(--ys-accent-2);
  border-radius: 999px;
  background: transparent;
  color: var(--ys-accent-2);
  font-size: var(--ys-text-sm);
  cursor: pointer;
}
</style>
