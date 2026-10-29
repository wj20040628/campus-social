<template>
  <!--
    个人主页封面（2026-10-20）：整屏图 + 本人可点换图。
    从 Profile.vue 拆出来是因为视图已贴着单文件 400 行门禁，
    而且封面这块自带注释与状态分支，留在视图里最容易被下一个改版者挤爆。

    2026-10-20 二次调整：封面上不再常驻任何按钮。
    原先左上角那个玻璃胶囊「恢复默认」直接压在用户自己挑的照片上，
    又抢眼又割裂（用户原话「悬浮在背景上很难看」）。改成
    「点封面 → 底部操作表」：没设过封面时仍然一步选图，设过的才多一步，
    封面上只留右上角「更多」一个元素。
  -->
  <div class="p-cover" :class="{ 'p-cover--custom': hasCover }" :style="bgStyle">
    <!-- 整块可点的热区。只在本人视角出现，访客点了不该有反应 -->
    <button
      v-if="isMe"
      type="button"
      class="p-cover__hit"
      :disabled="busy"
      :aria-label="hint"
      @click="onHit"
    >
      <span class="p-cover__hint">{{ hint }}</span>
    </button>

    <!--
      访客看封面（2026-10-28）：有自定义封面时可点开大图。
      不显示提示文案——访客的主线是看这个人的资料，不是编辑封面；
      挂一句「点击查看大图」会把封面变成公告栏。
    -->
    <button
      v-else-if="hasCover"
      type="button"
      class="p-cover__hit"
      aria-label="查看封面大图"
      @click="$emit('preview')"
    />

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

    <!--
      封面操作表。只在「已经有自定义封面」时才弹 —— 没设过封面的人点封面
      就该直接进相册，多弹一层纯属挡路。设过的人点封面多半是想换或想撤，
      这时给出两个明确选项，比在封面上钉一个按钮干净。
    -->
    <ActionSheet v-model="sheetOpen" title="主页封面">
      <button type="button" class="p-sheet__item" @click="preview">查看大图</button>
      <button type="button" class="p-sheet__item" @click="choose">更换封面</button>
      <button type="button" class="p-sheet__item" @click="reset">恢复默认封面</button>
    </ActionSheet>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'

const props = defineProps({
  /** 本人视角：渲染可点热区 */
  isMe: { type: Boolean, default: false },
  /** 是否已设置自定义封面（父级按 user.coverUrl 判） */
  hasCover: { type: Boolean, default: false },
  /** 内联背景样式（父级已拼好 background-image） */
  bgStyle: { type: Object, default: null },
  /** 上传中：热区置灰，防连点重复上传 */
  busy: { type: Boolean, default: false },
})

const emit = defineEmits(['pick', 'reset', 'more', 'preview'])

const sheetOpen = ref(false)

/**
 * 提示文案。三种状态各说一句「点了会发生什么」——
 * 用同一句「点击更换」会让还没设过封面的用户以为已经有一张了。
 */
const hint = computed(() => {
  if (props.busy) return '上传中…'
  return props.hasCover ? '更换或恢复封面' : '点击设置封面'
})

/** 没设过封面：直接进相册；设过的：先问是换还是撤 */
function onHit() {
  if (props.hasCover) sheetOpen.value = true
  else emit('pick')
}

/** 先关面板再抛事件：面板不关会挡住系统文件选择器退出后的回跳 */
function choose() {
  sheetOpen.value = false
  emit('pick')
}

function reset() {
  sheetOpen.value = false
  emit('reset')
}

/** 先关面板再抛事件：面板不关会盖住全屏看图 */
function preview() {
  sheetOpen.value = false
  emit('preview')
}
</script>

<style scoped src="./ProfileCover.css"></style>
