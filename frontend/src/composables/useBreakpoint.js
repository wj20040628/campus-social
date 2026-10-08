import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import tokens from '@/styles/design-tokens.json'

/**
 * 断点 composable
 * ------------------------------------------------------------------
 * 断点值的三处同步纪律(SPEC §10.4,OD-18 裁定为「手工同步 + 文档纪律」):
 *   1. design-tokens.json  —— 唯一源
 *   2. design-tokens.css 的 @custom-media
 *   3. 本文件
 *
 * 本文件**从 design-tokens.json import 值**,不写死字面量。
 * 改断点时只需改 JSON + CSS 两处,本文件自动跟随,
 * 等于把「三处同改」降成「两处同改」。
 *
 * 另:组件里的 CSS 媒体查询用字面量 `@media (min-width: 1024px)`,
 * 因为本项目没有 postcss-custom-media,`@media (--ys-bp-lg)` 会被浏览器
 * 静默丢弃(详见 styles/design-tokens.css 顶部说明)。JS 侧可用本文件的数值。
 */
const BP = tokens.breakpoint

/** 断点数值,直接来自 design-tokens.json(唯一源) */
export const BREAKPOINTS = {
  sm: parseInt(BP.sm.value, 10),         // 640
  md: parseInt(BP.md.value, 10),         // 768
  lg: parseInt(BP.lg.value, 10),         // 1024
  xl: parseInt(BP.xl.value, 10),         // 1280
  '2xl': parseInt(BP['2xl'].value, 10), // 1536
}

/** 视口宽 -> 档位名。档位语义与 design-tokens.json 的 breakpoint 段一致。 */
function toName(w) {
  if (w >= BREAKPOINTS['2xl']) return '2xl'
  if (w >= BREAKPOINTS.xl) return 'xl'
  if (w >= BREAKPOINTS.lg) return 'lg'
  if (w >= BREAKPOINTS.md) return 'md'
  if (w >= BREAKPOINTS.sm) return 'sm'
  return 'xs'
}

/**
 * 注册一个 resize 监听,返回 [widthRef, nameComputed]。
 * SSR/无 window 时返回 lg 档默认值,不做监听。
 */
function useViewport() {
  const hasWindow = typeof window !== 'undefined'
  const width = ref(hasWindow ? window.innerWidth : BREAKPOINTS.lg)

  function measure() {
    width.value = window.innerWidth
  }

  onMounted(() => {
    if (!hasWindow) return
    measure()
    window.addEventListener('resize', measure, { passive: true })
  })

  onBeforeUnmount(() => {
    if (!hasWindow) return
    window.removeEventListener('resize', measure)
  })

  const name = computed(() => toName(width.value))
  return { width, name }
}

/**
 * 当前视口档位。
 * @returns {{ width: import('vue').Ref<number>, name: import('vue').ComputedRef<string>, BREAKPOINTS: typeof BREAKPOINTS }}
 */
export function useBreakpoint() {
  const { width, name } = useViewport()
  return { width, name, BREAKPOINTS }
}

/**
 * 是否为移动端(< lg 档,即 < 1024px)。
 * 这是 TabBar 与桌面 el-menu 侧栏切换的唯一判据
 * (MainLayout.vue 与 mobile-shell.md §2.7 都以此为准)。
 */
export function useIsMobile() {
  const { width } = useViewport()
  const isMobile = computed(() => width.value < BREAKPOINTS.lg)
  return isMobile
}

export default useBreakpoint