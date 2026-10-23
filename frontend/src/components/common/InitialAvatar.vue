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
  <!--
    G10(2026-10-21):图片取不到时要有首字回落。
    el-avatar 在图片 404 时只渲染一个空圆,不会退回首字 —— 群成员表第一行
    出现过一个空白灰圆(账号其实有头像,只是文件没了)。
    接 @error 把本地 broken 置真、src 置空,让它走 v-else 的首字分支。
    放在这里而不是每个调用点:任何列表都可能出现坏头像,不该由调用方逐个处理。
  -->
  <el-avatar v-if="src && !broken" :size="size" :src="src" class="ia" @error="broken = true" />
  <span v-else class="ia ia--ini" :style="iniStyle" aria-hidden="true">{{ initial }}</span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

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

/**
 * 图片加载失败标记。src 换了要复位 —— 同一个组件复用在不同用户之间时
 * (列表 v-for、切换会话),不复位会让第二个人也顶着第一个人的首字圆。
 */
const broken = ref(false)
watch(
  () => props.src,
  () => {
    broken.value = false
  },
)

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
