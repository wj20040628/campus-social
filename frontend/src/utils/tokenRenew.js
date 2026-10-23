import { useUserStore } from '@/stores/user'

/**
 * access token 的**主动续期**。
 * ------------------------------------------------------------------
 * 【为什么需要】
 * 改版前全站没有任何主动续期：`accessExpireAt` 后端一直在下发、
 * localStorage 也存了，但**从头到尾没有任何地方读它**。
 * 于是 access token（15 分钟）一到，必然先吃一个 401 才被动刷新 ——
 * 用户看到的是「页面突然闪一下、某个请求失败又自动好了」，
 * 在弱网下这次 401 的恢复还可能失败，直接跳登录页。
 *
 * 【为什么提前 60 秒，而不是等到期】
 * 续期本身要发一次网络请求。如果掐着到期那一刻发，请求在途期间
 * 任何并发的业务请求都会拿到 401。提前 60 秒给这次请求留出窗口：
 *   - 续期成功 → 新 token 上线，后面所有请求都不会 401
 *   - 续期失败（断网）→ 还有 60 秒，下次 tick 会重试
 * 60 秒也是「用户切个标签页回来」的量级，不会频繁到打扰后端。
 *
 * 【为什么用 setInterval 每 30 秒轮询，而不是 setTimeout 精确到点】
 * setTimeout 的问题是休眠/后台标签页会累积漂移，且 laptop 合盖再打开时
 * 定时器可能整体延后几分钟触发 —— 那时 token 早过期了。
 * 轮询 + 每次重新读 accessSecondsLeft() 对时间源的变化免疫：
 * 只要醒着，最多晚 30 秒发现该续期。代价是每 30 秒一次极轻的本地计算。
 *
 * 【与 401 拦截器的关系】
 * 这是「锦上添花」的前置防线，不是替代。401 拦截器（request.js）
 * 仍然保留 —— 主动续期失败、或者 token 被服务端提前吊销时，
 * 401 是最后的兜底。两者共用 store 的 refresh() 单飞，
 * 不会互相触发并发刷新。
 */

/** 提前多少秒续期 */
const RENEW_AHEAD_SEC = 60
/** 轮询间隔 */
const TICK_MS = 30 * 1000

let timer = null
let renewing = false

/** 读一次当前该不该续期。抽成函数便于单测与诊断。 */
function shouldRenew(store) {
  const left = store.accessSecondsLeft()
  // null = 不知道到期时刻（老 token / 未记录）→ 不主动续期，交给 401 兜底
  if (left == null) return false
  return left <= RENEW_AHEAD_SEC
}

async function tick() {
  const store = useUserStore()
  // 没有 refresh 凭证就没什么可续的（未登录、或已登出）
  if (!store.refreshToken) return
  if (renewing) return // 上一轮还没回来，跳过
  if (!shouldRenew(store)) return

  renewing = true
  try {
    await store.refresh()
    // 成功：什么都不用做，setTokens 已更新 accessExpireAt
  } catch {
    /*
     * 静默失败。这里**不弹 toast、不改登录态**：
     *   1. 续期失败最常见的原因是断网 / 切后台，网络恢复后下一轮会成功；
     *   2. 真正的失效由 401 拦截器统一处理（清 token + 跳登录 + 提示），
     *      在这里再弹一次会让用户看到两条互相矛盾的提示；
     *   3. 若这里直接登出，用户切个后台回来就被踢了 —— 比 401 兜底更激进。
     */
  } finally {
    renewing = false
  }
}

/** 在 app 启动时调用一次（main.js）。重复调用幂等。 */
export function startTokenRenew() {
  if (timer) return
  timer = setInterval(tick, TICK_MS)

  // 从后台切回前台时立刻检查一次：休眠期间 token 很可能已经过期
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') tick()
  })
}

/** 停止（登出 / 测试用）。 */
export function stopTokenRenew() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}
