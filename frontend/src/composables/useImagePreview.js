/**
 * 图片大图预览（2026-10-28）。
 *
 * 把「点开 → 关闭」这段状态从 Profile.vue 挪出来：那个文件已经贴着
 * 单文件 400 行门禁，多两段 preview 函数就会被挤爆注释。
 */
import { ref } from 'vue'
import { resolveUrl } from '@/utils/resolve'

export function useImagePreview() {
  const open = ref(false)
  const src = ref('')
  const alt = ref('')

  /** 传空 url 时什么都不做——点了弹一张空白图比没反应更糟 */
  function preview(url, label = '') {
    const resolved = resolveUrl(url || '')
    if (!resolved) return
    src.value = resolved
    alt.value = label
    open.value = true
  }

  return { open, src, alt, preview }
}
