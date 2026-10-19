/**
 * 校内认证状态(全站共享)
 * ------------------------------------------------------------------
 * [为什么用模块级单例,而不是每个组件各自 useState]
 * 认证状态是**全站唯一**的事实:顶栏、个人主页、首页提示条都要读它。
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
 * user store 存的是**登录态**(token / 角色);认证状态是**会变的业务事实**,
 * 提交学号后就要改。混进 store 会让「刷新页面 = 重新认证状态吗」
 * 变成一个每次都要重新回答的问题。留在本文件里,失效边界就是本文件。
 */
import { computed, readonly, ref, watch } from 'vue'
import { verifyStatus } from '@/api/verify'
import { useUserStore } from '@/stores/user'

/** 0 未认证 / 1 已认证 / 2 审核中 / 3 已驳回 */
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
 * 拉一次认证状态。
 *
 * @param {boolean} force true = 忽略缓存强制重拉(提交学号后用)
 * @returns {Promise<object|null>} 失败返回 null,不抛 —— 认证状态是**附加信息**,
 *   拉不到不该让页面报错(它挂在首页/个人主页上,一失败就整页报错是把
 *   「锦上添花」当成「雪中送炭」)
 */
async function load(force = false) {
  if (!force && cached) return cached
  if (!force && inflight) return inflight

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
 * 登录态变化时必须清空:上一个人的认证状态不能留给下一个人。
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
  //登录态一变就清空:认证状态是**上一个账号**的私有数据,
  // 换人后若还留着,下一个登录的人会看到别人的驳回理由。
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
    /** verifyStatus,未拉到时为 null(不是 0 —— 「不知道」与「未认证」不是一回事) */
    code: computed(() => state.value?.verifyStatus ?? null),
    text: computed(() => state.value?.verifyStatusText || ''),
    rejectReason: computed(() => state.value?.rejectReason || ''),
    /** 认证状态原始码(0 未认证 / 1 已认证 / 2 审核中 / 3 已驳回),未拉到为 null */
    studentNo: computed(() => state.value?.studentNo || ''),
    /** §5 A3:认证解锁的能力位,前端此前零消费,现在暴露出去 */
    canStrangerChat: computed(() => state.value?.canStrangerChat ?? false),
    canPublishAnnouncement: computed(() => state.value?.canPublishAnnouncement ?? false),
    autoApproved: computed(() => state.value?.autoApproved ?? false),
    /**
     * 该不该展示「驳回」提示条。
     *
     * **三个条件缺一不可**,其中 `rejectReason` 非空最容易被漏:
     * 实测库里 user id=23 就是 verifyStatus=3 但 rejectReason=null
     * (那条是老数据,驳回时还没写理由)。只判 status 会给这类用户弹一个
     * 「你的认证被驳回了」的空盒子 —— 比不显示更糟,他会以为系统检测到
     * 了他没告诉系统的东西,转头去问管理员到底哪里错了。
     */
    showRejected: computed(
      () => state.value?.verifyStatus === VERIFY_REJECTED && !!state.value?.rejectReason,
    ),
    /**
     * 该不该展示「审核中」提示。
     *
     * 只判 status=2:审核中是**可预期**的正常态,给一句「在等谁、等多久」
     * 就能解释掉用户「提交完就没下文了」的困惑。0 未认证不提示 ——
     * 那是「还没开始」,不是「出了问题」。
     */
    showPending: computed(() => state.value?.verifyStatus === VERIFY_PENDING),
    load,
    reset,
  }
}