<template>
  <ActionSheet v-model="visible" title="需要先完成校内认证" cancel-text="先逛逛">
    <div class="ag">
      <p class="ag__why">认证后可发帖、评论、私信同学、加入圈子、发布集市活动。</p>
      <p class="ag__how">只需要学号和学校归属，不收集姓名。</p>
      <button type="button" class="ag__btn" @click="goVerify">去认证</button>
    </div>
  </ActionSheet>
</template>

<script setup>
/**
 * 全站认证弹层。
 *
 * 触发路径有两条:
 * 1. request.js 收到业务码 1029(后端护栏),直接打开本弹层;
 * 2. 页面在动作前主动判断未认证,调 openAuthGate()。
 * 两条都落到同一个 visible,不会叠出两个弹层。
 */
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import { useAuthGate } from '@/composables/useAuthGate'
import { useVerifyStatus } from '@/composables/useVerifyStatus'

const router = useRouter()
const { visible, closeAuthGate } = useAuthGate()
const { profileComplete, code: verifyCode, load: loadVerifyStatus } = useVerifyStatus()

/*
 * 出口按「学校归属填没填」分流（2026-10-28）。
 * 归属已填全的人再被丢进整套引导页，会被迫从头走一遍学校选择 ——
 * 他已经填过了，真正缺的只是学号，应该直达编辑资料页的学号框。
 * 这与 Chat.vue 的 verifyEntry 是同一条口径，两处别各写一套。
 *
 * `verifyCode === null` 表示状态还没拉到：此时 profileComplete 恒为 false，
 * 不分流就会把老用户也送去引导页。补拉一次，拿不到再退回引导页（保守但可用）。
 */
onMounted(() => {
  if (verifyCode.value === null) loadVerifyStatus()
})

const verifyPath = computed(() => (profileComplete.value ? '/profile' : '/onboarding'))

function goVerify() {
  closeAuthGate()
  router.push(verifyPath.value)
}
</script>

<style scoped>
.ag {
  padding: var(--ys-space-4);
}

.ag__why,
.ag__how {
  margin: 0;
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-base);
  color: var(--ys-fg-2);
}

.ag__how {
  margin-top: var(--ys-space-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}

.ag__btn {
  width: 100%;
  min-height: var(--ys-touch-min);
  margin-top: var(--ys-space-5);
  border: 0;
  border-radius: var(--ys-radius-lg);
  background: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-accent-on);
  cursor: pointer;
}

.ag__btn:active {
  opacity: 0.8;
}
</style>
