<template>
  <!--
    列表加载骨架屏。
    硬要求(SPEC 逐页设计 §4):骨架形状必须对齐真实条目,
    否则加载完成那一刻产生位移,直接打爆 AC-17 的 LCP/CLS 预算。
    所以默认 variant="post"(44px 圆 + 两行文字条,与 PostCard 同形态),
    而不是一个通用 spinner 或形状不一的卡片。
  -->
  <ul class="skeleton" :class="`skeleton--${variant}`" aria-hidden="true">
    <li v-for="n in count" :key="n" class="skeleton__row">
      <span v-if="variant !== 'row'" class="skeleton__avatar" />
      <div class="skeleton__lines">
        <span class="skeleton__line" :style="{ width: lineWidths[n % lineWidths.length] }" />
        <span v-if="variant !== 'row'" class="skeleton__line skeleton__line--short" />
        <!-- audit 变体是三行(昵称+标签 / detail / 时间),第三行更短 -->
        <span v-if="variant === 'audit'" class="skeleton__line skeleton__line--tiny" />
      </div>
    </li>
  </ul>
</template>

<script setup>
/**
 * @param {number} count  骨架条数,3-6 条为宜(SPEC 要求 3-6)
 * @param {'post'|'row'|'panel'|'audit'} variant
 *        post  = 头像 + 两行(Feed/公告列表)
 *        row   = 单行(会话列表)
 *        panel = 无头像,纯文字条(表单/详情)
 *        audit = 头像 + 三行,第三行更短(操作日志条目)
 */
defineProps({
  count: { type: Number, default: 4 },
  variant: { type: String, default: 'post' },
})

// 宽度参差,避免整齐得像栅格 —— 真实文本行宽本来就是参差的
const lineWidths = ['92%', '78%', '88%', '64%']
</script>

<style scoped>
/*
 * 骨架条目间用 1px 分隔线而不是卡片盒子,
 * 与真实 Feed 的「无卡片 + 1px 线」形态保持一致。
 */
.skeleton {
  margin: 0;
  padding: 0;
  list-style: none;
}

.skeleton__row {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-3);
  padding: var(--ys-space-4);
}

.skeleton__row + .skeleton__row {
  border-top: 1px solid var(--ys-border-soft);
}

.skeleton__avatar {
  flex: none;
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  border-radius: var(--ys-radius-full);
}

.skeleton__lines {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.skeleton__line {
  display: block;
  height: 12px;
  border-radius: var(--ys-radius-sm);
}

.skeleton__line--short {
  width: 40%;
}

.skeleton--row .skeleton__row {
  padding: var(--ys-space-3) var(--ys-space-4);
}

.skeleton--panel .skeleton__row {
  display: block;
}

/*
  audit 变体:操作日志条目是 34px 小头像 + 三行文字,
  骨架必须一比一对齐 —— 换成两行会在加载完成那一刻整体上移一行。
*/
.skeleton--audit .skeleton__avatar {
  width: 34px;
  height: 34px;
}

.skeleton--audit .skeleton__row {
  padding: var(--ys-space-4) var(--ys-gutter-phone);
}

.skeleton__line--tiny {
  width: 24%;
}

/*
 * 微光:--ys-ease-linear。
 * 骨架是加载指示,不是装饰动画,故用 linear 而非 out。
 */
.skeleton__avatar,
.skeleton__line {
  background: linear-gradient(
    90deg,
    var(--ys-surface-2) 25%,
    var(--ys-border-soft) 37%,
    var(--ys-surface-2) 63%
  );
  background-size: 400% 100%;
  animation: ys-skeleton-sheen 1.4s var(--ys-ease-linear) infinite;
}

@keyframes ys-skeleton-sheen {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .skeleton__avatar,
  .skeleton__line {
    animation: none;
  }
}
</style>