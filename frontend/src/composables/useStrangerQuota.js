/**
 * 今日剩余破冰额度(AC-04,账户级,非按人)—— 全站共享。
 * ------------------------------------------------------------------
 * [为什么用模块级单例,而不是 GreetButton 在 onMounted 各自拉]
 * 原 GreetButton 每个实例都在 onMounted 里调一次 strangerQuota(),
 * feed 一屏 10~20 张卡 = 10~20 个完全相同请求(N+1),且每个都打后端。
 * 额度是**账户级**事实(remainingNewPeer 跟当前登录用户走,不跟某张帖子/
 * 某个人走),全站只需一份。模块级单例保证:并发挂载共享同一次请求,
 * 已拉过不再请求。这就是「首页拉一次、feed 每卡复用」的干净实现,
 * 且顺带覆盖 PostDetail 等所有用到 GreetButton 的地方,不用逐页传 prop。
 *
 * 三件事(同 useVerifyStatus 的范式):
 *   1. `cached` 记住上一次数字,组件反复挂载不再请求
 *   2. `inflight` 记住在途 Promise,并发调用共享同一次请求
 *   3. 登录态变化时 reset:上一个账号的额度不能留给下一个账号
 *
 * [不存进 Pinia 的理由]
 * 与认证状态同理:这是会变的业务事实,混进 user store 会让失效边界模糊。
 * 留在本文件,失效边界就是本文件。
 *
 * [与旧 onMounted 行为对齐]
 * 取不到(401 / 断网)时保持 null 而非 0 —— 「不知道」≠「用完了」。
 * null 时 GreetButton 不置灰,宁可点了被后端拒,也不要假置灰骗人。
 * 失败不标记 alreadyLoaded,下次挂载可重试(网恢复了能补上置灰)。
 */
import { onMounted, readonly, ref, watch } from 'vue'
import { strangerQuota } from '@/api/chat'
import { useUserStore } from '@/stores/user'

/** null = 还没拉到 / 拉不到(「不知道」,不置灰) */
const remaining = ref(null)
const loading = ref(false)

let cached = null
let inflight = null
let alreadyLoaded = false

async function load(force = false) {
  // 已成功拉到(含 0)就复用,不再打请求
  if (!force && alreadyLoaded) return cached
  // 在途:并发调用共享同一次请求,不叠加
  if (!force && inflight) return inflight

  loading.value = true
  inflight = strangerQuota()
    .then((data) => {
      // 取不到剩余数时保持 null:与旧 onMounted 失败分支一致,不置灰
      cached = data?.remainingNewPeer != null ? Number(data.remainingNewPeer) : null
      remaining.value = cached
      alreadyLoaded = true
      return cached
    })
    .catch(() => {
      // request.js 已按需弹错;这里只标记,不重抛。
      // 失败不置 alreadyLoaded:下次挂载可重试。
      remaining.value = null
      cached = null
      return null
    })
    .finally(() => {
      loading.value = false
      inflight = null
    })

  return inflight
}

function reset() {
  cached = null
  inflight = null
  alreadyLoaded = false
  remaining.value = null
  loading.value = false
}

export function useStrangerQuota() {
  const user = useUserStore()
  // 登录态一变就清空:额度是上一个账号的私有数据
  watch(
    () => user.accessToken,
    (tok) => {
      if (!tok) reset()
    },
  )

  // 第一个挂载的 GreetButton 触发拉取;其余共享同一次请求(单飞),
  // 已拉过或在途都不会重复打。未登录不打(打了也是 401,徒增报错)。
  // 放在 composable 里而非让每个消费方自己 onMounted ——
  // 漏一处就是一次「额度永远为 null、置灰从不出现」的静默失效。
  onMounted(() => {
    if (user.accessToken) load()
  })

  return {
    /** 剩余可破冰的新对象数;null 表示未知(不置灰) */
    quotaRemaining: readonly(remaining),
    loading: readonly(loading),
    load,
    reset,
  }
}
