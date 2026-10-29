/**
 * 复制文本。
 *
 * 为什么需要兜底：`navigator.clipboard` 只在**安全上下文**存在——
 * https 或 localhost。站点备案前公网走 `http://120.26.4.66`，
 * 那个环境里 `navigator.clipboard` 直接是 undefined，
 * 于是每个「点击复制」都落进 catch，只能弹一句「长按地址栏试试」。
 * 用户实测就是「复制不了」。
 *
 * 兜底用 `execCommand('copy')`：已废弃但全平台仍可用，
 * 且不要求安全上下文。这是这类站点唯一能用的同步复制通道。
 *
 * @returns {Promise<boolean>} 真复制成功才 true，调用方据此选提示文案
 */
export async function copyText(text) {
  const value = String(text ?? '')
  if (!value) return false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch {
    // 权限被拒/非安全上下文：继续走兜底，而不是直接失败
  }
  return legacyCopy(value)
}

function legacyCopy(value) {
  const ta = document.createElement('textarea')
  ta.value = value
  ta.setAttribute('readonly', '')
  /*
   * 不能用 display:none / visibility:hidden —— 那样元素不可选中，
   * execCommand 会返回 false。只能「在视口内但不可见」。
   */
  ta.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;'
  document.body.appendChild(ta)

  // 记住并恢复用户原来的选区：复制不该顺手清掉别处的选中
  const sel = document.getSelection()
  const saved = sel && sel.rangeCount > 0 ? sel.getRangeAt(0) : null

  ta.select()
  ta.setSelectionRange(0, value.length)
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  ta.remove()
  if (saved && sel) {
    sel.removeAllRanges()
    sel.addRange(saved)
  }
  return ok
}
