/**
 * 列表卡片的焦点管理(AdminReview.vue.md §5 无障碍第三条)。
 *
 * 设计原文:
 * > 卡片内容区 `tabindex="-1"`,切换条目时程序化聚焦,让屏幕阅读器读出新条目。
 *
 * [为什么单独抽出来]
 * 放进 AdminReview.vue 会顶破 300 行门禁,而且这段逻辑的触发条件
 * (「索引变了」「列表换了」)与快捷键、处置、撤销三件事都有关,
 * 混在页面里下次改撤销流程时很容易顺手改错。
 *
 * [为什么按 id 存 DOM 引用,不用下标]
 * approve / reject 会把条目从列表移除(乐观出栈),下标会错位。
 * 按 id 取才能保证聚焦的是「当前这条」,而不是「原来第 N 条」
 * 移位之后占据该下标的那条。
 *
 * [为什么用 Map 不用 reactive 对象]
 * 存的是 DOM 节点,不是要参与渲染的数据。放进 reactive 会让 Vue
 * 尝试把 DOM 节点包成 Proxy(focus() 仍能调,但访问器行为诡异),
 * 也会让这个 Map 被误当成响应式状态而触发无谓的依赖收集。
 */
import { watch, nextTick } from 'vue'

/**
 * @param {object} opts
 * @param {import('vue').Ref<number>} opts.index   当前条目下标
 * @param {import('vue').Ref<Array>} opts.list     当前列表
 * @param {import('vue').Ref<boolean>} opts.disabled 移动端等场景下不聚焦(读屏焦点会被抢)
 * @returns {{ setRef: (id:*, el:Element|null) => void, focusCurrent: Function }}
 */
export function useListFocus({ index, list, disabled }) {
  const cardEls = new Map()

  /** 模板里 :ref 用。卸载时 el 变 null,顺手把键删掉,不留 detached 节点 */
  function setRef(id, el) {
    if (el) cardEls.set(id, el)
    else cardEls.delete(id)
  }

  /**
   * 聚焦当前卡片。
   *
   * 必须 await nextTick:index 变了但列表还没重渲染,
   * 这时拿到的仍是上一条的 DOM,焦点会停在一个即将消失的元素上。
   *
   * focus() 的返回值/异常都不处理:元素被卸载时浏览器会静默忽略,
   * 这属于「焦点没落上」的正常情况,不该打扰管理员。
   */
  async function focusCurrent() {
    if (disabled?.value) return
    const t = list.value[index.value]
    if (!t) return
    await nextTick()
    cardEls.get(t.id)?.focus?.()
  }

  watch(index, focusCurrent)

  /**
   * 列表整体换了(切 Tab / 重新加载第一页)要清引用。
   * 不清的话 Map 里留着已卸载的 DOM:一是内存泄漏,
   * 二是按 id 取会命中 detached 节点,focus() 静默无效 ——
   * 表现为「切 Tab 后快捷键全失灵」,而且没有任何报错。
   */
  watch(list, () => cardEls.clear())

  return { setRef, focusCurrent }
}