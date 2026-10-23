<template>
  <!--
    桌面端消息右键菜单(2026-10-15 新增,只在 hover:hover 设备出现)。
    Teleport 到 body:菜单定位基于 viewport(clientX/Y),且 .th__list 会 overflow 裁剪,
    挂在 ChatThread 内部会被切掉。作用域样式不受 teleport 影响(style 作用域在组件层)。
  -->
  <Teleport to="body">
    <div
      v-if="modelValue && isHoverDevice"
      class="ctx"
      :style="menuPos"
      role="menu"
      @click.stop
    >
      <button
        v-if="canCopy"
        type="button"
        role="menuitem"
        class="ctx__item"
        @click="pick('copy')"
      >
        <el-icon :size="16"><CopyDocument /></el-icon>
        复制
      </button>
      <button
        v-if="canQuote"
        type="button"
        role="menuitem"
        class="ctx__item"
        @click="pick('quote')"
      >
        <el-icon :size="16"><Paperclip /></el-icon>
        引用
      </button>
      <button
        v-if="canRecall"
        type="button"
        role="menuitem"
        class="ctx__item ctx__item--danger"
        @click="pick('recall')"
      >
        <el-icon :size="16"><RefreshLeft /></el-icon>
        撤回
      </button>
      <!--
        举报（2026-10-21 新增）。
        后端 targetType=4 早就支持（软删隐藏），但菜单此前只有复制/引用/撤回 —
        撤回只管自己的消息，别人发来的骚扰私信无处举报。
      -->
      <button
        v-if="canReport"
        type="button"
        role="menuitem"
        class="ctx__item ctx__item--danger"
        @click="pick('report')"
      >
        <el-icon :size="16"><Warning /></el-icon>
        举报这条消息
      </button>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * 桌面端消息右键菜单。
 *
 * 与移动端长按 ActionSheet(ChatMsgSheet)的职责划分:
 *   - 本菜单只在 `@media (hover:hover)` 设备(桌面鼠标)挂载,
 *     由父组件在 capture 了 `contextmenu` 事件且指针是鼠标时打开。
 *   - 移动端没有右键概念,长按走 ChatMsgSheet(已精简为撤回/引用)。
 *   - 本菜单比 ActionSheet 更轻:没有标题,没有底部抽屉动画,
 *     就是浮在光标旁的三行,项目暖米色背景,与气泡配色一致。
 *
 * 关闭方式:父组件在 `onOpenChange`(document 点击捕获、Esc、
 *   滚动、右键别的消息)里把 modelValue 置 false。
 */
import { computed } from 'vue'
import { CopyDocument, Paperclip, RefreshLeft, Warning } from '@element-plus/icons-vue'

const props = defineProps({
  /** 开合(v-model) */
  modelValue: { type: Boolean, default: false },
  /** 菜单左上角视口坐标 {x, y},由父组件 capture contextmenu 计算 */
  pos: { type: Object, default: () => ({ x: 0, y: 0 }) },
  /** 能否撤回:本菜单的撤回只在「我发的 + 2 分钟窗口内 + 有 msgId」时渲染,
   *   由父组件用 canRecall(msg) 算好传下来,本菜单不判业务规则。 */
  canRecall: { type: Boolean, default: false },
  /** 能否复制:消息无 content(如纯撤回态)时父组件关掉 */
  canCopy: { type: Boolean, default: true },
  /** 能否引用:撤回/失败态的消息不能引用 */
  canQuote: { type: Boolean, default: true },
  /*
   * 能否举报（2026-10-21 新增）。
   * 只对对方发来的、未撤回的消息为真 —— 判定口径与 canQuote 一致，
   * 由父组件算好传下来，本菜单不碰业务规则。
   */
  canReport: { type: Boolean, default: false },
})

const emit = defineEmits([
  'update:modelValue',
  'copy',
  'quote',
  'recall',
  'report',
])

/**
 * 桌面右键菜单只在 hover 设备出现:touch 设备(手机 / 平板 / 混合)
 * 的鼠标右键是浏览器默认「复制链接/保存」,我们不该拦截。
 * 用 matchMedia 判一次即可——用户从手机切到桌面(窗口 resize)
 * 重新挂载 ChatThread 时 matchMedia 值也会更新。
 */
const isHoverDevice = computed(() =>
  typeof window !== 'undefined' &&
  window.matchMedia &&
  window.matchMedia('(hover:hover)').matches
)

/** 菜单宽度上限,让翻向计算有个基准(实际宽度由内容撑开) */
const MENU_W = 160
const MENU_H = 110

/**
 * 视口边缘翻向:右/底放得下就放右/下,放不下就翻到左/上。
 * 父组件只在 open 的瞬间算一次位置,滚动/resize 时整个菜单会被关掉,
 * 所以这里用 clientX/Y(clientX 是视口坐标)就够了。
 */
const menuPos = computed(() => {
  const vw = window.innerWidth
  const vh = window.innerHeight
  let x = props.pos.x
  let y = props.pos.y
  if (x + MENU_W > vw - 8) x = Math.max(8, x - MENU_W - 8)
  if (y + MENU_H > vh - 8) y = Math.max(8, y - MENU_H - 8)
  return { left: `${x}px`, top: `${y}px` }
})

/** 动作一律先关层再上报:父组件不必在每个 handler 里重复写关闭 */
function pick(kind) {
  emit('update:modelValue', false)
  emit(kind)
}
</script>

<style scoped>
/* 菜单:项目暖米底 + 圆角 + 柔和阴影(与气泡/卡片同一套 design token),
   不是浏览器原生 contextmenu 那种系统灰。z-index 压过 EP 弹层(2000+)。 */
.ctx {
  position: fixed;
  z-index: 3000;
  min-width: 140px;
  padding: var(--ys-space-1);
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.14);
  font-family: var(--ys-font-body);
}

.ctx__item {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  width: 100%;
  min-height: 40px;
  padding: 0 var(--ys-space-3);
  border: none;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: var(--ys-fg);
  font-size: var(--ys-text-base);
  text-align: left;
  cursor: pointer;
  transition: background var(--ys-motion-fast) var(--ys-ease-out);
}

.ctx__item:hover {
  background: var(--ys-surface-2);
}

.ctx__item--danger {
  color: var(--ys-accent-2);
}
</style>
