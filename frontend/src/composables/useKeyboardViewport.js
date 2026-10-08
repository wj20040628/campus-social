import { ref, onMounted, onBeforeUnmount } from 'vue'

/**
 * iOS 键盘高度(Chat.vue.md §1.4)
 *
 * ## 为什么必须用 visualViewport 而不是 window.resize
 *
 * iOS Safari 在键盘弹起时 **`window.innerHeight` 不变**,变的只有
 * `visualViewport.height`(和 `offsetTop`)。所以监听 window 的 resize:
 *   - 安卓 Chrome:正常(它确实改 innerHeight)
 *   - iOS Safari:永远不触发 -> 输入框被键盘盖住约 300px
 * 这是移动端 IM 最常见的一类线上事故,而且**只在 iOS 上复现**,
 * 安卓开发机怎么测都测不出来。
 *
 * ## 为什么写入 CSS 变量而不是直接改 style
 *
 * `--kb-h` 写在 :root 上,输入区只负责 `translateY(calc(-1 * var(--kb-h, 0px)))`。
 * 于是「键盘多高」这个 JS 关注点与「输入区怎么让位」这个 CSS 关注点解耦。
 * 动 transform 而不是 top/height(§4:禁止动会触发布局的属性)。
 *
 * @param {(kb:number)=>void} [onChange] 键盘高度变化回调(用于把最新一条滚进可视区)
 * @returns {{ kbHeight: import('vue').Ref<number> }}
 */
export function useKeyboardViewport(onChange) {
  /** 键盘占用的视口高度(px)。0 = 键盘未弹起 */
  const kbHeight = ref(0)

  /** 统一的卸载路径:无分支,避免「加了监听忘了解绑」这种泄漏 */
  let detach = () => {}

  function apply(kb) {
    // 1px 容差:visualViewport 在拖动时会给出小数与微小抖动,
    // 不做容差会让 transform 每帧重写,一直跑合成层动画
    if (Math.abs(kb - kbHeight.value) < 1) return
    kbHeight.value = kb
    document.documentElement.style.setProperty('--kb-h', `${kb}px`)
    onChange?.(kb)
  }

  onMounted(() => {
    const vv = window.visualViewport

    if (vv) {
      const sync = () => {
        /*
         * offsetTop 也要减:键盘弹起时用户上滑页面,
         * visualViewport 会相对 layout viewport 向上偏移,
         * 只减 height 会漏掉 offsetTop,输入区位置会偏高。
         */
        apply(Math.max(0, window.innerHeight - vv.height - vv.offsetTop))
      }
      vv.addEventListener('resize', sync)
      vv.addEventListener('scroll', sync)
      sync() // 首次同步:从别的 App 切回时键盘可能已弹起
      detach = () => {
        vv.removeEventListener('resize', sync)
        vv.removeEventListener('scroll', sync)
      }
      return
    }

    /*
     * 无 visualViewport 的浏览器(桌面 Chrome):
     * window.innerHeight 与 clientHeight 的差就是被键盘/软键盘占掉的部分。
     * 桌面上这个值恒为 0,变量照样写入 —— 否则同一份 CSS 的
     * `var(--kb-h, 0px)` fallback 在桌面生效、在移动端不生效,
     * 同一段代码两种行为,是最难查的一类 bug。
     */
    const onWinResize = () => {
      apply(Math.max(0, window.innerHeight - document.documentElement.clientHeight))
    }
    window.addEventListener('resize', onWinResize)
    detach = () => window.removeEventListener('resize', onWinResize)
  })

  onBeforeUnmount(() => {
    detach()
    // 必须清掉:root 上的 --kb-h 会残留到下一个页面,
    // 表现是「从会话页退出后,别的页面底部莫名多出一段空白」
    document.documentElement.style.removeProperty('--kb-h')
  })

  return { kbHeight }
}
