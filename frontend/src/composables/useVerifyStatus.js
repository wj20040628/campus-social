/**
 * 校园信息状态(全站共享)
 * ------------------------------------------------------------------
 * 2026-10-30 起它只是可选资料状态，不承载任何功能权限；
 * 功能门槛由后端按账号年龄 + 信誉分判定。
 *
 * [为什么用模块级单例,而不是每个组件各自 useState]
 * 状态是**全站唯一**的事实:设置页、引导页、抽屉都要读它。
 * 若每个消费方各自在 onMounted 里拉一次,首屏会并发打出 2~3 个
 * 相同请求(且 Profile / Home 都可能被同时挂载),后端各返回一份 ——
 * 数据虽然一致,但白白多打两跳,且两个副本可能短暂不一致
 * (一个已加载一个还在飞,用户会看到提示条闪一下又消失)。
 *
 * 模块级单例的三件事:
 *   1. `cached` 记住上一次结果,组件反复挂载不再请求
 *   2. `inflight` 记住在途 Promise,并发调用共享同一次请求
 *   3. `version` 供显式失效(重新提交学号后要重拉,否则用户看不到状态变化)
 *
 * [不存进 Pinia 的理由]
 * user store 存的是**登录态**(token / 角色);校园信息状态是**会变的业务事实**,
 * 提交学号后就要改。混进 store 会让「刷新页面 = 重新拉状态吗」
 * 变成一个每次都要重新回答的问题。留在本文件里,失效边界就是本文件。
 */
import { computed, readonly, ref, watch } from 'vue'
import { verifyStatus } from '@/api/verify'
import { useUserStore } from '@/stores/user'

/** 0 未填写 / 1 已登记 / 2 核对中 / 3 需修改 */
export const VERIFY_UNVERIFIED = 0
export const VERIFY_OK = 1
export const VERIFY_PENDING = 2
export const VERIFY_REJECTED = 3

const state = ref(null)
const failed = ref(false)
const loading = ref(false)

let cached = null
let inflight = null

/**
 * 拉一次校园信息状态。
 *
 * @param {boolean} force true = 忽略缓存强制重拉(提交学号后用)
 * @returns {Promise<object|null>} 失败返回 null,不抛 —— 它是**附加信息**,
 *   拉不到不该让页面报错
 */
async function load(force = false) {
  if (!force && cached) return cached
  if (!force && inflight) return inflight

  /*
   * 游客不发请求(2026-10-25 修):分享卡片落地页是免登录的,
   * 游客进详情页时若有组件顺手调 load(),GET /user/verify/status 会 401,
   * request.js 的 401 拦截把它当成「登录过期」踢去登录页 —— 落地页整页报废。
   * 游客本来就没有这条资料,直接给 null,不发请求。
   */
  const userStore = useUserStore()
  if (!userStore.hasToken) {
    failed.value = false
    return null
  }

  loading.value = true
  inflight = verifyStatus()
    .then((data) => {
      cached = data || null
      state.value = cached
      failed.value = !cached
      return cached
    })
    .catch(() => {
      // request.js 已按需弹错;这里只标记,不重抛
      failed.value = true
      return null
    })
    .finally(() => {
      loading.value = false
      inflight = null
    })

  return inflight
}

/**
 * 登录态变化时必须清空:上一个人的校园信息状态不能留给下一个人。
 * token 换了就重置,与是否 force 无关。
 */
function reset() {
  cached = null
  inflight = null
  state.value = null
  failed.value = false
  loading.value = false
}

export function useVerifyStatus() {
  //登录态一变就清空:它是**上一个账号**的私有数据,
  // 换人后若还留着,下一个登录的人会看到别人的退回理由。
  // watch 放在 composable 内而不是让每个调用方自己判断 ——
  // 漏一处就是一次「把别人的审核记录显示给别人」的事故。
  const user = useUserStore()
  watch(
    () => user.accessToken,
    (tok) => {
      if (!tok) reset()
    },
  )

  return {
    status: readonly(state),
    failed: readonly(failed),
    loading: readonly(loading),
    /** verifyStatus,未拉到时为 null(不是 0 —— 「不知道」与「未填写」不是一回事) */
    code: computed(() => state.value?.verifyStatus ?? null),
    text: computed(() => state.value?.verifyStatusText || ''),
    rejectReason: computed(() => state.value?.rejectReason || ''),
    /** 状态原始码(0 未填写 / 1 已登记 / 2 核对中 / 3 需修改),未拉到为 null */
    studentNo: computed(() => state.value?.studentNo || ''),
    /** 学校归属是否填全 */
    profileComplete: computed(() => state.value?.profileComplete === true),
    load,
    reset,
  }
}