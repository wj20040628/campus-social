<template>
  <!--
    统一头像(A 方向,2026-10-15):有图用图,没图用「彩色首字母圆」。

    存在的原因:PostCard / PostBody / CommentRow / CommentComposer 四处原来
    各写各的 el-avatar,没图时 EP 默认渲染一个灰蓝色图标,和暖色体系冲突;
    且「暖六色 + 作者 id 哈希」的配色规则必须全站一致 —— 复制四份必然漂移。
    抽成组件,规则只有这一处。

    点击不在这里做:有的宿主要「点头像进主页」,有的(评论区)整行可点,
    由调用方自己包一层处理,组件保持哑巴。
  -->
  <el-avatar v-if="src" :size="size" :src="src" class="ia" />
  <span v-else class="ia ia--ini" :style="iniStyle" aria-hidden="true">{{ initial }}</span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 头像地址。空串 = 没有图,走首字母圆 */
  src: { type: String, default: '' },
  /** 哈希种子:传作者 id,同一个人的颜色才稳定(昵称会改,id 不会) */
  seed: { type: [String, Number], default: '' },
  /** 昵称,取首字。脱敏昵称(匿**)也只用第一个字,不泄露更多 */
  name: { type: String, default: '' },
  size: { type: Number, default: 40 },
})

/** 暖色系六色。浅底 + 暖墨字,对比度全部 >= 4.5(字色 --ys-fg) */
const AVA_COLORS = ['#F6D9C9', '#D8E8E0', '#EFE0C8', '#F2D8CC', '#E9E4CB', '#DCE6E0']

const initial = computed(() => props.name.trim().charAt(0) || '友')

const iniStyle = computed(() => {
  const s = String(props.seed ?? '')
  let h = 0
  for (const ch of s) h = (h * 31 + ch.codePointAt(0)) % 997
  return {
    width: props.size + 'px',
    height: props.size + 'px',
    background: AVA_COLORS[h % AVA_COLORS.length],
    fontSize: Math.round(props.size * 0.38) + 'px',
  }
})
</script>

<style scoped>
.ia { flex: none; }
.ia--ini {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--ys-radius-full);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  line-height: 1;
}
</style>
