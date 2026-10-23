/**
 * 主题(跟随系统 / 浅色 / 深色)—— 全站唯一事实。
 * ------------------------------------------------------------------
 * [为什么是模块级单例 + 落到 <html>]
 * 主题不是「某个组件的局部状态」,而是整页的视觉基调:
 * 我们的设计 token 走 `[data-theme="dark"]`,Element Plus 的原生组件
 * (el-dialog / el-button / el-message …)走它自己的深色变量。
 * 落在 <html> 上,全站一处生效。
 *
 * 持久化用 localStorage(键 `ys-theme`),值是 'system' | 'light' | 'dark'。
 *
 * [2026-10-20 从二态改三态]
 * 原来只有 light / dark,关掉深色等于**强制浅色** —— 用户没法表达
 * 「跟着系统走」。三态里 'system' 才是真正想要的默认:
 * 白天自动浅色、晚上自动深色,不用每天手动切两次。
 *
 * 兼容:老用户 localStorage 里存的是 'light' / 'dark',两个都是合法
 * mode,原样读回,不迁移不清空;没存过的走 'system'。
 *
 * [为什么不在 Pinia]
 * 主题与登录态无关,任何访客都能切;放进 user store 反而要处理
 * 「未登录也有主题」的边界。留在本文件,失效边界 = 本文件。
 *
 * [首屏闪白]
 * initTheme() 必须在 app.mount('#app') 之前调用(见 main.js),
 * 把 data-theme 落到 <html>,否则先白底后变黑底会闪一下。
 * 注意 resolvedTheme 是 computed —— initTheme 里调 apply() 之前
 * 不能依赖任何组件上下文。
 *
 * [EP 深色二次覆盖]
 * 本文件只负责「开关 + 落属性」。EP 原生组件在深色下的最终观感,
 * 依赖 main.js 引入的 element-plus/theme-chalk/dark/css-vars.css,
 * 以及 design-tokens.css 里对 --el-* 的二次覆盖。
 * 这块「好不好看」必须在浏览器里实测,编译/门禁查不出来。
 */
import { computed, readonly, ref } from 'vue'

const STORAGE_KEY = 'ys-theme'
const MODES = ['system', 'light', 'dark']

/** 系统偏好。SSR / 老旧环境没有 matchMedia,退化成「浅色」。 */
const mq = typeof window !== 'undefined' && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-color-scheme: dark)')
  : null

function readSaved() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    // 老值是 'light' / 'dark',同样合法;不认识的一律回落到跟随系统
    return MODES.includes(saved) ? saved : 'system'
  } catch {
    /* 隐私模式读不到就按跟随系统 */
    return 'system'
  }
}

/** 用户选的模式(可能是 'system') */
const mode = ref(readSaved())
/** 系统当前是深色还是浅色;仅在 mode === 'system' 时影响结果 */
const systemDark = ref(mq ? mq.matches : false)

/** 真正落到 <html> 上的值:永远是 'light' 或 'dark' */
const resolved = computed(() => (
  mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value
))

function apply() {
  document.documentElement.dataset.theme = resolved.value
  // 不再加 html.dark class。EP dark css-vars 靠 `.dark` class 触发,
  // 它选择器 `:root, .dark`(0,0,1,0)比 design-tokens 的
  // `:root[data-theme="dark"]`(0,0,2,0)特异性**低**,但 CSS 变量定义
  // 里「后写者胜」优先于特异性 —— EP dark 值(冷灰蓝/默认蓝)会盖回
  // Bridge 段映射的友声暖深色,导致 el-menu/el-button 在深色下变回标准 EP。
  // 去掉 class 后,data-theme="dark" 是**唯一事实**。
}

let listening = false

/** 应用启动时调用一次:在 Vue 挂载前把主题落到 <html>,避免首屏闪白 */
export function initTheme() {
  apply()
  if (!mq || listening) return
  listening = true
  /**
   * 系统外观变化时,只有「跟随系统」的用需要跟着变。
   * 注意仍要更新 systemDark —— 用户之后切回「跟随系统」时
   * 用的是最新值,不是启动那一刻的快照。
   */
  const onChange = (e) => {
    systemDark.value = e.matches
    if (mode.value === 'system') apply()
  }
  if (typeof mq.addEventListener === 'function') mq.addEventListener('change', onChange)
  else if (typeof mq.addListener === 'function') mq.addListener(onChange) // Safari < 14
}

export function useTheme() {
  /** 设模式:'system' | 'light' | 'dark' */
  function setMode(value) {
    mode.value = MODES.includes(value) ? value : 'system'
    try {
      localStorage.setItem(STORAGE_KEY, mode.value)
    } catch {
      /* 隐私模式写不进就只管本次会话 */
    }
    apply()
  }

  /** 兼容旧调用点:setTheme('dark') 等价于 setMode('dark') */
  function setTheme(value) {
    setMode(value)
  }

  /**
   * 两步切换(设置页 / 通用设置里的开关仍在用)。
   * 注意它会**退出「跟随系统」**:拨开关就是明确指定浅色或深色。
   */
  function toggle() {
    setMode(resolved.value === 'dark' ? 'light' : 'dark')
  }

  return {
    /** 用户选的模式,含 'system' */
    mode: readonly(mode),
    /** 实际生效的外观,'light' | 'dark' */
    theme: computed(() => resolved.value),
    isDark: computed(() => resolved.value === 'dark'),
    setMode,
    setTheme,
    toggle,
  }
}

/** 模式的中文名,抽屉与设置页共用,避免两处文案漂移 */
export const THEME_MODE_LABEL = {
  system: '跟随系统',
  light: '浅色',
  dark: '深色',
}
