/**
 * 桌面端消息右键菜单(2026-10-15 新增,2026-10-20 从 ChatThread.vue 抽出)
 *
 * 只负责:开合 / 位置 / 当前目标 / 外部点击与 Esc 的收起。
 * 具体动作(复制、引用、撤回)由调用方通过回调接管。
 */
import { ref, onBeforeUnmount } from 'vue'

export function useMessageContextMenu({ onOpen, onQuote, onRecall } = {}) {
  const open = ref(false)
  const target = ref(null)
  const pos = ref({ x: 0, y: 0 })

  function onOutside(e) {
    // 点菜单本体也算「外部」:document 监听在捕获阶段,菜单内的 click 照样会先到这里,
    // 所以先排除菜单内部,否则菜单项的 click 还没跑菜单就关了。
    const menu = document.querySelector('.ctx')
    if (menu && e.target && menu.contains(e.target)) return
    close()
  }

  function onEsc(e) {
    if (e.key === 'Escape') close()
  }

  function bind() {
    document.addEventListener('click', onOutside, true)
    document.addEventListener('contextmenu', onOutside, true)
    document.addEventListener('scroll', onOutside, true)
    window.addEventListener('keydown', onEsc, true)
  }

  function unbind() {
    document.removeEventListener('click', onOutside, true)
    document.removeEventListener('contextmenu', onOutside, true)
    document.removeEventListener('scroll', onOutside, true)
    window.removeEventListener('keydown', onEsc, true)
  }

  function close() {
    open.value = false
    unbind()
  }

  /** 右键开菜单:先让调用方关掉长按表(两者互斥,避免同屏两个浮层) */
  function show({ msg, x, y }) {
    onOpen?.()
    target.value = msg
    pos.value = { x, y }
    open.value = true
    unbind()
    bind()
  }

  function quote() {
    const m = target.value
    if (!m) return
    close()
    onQuote?.(m)
  }

  function recall() {
    const m = target.value
    if (!m) return
    close()
    onRecall?.(m)
  }

  onBeforeUnmount(unbind)

  return { open, target, pos, show, close, quote, recall }
}
