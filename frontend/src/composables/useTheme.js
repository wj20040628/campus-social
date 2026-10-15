/**
 * 主题(浅色 / 深色)切换 —— 全站唯一事实。
 * ------------------------------------------------------------------
 * [为什么是模块级单例 + 落到 <html>]
 * 主题不是「某个组件的局部状态」,而是整页的视觉基调:
 * 我们的设计 token 走 `[data-theme="dark"]`,Element Plus 的原生组件
 * (el-dialog / el-button / el-message …)走它自己的深色变量,需要
 * `<html class="dark">`。两者都必须挂在 <html> 上,全站统一生效。
 *
 * 持久化用 localStorage(键 `ys-theme`),刷新不丢。
 *
 * [为什么不在 Pinia]
 * 主题与登录态无关,任何访客都能切;放进 user store 反而要处理
 * 「未登录也有主题」的边界。留在本文件,失效边界 = 本文件。
 *
 * [首屏闪白]
 * initTheme() 必须在 app.mount('#app') 之前调用(见 main.js),
 * 把 data-theme / dark class 落到 <html>,否则先白底后变黑底会闪一下。
 *
 * [EP 深色二次覆盖]
 * 本文件只负责「开关 + 落类」。EP 原生组件在深色下的最终观感,
 * 依赖 main.js 引入的 element-plus/theme-chalk/dark/css-vars.css,
 * 以及必要时对个别 --el-* 的二次覆盖(见 design-tokens.css 顶部注释)。
 * 这块「好不好看」必须在浏览器里实测,编译/门禁查不出来。
 */
import { computed, readonly, ref } from 'vue'

const STORAGE_KEY = 'ys-theme'

function readSaved() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
  }
}

const theme = ref(readSaved())

function apply(value) {
  const root = document.documentElement
  if (value === 'dark') {
    root.dataset.theme = 'dark'
    root.classList.add('dark') // EP 深色变量需要 html.dark
  } else {
    root.dataset.theme = 'light'
    root.classList.remove('dark')
  }
}

/** 应用启动时调用一次:在 Vue 挂载前把主题落到 <html>,避免首屏闪白 */
export function initTheme() {
  apply(theme.value)
}

export function useTheme() {
  function setTheme(value) {
    theme.value = value === 'dark' ? 'dark' : 'light'
    try {
      localStorage.setItem(STORAGE_KEY, theme.value)
    } catch {
      /* 隐私模式写不进就只管本次会话 */
    }
    apply(theme.value)
  }

  function toggle() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return {
    /** 'light' | 'dark' */
    theme: readonly(theme),
    isDark: computed(() => theme.value === 'dark'),
    setTheme,
    toggle,
  }
}
