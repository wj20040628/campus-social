<template>
  <!--
    个人主页封面（2026-10-20）：整屏图 + 本人可点换图 + 恢复默认出口。
    从 Profile.vue 拆出来是因为视图已贴着单文件 400 行门禁，
    而且封面这块自带四段注释与三个状态分支，留在视图里最容易被下一个改版者挤爆。
  -->
  <div class="p-cover" :class="{ 'p-cover--custom': hasCover }" :style="bgStyle">
    <!-- 整块可点的热区。只在本人视角出现，访客点了不该有反应 -->
    <button
      v-if="isMe"
      type="button"
      class="p-cover__hit"
      :disabled="busy"
      :aria-label="hint"
      @click="$emit('pick')"
    >
      <span class="p-cover__hint">{{ hint }}</span>
    </button>

    <!--
      恢复默认。只在自己设过封面时出现 —— 默认封面没有「恢复」可言，
      常驻一个按不动的按钮纯属噪音。
    -->
    <button
      v-if="isMe && hasCover"
      type="button"
      class="p-cover__reset"
      :disabled="busy"
      @click.stop="$emit('reset')"
    >恢复默认</button>

    <!--
      右上角「更多」(打开全局导航抽屉)。做成本组件内的按钮而不是父级塞进来的插槽:
      scoped 样式下的插槽内容带的是**父级**的 scope id，.p-more 的定位规则
      留在本文件里会匹配不到，表现为按钮跑到左上角、失去玻璃拟态。
    -->
    <button type="button" class="p-more" aria-label="更多" @click="$emit('more')">
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
        stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true">
        <path d="M4 7h16M4 12h16M4 17h16" />
      </svg>
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 本人视角：渲染可点热区与恢复默认 */
  isMe: { type: Boolean, default: false },
  /** 是否已设置自定义封面（父级按 user.coverUrl 判） */
  hasCover: { type: Boolean, default: false },
  /** 内联背景样式（父级已拼好 background-image） */
  bgStyle: { type: Object, default: null },
  /** 上传中：热区与恢复按钮一起置灰，防连点重复上传 */
  busy: { type: Boolean, default: false },
})

defineEmits(['pick', 'reset', 'more'])

/**
 * 提示文案。三种状态各说一句「点了会发生什么」——
 * 用同一句「点击更换」会让还没设过封面的用户以为已经有一张了。
 */
const hint = computed(() => {
  if (props.busy) return '上传中…'
  return props.hasCover ? '点击更换封面' : '点击设置封面'
})
</script>

<style scoped src="./ProfileCover.css"></style>
