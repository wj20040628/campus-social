/**
 * 字体大小(标准 / 大 / 特大)—— 全站唯一事实。
 * ------------------------------------------------------------------
 * [机制]
 * 三档映射到 <html data-font-scale="large|xlarge">,由 design-tokens.css
 * 尾部的两个覆盖块改写 --ys-text-* 字号阶梯(行高是无单位值,自动跟随)。
 * standard 档**不落属性**,保持默认。
 *
 * [为什么照抄 useTheme 的模式]
 * 与主题一样:全站唯一的视觉基调、与登录态无关、要持久化、要在
 * app.mount 前落好避免首屏跳一下。模块级单例 + localStorage 的理由
 * 见 useTheme.js 头注释,不重复。
 *
 * [为什么不做任意滑杆]
 * 字号阶梯是设计过的 8 级(12~30px),任意值会打破 4px 网格与对比度
 * 达标(WCAG 1.4.4 只要求 200% 缩放,三档足够);三档选项也更符合
 * 「设置项」的心智,滑杆是调试工具不是设置。
 */
import { computed, readonly, ref } from 'vue'

const STORAGE_KEY = 'ys-font-scale'
const VALUES = ['standard', 'large', 'xlarge']

function readSaved() {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return VALUES.includes(v) ? v : 'standard'
  } catch {
    return 'standard'
  }
}

const scale = ref(readSaved())

function apply(value) {
  const root = document.documentElement
  if (value === 'standard') {
    delete root.dataset.fontScale
  } else {
    root.dataset.fontScale = value
  }
}

/** 应用启动时调用一次:在 Vue 挂载前把字号档位落到 <html>(main.js) */
export function initFontScale() {
  apply(scale.value)
}

export function useFontScale() {
  function setScale(value) {
    scale.value = VALUES.includes(value) ? value : 'standard'
    try {
      localStorage.setItem(STORAGE_KEY, scale.value)
    } catch {
      /* 隐私模式写不进就只管本次会话 */
    }
    apply(scale.value)
  }

  return {
    /** 'standard' | 'large' | 'xlarge' */
    scale: readonly(scale),
    setScale,
    /** 通用设置页的三档选项(label 与预览稿一致) */
    options: [
      { value: 'standard', label: '标准' },
      { value: 'large', label: '大' },
      { value: 'xlarge', label: '特大' },
    ],
  }
}
