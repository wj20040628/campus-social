<template>
  <!--
    自绘图标:点赞 thumbs-up(icons-req.md §3.1)

    为什么必须自绘:EP 库无 thumbs-up;`Star` 已被定义为「收藏」,
    拿Star 当点赞会让用户误解,且后续做收藏功能时无处安放(icons-req.md §5 第 5 条)。

    风格对齐:EP 图标实测为**填充型** —— viewBox 0 0 1024 1024 + fill="currentColor"。
    本图标因此不用 stroke 描边,而是:
      filled 态 = 外轮廓实心
      outline 态 = 外轮廓 + 内轮廓,靠 fill-rule="evenodd" 挖空成环
    这样两态都是「填充型」,与 EP 其余图标笔触一致,不会出现同一页面两种质感。

    两态用同一几何、切换 path 变体,不换图标 —— 保证选中前后视觉锚点不跳变。
    颜色一律 currentColor 继承(禁止给 SVG 单独填色):
      未赞 --ys-muted / 已赞 --ys-accent(由调用方决定)
  -->
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 1024 1024"
    :fill-rule="filled ? 'nonzero' : 'evenodd'"
    aria-hidden="true"
    focusable="false"
    class="ys-icon"
  >
    <path :d="d" />
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 尺寸档位。只允许 16 / 20 / 24(icons-req.md §1,不允许出现第四档) */
  size: { type: Number, default: 16 },
  /** true = 已赞(实心);false = 未赞(轮廓) */
  filled: { type: Boolean, default: false },
})

/* 外轮廓:左手边拇指上翘,右侧掌身,1024 视口内留 72 左右安全边 */
const OUTER =
  'M176 452 L296 452 L296 344 C296 236 384 148 492 148 ' +
  'C566 148 622 206 622 280 L622 452 L862 452 ' +
  'C908 452 944 488 944 534 L944 792 ' +
  'C944 838 908 874 862 874 L176 874 Z'

/* 内轮廓:外轮廓向内等距偏移约 56,配合 evenodd 挖空成「轮廓」 */
const INNER =
  'M232 508 L352 508 L352 344 C352 268 414 204 492 204 ' +
  'C540 204 566 242 566 280 L566 508 L862 508 ' +
  'C878 508 888 520 888 536 L888 790 ' +
  'C888 806 878 818 862 818 L232 818 Z'

const d = computed(() => (props.filled ? OUTER : `${OUTER} ${INNER}`))
</script>

<style scoped>
.ys-icon {
  display: inline-block;
  flex: none;
  /* 16/20 档用基线对齐,避免图标与文字错位 */
  vertical-align: -0.125em;
}
</style>