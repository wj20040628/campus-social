<template>
  <!--
    自绘图标:认证盾牌 verified-shield(icons-req.md §3.2)

    为什么必须自绘:这是本产品与校园墙的核心分界(PRD F2),用户判断
    「这人是不是本校的」完全依赖这个符号。EP 只有 Lock(锁=私密)、
    Medal/GoldMedal(奖章=荣誉),语义都不对。

    规格:viewBox 0 0 1024 1024 + fill="currentColor",与 EP 图标同风格。
    内部勾形用**反向镂空**(evenodd 的内子路径),不另加颜色。
    颜色:已认证 --ys-accent / 未认证 --ys-border 灰化。
         **未认证不用红或警告色** —— 未认证不是错误状态。

    无障碍:纯图标对读屏不友好,必须与文字标签「已认证 · XX 大学」同时出现
    (AC-12 / icons-req.md §3.2 配套条款)。故本组件 aria-hidden,
    文字由调用方提供。
  -->
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 1024 1024"
    fill-rule="evenodd"
    aria-hidden="true"
    focusable="false"
    class="ys-icon"
  >
    <path d="M512 64 L896 192 L896 480 C896 700 730 880 512 960 C294 880 128 700 128 480 L128 192 Z" />
    <!-- 勾形:反向镂空 -->
    <path d="M448 604 L356 512 L404 464 L448 508 L620 336 L668 384 Z" />
  </svg>
</template>

<script setup>
defineProps({
  /** 尺寸档位。16px(认证标识旁)/ 24px(个人主页大标识) */
  size: { type: Number, default: 16 },
})
</script>

<style scoped>
.ys-icon {
  display: inline-block;
  flex: none;
  vertical-align: -0.125em;
}
</style>