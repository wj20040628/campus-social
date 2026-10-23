<template>
  <!--
    桌面端会话行右键菜单(2026-10-20)。
    Teleport 到 body:.cl 有 overflow 裁剪,菜单挂列表内部会被切掉;
    定位用 clientX/clientY(视口坐标),所以必须是 fixed。
  -->
  <Teleport to="body">
    <div v-if="modelValue" class="rowmenu" :style="menuPos" role="menu" @click.stop>
      <button type="button" role="menuitem" class="rowmenu__item" @click="pick('mute')">
        <NavIcon :name="muted ? 'bell' : 'bell-off'" :size="16" />
        {{ muted ? '取消免打扰' : '免打扰' }}
      </button>
      <button type="button" role="menuitem" class="rowmenu__item" @click="pick('pin')">
        <NavIcon name="pin" :size="16" />
        {{ pinned ? '取消置顶' : '置顶' }}
      </button>
      <button type="button" role="menuitem" class="rowmenu__item rowmenu__item--danger" @click="pick('delete')">
        <NavIcon name="trash" :size="16" />
        删除
      </button>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * 桌面端「右键会话行」菜单。
 *
 * 为什么桌面需要它:左滑手势只在触屏生效(鼠标拖拽会和文字选择打架),
 * 所以改版后桌面端根本没有置顶/免打扰的入口 —— 用户提出补一个右键菜单。
 * 三个动作与手机左滑的三个按钮**完全同源**:都走 ChatConvList 的 onAction
 * → useChatCore.setPref,不另写一套逻辑。
 *
 * 关闭时机由父组件负责(document 捕获点击 / Esc / 滚动 / 右键另一行),
 * 本组件只管渲染与上报,免得两处各记一份状态。
 */
import { computed } from 'vue'
import NavIcon from '@/components/mobile/NavIcon.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 菜单左上角视口坐标 */
  pos: { type: Object, default: () => ({ x: 0, y: 0 }) },
  pinned: { type: Boolean, default: false },
  muted: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'mute', 'pin', 'delete'])

/** 估计值:只为边缘翻向服务,实际宽高由内容撑开 */
const MENU_W = 168
const MENU_H = 132

/**
 * 视口边缘翻向。右/下放不下就翻到左/上 —— 贴着屏幕右缘的会话行
 * 右键时,菜单如果不翻会有一半在屏幕外。
 */
const menuPos = computed(() => {
  const vw = window.innerWidth
  const vh = window.innerHeight
  let x = props.pos.x
  let y = props.pos.y
  if (x + MENU_W > vw - 8) x = Math.max(8, x - MENU_W)
  if (y + MENU_H > vh - 8) y = Math.max(8, y - MENU_H)
  return { left: `${x}px`, top: `${y}px` }
})

/** 先关层再上报:父组件不必每个 handler 重复写关闭 */
function pick(kind) {
  emit('update:modelValue', false)
  emit(kind)
}
</script>

<style scoped>
.rowmenu {
  position: fixed;
  z-index: 3000;
  min-width: 152px;
  padding: var(--ys-space-1);
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface);
  box-shadow: var(--ys-shadow-3, 0 8px 24px rgb(0 0 0 / 14%));
  font-family: var(--ys-font-body);
}

.rowmenu__item {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  width: 100%;
  min-height: 38px;
  padding: 0 var(--ys-space-3);
  border: none;
  border-radius: var(--ys-radius-sm);
  background: transparent;
  color: var(--ys-fg);
  font-family: inherit;
  font-size: var(--ys-text-base);
  text-align: left;
  cursor: pointer;
  transition: background var(--ys-motion-fast) var(--ys-ease-out);
}

.rowmenu__item:hover {
  background: var(--ys-surface-2);
}

/* 删除用 accent-2(暖橙红)而不是 danger:与气泡右键菜单的「撤回」同一档强调 */
.rowmenu__item--danger {
  color: var(--ys-accent-2);
}
</style>
