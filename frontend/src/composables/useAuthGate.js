import { ref } from 'vue'

/**
 * 全站认证闸门。
 *
 * 模块级单例:后端任何端点回 1029(USER_NOT_VERIFIED)时由 request.js 直接打开,
 * 页面主动拦截(发私信/建圈/加圈/发集市)也调同一个 openAuthGate。
 * 组件只负责渲染,不各自维护一份开关——多份开关会出现「弹了两个」或「关了一个另一个还在」。
 */
const visible = ref(false)

export function openAuthGate() {
  visible.value = true
}

export function closeAuthGate() {
  visible.value = false
}

export function useAuthGate() {
  return { visible, openAuthGate, closeAuthGate }
}
