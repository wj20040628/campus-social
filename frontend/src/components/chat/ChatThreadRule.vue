<template>
  <!--
    会话内规则行:放在消息流最前面,即第一条消息之前。
    只对单聊出现(群聊不受陌生人额度约束)。

    ## 规则自 2026-10-28 起只剩一条
    陌生人之间**对同一个人**每天 N 条(N 来自 /chat/stranger-quota 下发的
    perPeerDailyLimit,默认 1),不再有「每天 3 个新对象」。
    解除条件只有对方那边主动:对方关注我 / 对方回复过我 / 互关。
    **我关注对方不解锁** —— 关注是单向动作,不能替对方表达意愿。

    `remaining` 来自 /chat/stranger-quota?peerId=,null 表示没拉到 ——
    此时只说规则、不报剩余数,不要拿 0 顶替(「不知道」≠「用完了」)。
  -->
  <p v-if="limit" class="th__rule">
    <span class="th__rule__main">陌生人之间，一天最多给同一个人发 <span class="ys-num">{{ limit }}</span> 条</span>
    <span v-if="remaining > 0" class="th__rule__left">今天还剩 <span class="ys-num">{{ remaining }}</span> 条</span>
    <span v-else-if="remaining === 0" class="th__rule__left">今天这条已经发了</span>
    <span class="th__rule__sub">对方关注你、或回复过你之后，这条限制就解除了</span>
  </p>
</template>

<script setup>
defineProps({
  /** 对同一个人每天最多几条;null/0 = 不显示规则行 */
  limit: { type: Number, default: null },
  /** 对当前这个人今天还剩几条;null = 没拉到,不显示剩余数 */
  remaining: { type: Number, default: null },
})
</script>

<style scoped>
/*
 * 三行定长,不让浏览器自动折行 —— 390px 下把「今天这条已经发了」和规则挤在
 * 同一行时,末尾的「了」会被挤到第二行单独成行（实测截图）。规则、剩余、
 * 解除条件各占一行,每行都短到 320px 也放得下。
 */
.th__rule {
  display: flex;
  flex-direction: column;
  gap: 1px;
  width: fit-content;
  max-width: 100%;
  margin: 0 auto var(--ys-space-3);
  padding: 6px var(--ys-space-4);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-fg-2);
  text-align: center;
}

/* 剩余条数比规则本身更常被看,给一点重量但不喧宾夺主 */
.th__rule__left {
  color: var(--ys-accent);
}

.th__rule__sub {
  color: var(--ys-muted);
}
</style>
