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
  <span class="ia-wrap" :style="{ width: size + 'px', height: size + 'px' }">
    <el-avatar v-if="src && !broken" :size="size" :src="src" class="ia" @error="broken = true" />
    <span v-else class="ia ia--ini" :style="iniStyle" aria-hidden="true">{{ initial }}</span>
    <!-- 在线点：绿=在线，灰=离线/隐身。仅传了真实 userId 的头像才显示 -->
    <span
      v-if="hasPresence"
      class="ia-dot"
      :class="{ 'ia-dot--on': online }"
      :style="dotStyle"
      role="img"
      :aria-label="online ? '在线' : '离线'"
    />
  </span>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ensurePresence, isOnline } from '@/utils/presence'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  /** 头像地址。空串 = 没有图,走首字母圆 */
  src: { type: String, default: '' },
  /** 哈希种子:传作者 id,同一个人的颜色才稳定(昵称会改,id 不会) */
  seed: { type: [String, Number], default: '' },
  /** 昵称,取首字。脱敏昵称(匿**)也只用第一个字,不泄露更多 */
  name: { type: String, default: '' },
  size: { type: Number, default: 40 },
  /** 真实用户 id；传了才查在线状态并画绿点（匿名/圈子/群不传） */
  userId: { type: [String, Number], default: null },
  /** 某些场景不想显示在线点（如匿名头像）可显式关掉 */
  showPresence: { type: Boolean, default: true },
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

/** 游客不画点：未登录查不到在线状态，全灰点等于误导 */
const userStore = useUserStore()

const hasPresence = computed(
  () =>
    props.showPresence &&
    props.userId != null &&
    String(props.userId) !== '' &&
    !!userStore.accessToken,
)
const online = computed(() => hasPresence.value && isOnline(props.userId))
const dotStyle = computed(() => {
  const d = props.size >= 36 ? 8 : 6
  return { width: d + 'px', height: d + 'px' }
})

onMounted(() => {
  if (hasPresence.value) ensurePresence(props.userId)
})
watch(
  () => props.userId,
  (id) => {
    if (hasPresence.value) ensurePresence(id)
  },
)

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
.ia-wrap {
  position: relative;
  display: inline-flex;
  flex: none;
  border-radius: var(--ys-radius-full);
}
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
/* 点位压在头像右下边缘，2px 底色描边把它从头像上「抠」出来 */
.ia-dot {
  position: absolute;
  right: 0;
  bottom: 0;
  border-radius: var(--ys-radius-full);
  background: var(--ys-meta);
  border: 1.5px solid var(--ys-surface);
  box-sizing: content-box;
  pointer-events: none;
}
.ia-dot--on {
  background: #22c55e;
}
</style>
