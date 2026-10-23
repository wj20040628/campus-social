<template>
  <!-- 单条气泡。v-for 与分组留在 ChatThread:这里只负责「一条」的渲染 -->
  <li
    class="brow"
    :class="{ 'brow--me': msg.fromMe, 'brow--first': first, 'brow--last': last }"
  >
    <div
      class="bbubble"
      :class="{
        'bbubble--failed': msg.failed,
        'bbubble--long': isLong(msg),
        'bbubble--open': expanded,
      }"
      :aria-label="msg.fromMe ? '我发送的消息' : undefined"
      @pointerdown="onPointerDown($event, msg)"
      @pointerup="clearPress"
      @pointercancel="clearPress"
      @pointerleave="clearPress"
      @contextmenu="onContextMenu($event, msg)"
    >
      <div v-if="msg.replyToContent" class="quote">
        <span class="quote__from">{{ msg.replyToNickname || '对方' }}:</span>
        <span class="quote__text">{{ msg.replyToContent }}</span>
      </div>

      <p class="bbubble__text">{{ msg.content }}</p>

      <!-- 超长单条(>500 字)最多 8 行 + 展开全文(§3 内容超长) -->
      <button v-if="isLong(msg) && !expanded" type="button" class="bbubble__more" @click="expanded = true">
        展开全文
      </button>

      <!--
        气泡内不再显示时间与已读状态(2026-10-20 群聊改版)。
        时间上移到「组头」由 ChatThread 统一渲染,已读只在整组末尾出现一次 ——
        原来每条气泡右下角都挂「10-11 未读」,一天重复六遍日期,而且逐条
        告诉用户「没人理你」,与「不回应必须是零成本」的价值观相反。
        只在发送失败时保留一行状态。
      -->
      <div v-if="msg.failed" class="bbubble__foot">
        <span class="bbubble__state">未发出</span>
        <button
          type="button"
          class="bbubble__retry"
          @click.stop="emit('retry', msg)"
        >
          点这里重试
        </button>
      </div>
    </div>
  </li>
</template>

<script setup>
/**
 * 单条消息气泡(2026-10-15 从 ChatThread.vue 拆出)。
 *
 * 拆出的理由:ChatThread.vue 436 行超 300 行门禁;且气泡是独立关注点 ——
 * 它自带两份只属于自己的状态(是否展开全文、长按计时器),放在父组件里
 * 就得用 Set<msgId> 在父层维护,多一条消息多一处状态。
 *
 * ## 2026-10-20 改动
 * 1) 撤回不再渲染成气泡:父组件把撤回消息抽成居中的系统行,本组件只处理真实消息。
 * 2) 时间/已读从气泡里移走,改由父组件按「组」渲染(见 ChatThread 的 blocks)。
 * 3) 新增 first/last:同一个人连发的第一条与最后一条圆角不同,中间几条收成 12px,
 *    视觉上才是一「串」而不是六个孤立气泡。
 *
 * 长按阈值 500ms:短于此会和点击/滚动冲突,长于此用户会以为没响应。
 * 用 pointer 事件而不是 touch —— 前者桌面也触发,开发时不用切手机验证。
 * pointercancel/pointerleave 必须清定时器:手指滑走若不清,菜单 500ms 后会莫名弹出。
 */
import { ref, onBeforeUnmount } from 'vue'

defineProps({
  msg: { type: Object, required: true },
  /** 是否为所属分组的第一条 */
  first: { type: Boolean, default: true },
  /** 是否为所属分组的最后一条 */
  last: { type: Boolean, default: true },
})

const emit = defineEmits(['long-press', 'retry', 'context-menu'])

/** 展开全文:每条气泡自己记,不回父组件 */
const expanded = ref(false)
const pressTimer = ref(null)

/** 超长阈值 500 字(§3):超过后正文最多 8 行 */
function isLong(m) {
  return (m.content?.length || 0) > 500
}

function onPointerDown(e, m) {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  pressTimer.value = setTimeout(() => emit('long-press', m), 500)
}

/**
 * 桌面右键:气泡上按下鼠标右键 → 透传给父组件开上下文菜单。
 * - 只有鼠标右键(button===2)且 hover 设备才弹;touch 设备右键是浏览器默认
 *   (保存图片/复制链接),我们不该拦截。
 * - 必须在 capture 阶段 preventDefault,否则默认菜单会抢先出现。
 */
function onContextMenu(e, m) {
  if (e.button !== 2) return
  if (typeof window !== 'undefined' &&
      window.matchMedia &&
      window.matchMedia('(hover:hover)').matches) {
    e.preventDefault()
    emit('context-menu', { msg: m, x: e.clientX, y: e.clientY })
  }
}

function clearPress() {
  if (pressTimer.value) {
    clearTimeout(pressTimer.value)
    pressTimer.value = null
  }
}

onBeforeUnmount(clearPress)
</script>

<style scoped src="./ChatBubble.css"></style>
