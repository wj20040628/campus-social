import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'

/**
 * 首页浮动发帖按钮的可拖拽逻辑。
 *
 * 职责:
 * - 默认落点:右下角(距底 88px 避开 TabBar 56px + 安全区,距右 16px)
 * - 拖拽:支持触屏(touchstart/move/end)+ 鼠标(pointerdown/move/up),
 *   两套事件各自判「位移 < 阈值 = 点击 / ≥ 阈值 = 拖拽」,互不干扰
 * - 松手后吸附到最近的屏幕边缘(左/右),不跨屏
 * - 位置持久化 localStorage(用户改过的位置跨 session 保留)
 * - KeepAlive 回来时,watch route 切回 Home 时不重置位置
 */
export function useDraggableFab() {
  const router = useRouter()
  const fabEl = ref(null)

  // 视口感知:按钮坐标基于 window.innerWidth/innerHeight
  const fab = ref({
    x: 16,
    y: 88,
    dragging: false,
  })

  // 拖拽阈值:位移 < 8px 视为点击(防误触);≥ 8px 视为拖拽(开始移动)
  const DRAG_THRESHOLD = 8
  const STORAGE_KEY = 'fab-position'

  // 默认落点计算:右下角
  function defaultPos() {
    return {
      x: Math.max(0, window.innerWidth - 72), // 56px 按钮 + 16px 边距
      y: 88, // TabBar 56px + 安全区 + 16px 留白
    }
  }

  // 从 localStorage 恢复;失败就回默认
  function restorePos() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      if (raw) {
        const p = JSON.parse(raw)
        if (typeof p.x === 'number' && typeof p.y === 'number') {
          return p
        }
      }
    } catch { /* 忽略,回默认 */ }
    return defaultPos()
  }

  function savePos() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({ x: fab.value.x, y: fab.value.y }))
    } catch { /* 满/隐私模式,忽略 */ }
  }

  // 拖拽中状态机
  let dragStartX = 0
  let dragStartY = 0
  let pointerStartX = 0
  let pointerStartY = 0
  let isDragging = false
  let moved = false

  // ---------- 触屏事件 ----------
  function onTouchStart(e) {
    const t = e.touches[0]
    dragStartX = t.clientX
    dragStartY = t.clientY
    fab.value.dragging = true
    moved = false
    e.preventDefault()
  }

  function onTouchMove(e) {
    const t = e.touches[0]
    const dx = t.clientX - dragStartX
    const dy = t.clientY - dragStartY
    if (!moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return
    moved = true
    isDragging = true
    const el = fabEl.value
    if (!el) return
    const btnW = el.offsetWidth || 56
    const btnH = el.offsetHeight || 56
    const vw = window.innerWidth
    const vh = window.innerHeight
    // 按钮用 CSS `bottom` 定位,fab.y = 距底边距离。
    // clientY = 距顶边 = vh - (bottom + btnH) → bottom = vh - btnH - clientY
    // 这里直接用「拖拽位移」增量更稳:bottom 方向与 clientY 方向相反,
    // 用户往上拖(clientY 减小)→ bottom 应增大 → 用 -(ΔclientY)
    let nx = fab.value.x + (t.clientX - dragStartX)
    let ny = fab.value.y - (t.clientY - dragStartY)
    nx = Math.max(0, Math.min(nx, vw - btnW))
    ny = Math.max(56, Math.min(ny, vh - btnH - 56)) // 上下各留 56(TabBar)
    fab.value.x = nx
    fab.value.y = ny
    e.preventDefault()
  }

  function onTouchEnd() {
    if (isDragging) {
      snapToEdge()
      savePos()
    }
    fab.value.dragging = false
    isDragging = false
    moved = false
  }

  // ---------- 指针事件(桌面/触屏通用,鼠标走这里) ----------
  function onPointerDown(e) {
    if (e.pointerType === 'touch') return // 触屏走 touch 事件,这里只处理 mouse
    pointerStartX = e.clientX
    pointerStartY = e.clientY
    fab.value.dragging = true
    moved = false
    e.preventDefault()
  }

  function onPointerMove(e) {
    if (e.pointerType === 'touch') return
    const dx = e.clientX - pointerStartX
    const dy = e.clientY - pointerStartY
    if (!moved && Math.hypot(dx, dy) < DRAG_THRESHOLD) return
    moved = true
    isDragging = true
    const el = fabEl.value
    if (!el) return
    const btnW = el.offsetWidth || 56
    const btnH = el.offsetHeight || 56
    const vw = window.innerWidth
    const vh = window.innerHeight
    let nx = fab.value.x + (e.clientX - pointerStartX)
    let ny = fab.value.y - (e.clientY - pointerStartY)
    nx = Math.max(0, Math.min(nx, vw - btnW))
    ny = Math.max(56, Math.min(ny, vh - btnH - 56)) // 上下各留 56(TabBar)
    fab.value.x = nx
    fab.value.y = ny
    e.preventDefault()
  }

  function onPointerUp(e) {
    if (e.pointerType === 'touch') return
    if (isDragging) {
      snapToEdge()
      savePos()
    } else if (!moved) {
      // 纯点击:跳发布页
      router.push('/publish')
    }
    fab.value.dragging = false
    isDragging = false
    moved = false
  }

  /** 松手吸附到最近的左/右边缘;bottom 保持不变(不跨屏上移) */
  function snapToEdge() {
    const vw = window.innerWidth
    const btnW = fabEl.value?.offsetWidth || 56
    const mid = vw / 2
    fab.value.x = fab.value.x + btnW / 2 < mid ? 16 : vw - btnW - 16
  }

  /** 纯点击(未拖过):触屏在 touchend 时触发,桌面在 pointerup 时触发 */
  function onClick() {
    // 防止「拖完松手」也被算成 click(浏览器对 touch 的 click 是 300ms 后补发)
    // 这里用 moved 标志位:拖过就不跳
    if (moved) return
    router.push('/publish')
  }

  onMounted(async () => {
    const p = restorePos()
    fab.value.x = p.x
    fab.value.y = p.y
    await nextTick()
  })

  onBeforeUnmount(() => {
    savePos()
  })

  return {
    fabEl,
    fab,
    handlers: {
      onTouchStart, onTouchMove, onTouchEnd,
      onPointerDown, onPointerMove, onPointerUp,
      onClick,
    },
  }
}

export default useDraggableFab
