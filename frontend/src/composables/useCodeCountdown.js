/**
 * 验证码倒计时。
 *
 * [为什么抽出来]
 * Login.vue 与 Register.vue 各有一份完全相同的 `cd` ref + setInterval,
 * 且两处都缺了设计 §4 要求的 `aria-live="polite"`(倒计时要能被读屏播报,
 * 否则用户不知道按钮为什么突然不能点)。修一处漏一处的典型写法。
 *
 * [为什么这里不发 aria-live 所需的文案]
 * 组件不生成 DOM,aria 属性由页面写在按钮上。这里只管秒数与计时器生命周期。
 *
 * 秒数用模块级共享会导致两个页面(路由切换时短暂共存)互相干扰倒计时,
 * 所以仍然是每实例独立。
 */
import { ref, onUnmounted } from 'vue'

/** 后端限制的冷却秒数,与 sendCode 接口的 TTL 对齐 */
export const CODE_COOLDOWN = 60

/**
 * @param {number} [seconds=CODE_COOLDOWN] 冷却秒数
 * @returns {{ cd: import('vue').Ref<number>, sending: import('vue').Ref<boolean>, start: Function, tick: Function }}
 */
export function useCodeCountdown(seconds = CODE_COOLDOWN) {
  /** 剩余冷却秒数。0 = 可以点获取 */
  const cd = ref(0)
  /** 请求在途。防止连点产生并发请求 */
  const sending = ref(false)
  let timer = null

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function start() {
    stop()
    cd.value = seconds
    timer = setInterval(() => {
      cd.value -= 1
      if (cd.value <= 0) stop()
    }, 1000)
  }

  /**
   * 页面切走时必须停表。
   * 漏掉的话,setInterval 会活到页面卸载之后继续跑 —— 路由来回切几次后
   * 同时跑着几个定时器,cd 被互相拉快,表现为「冷却还没走完就能重发」。
   */
  onUnmounted(stop)

  return { cd, sending, start, stop }
}
