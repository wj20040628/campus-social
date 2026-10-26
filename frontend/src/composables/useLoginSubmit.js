/**
 * 登录页的两条提交路径(密码 / 验证码)。
 *
 * [为什么抽出来]
 * Login.vue 原本 297 行,离 300 行硬门禁只剩 3 行 —— 而设计 §3 要求补的
 * 封禁整页态、字段级错误、可点重试都还要往里加。副作用必须挪走。
 *
 * 这里只管「发请求 + 分类错误」,不发请求以外的指令:
 * 表单校验、Tab 切换、路由跳转留在页面里,因为那些依赖 el-form 的 ref。
 */
import { ref } from 'vue'
import { accountBlocked, authErrCode, isNetworkFail, credentialMessage, AUTH_ERR } from '@/utils/authError'

/**
 * @param {{ pwdForm:object, codeForm:object, userStore:object }} deps
 * @returns {{
 *   blocked: import('vue').Ref<null|{title:string,reason:string,code:number}>,
 *   formError: import('vue').Ref<string>,
 *   retryable: import('vue').Ref<boolean>,
 *   loadingPwd: import('vue').Ref<boolean>,
 *   loadingCode: import('vue').Ref<boolean>,
 *   submitPassword: Function, submitCode: Function, retry: Function, clearError: Function,
 * }}
 */
export function useLoginSubmit({ pwdForm, codeForm, userStore, onSuccess }) {
  /** 账号被冻结/注销。整页替换,不是 toast —— 见 AuthBlocked.vue 的注释 */
  const blocked = ref(null)
  /** 表单级错误文案 */
  const formError = ref('')
  /** 上一次是不是网络失败。是才给「点这里重试」 */
  const retryable = ref(false)
  /** 最近一次失败走的是哪条路径,重试要靠它 */
  let lastKind = null

  const loadingPwd = ref(false)
  const loadingCode = ref(false)

  function clearError() {
    formError.value = ''
    retryable.value = false
    lastKind = null
  }

  /**
   * 统一失败处理。
   *
   * 顺序有讲究:先判账号状态,再判网络,最后才落凭据文案。
   * 账号封禁的账号即使密码也是错的,主因仍是封禁 —— 先说「密码错了」
   * 会引导用户反复重置一个永远不会被接受的密码。
   */
  function handleFail(e, kind) {
    lastKind = kind
    const b = accountBlocked(e)
    if (b) {
      blocked.value = { ...b, appealable: b.code === AUTH_ERR.USER_FROZEN }
      formError.value = ''
      retryable.value = false
      return
    }
    retryable.value = isNetworkFail(e)
    formError.value = retryable.value
      ? '网络好像断了，点这里重试'
      : credentialMessage(e, kind)
  }

  async function submitPassword() {
    // 防重入。两个 Tab 的按钮各自 disabled,但「点这里重试」这条路径
    // 与 disabled 无关 —— 连点两下重试会发两次登录,后一次必失败,
    // 用户看到的是「明明密码是对的却提示密码错误」。
    if (loadingPwd.value) return
    clearError()
    loadingPwd.value = true
    try {
      await userStore.login(pwdForm.username, pwdForm.password, pwdForm.rememberMe === true)
      await userStore.fetchMe()
      onSuccess()
    } catch (e) {
      handleFail(e, 'password')
    } finally {
      loadingPwd.value = false
    }
  }

  async function submitCode() {
    if (loadingCode.value) return
    clearError()
    loadingCode.value = true
    try {
      await userStore.loginByCode(codeForm.email, codeForm.code)
      await userStore.fetchMe()
      onSuccess()
    } catch (e) {
      handleFail(e, 'code')
    } finally {
      loadingCode.value = false
    }
  }

  /**
   * 「点这里重试」。
   *
   * 重发的是**发请求**这一步,不是完整提交 —— 完整提交(含本地校验)在
   * PasswordForm / CodeForm 内部,它们 emit 事件时才走到这里。
   * 在这里重跑一遍全流程会绕过子组件的 validate,输入被清空时就会
   * 拿一个空密码去请求,后端返回 1005,用户看到的是「又失败了」。
   *
   * 网络失败时表单内容一定还在(请求发出去之前就失败了),
   * 所以不发这个事件是安全的。
   */
  function retry() {
    if (lastKind === 'code') submitCode()
    else if (lastKind === 'password') submitPassword()
  }

  /**
   * 账号不可用态下点了「返回登录」:清掉状态,回到可输入的表单。
   *
   * 连 lastKind 一起清:账号被封禁这次失败**不该**留着重试路径。
   * 否则用户点了「返回登录」、改了点什么又失败,重试会指向一个陈旧的 kind。
   */
  function backFromBlocked() {
    blocked.value = null
    lastKind = null
  }

  return {
    blocked,
    formError,
    retryable,
    loadingPwd,
    loadingCode,
    submitPassword,
    submitCode,
    retry,
    clearError,
    backFromBlocked,
  }
}

/** 发送验证码失败:不属于凭据类,但也要贴近表单(设计 §3) */
export function sendCodeFailMessage(e) {
  if (isNetworkFail(e)) return '网络好像断了，点这里重试'
  const code = authErrCode(e)
  if (code === AUTH_ERR.CODE_TOO_FREQUENT) return '验证码发得太频繁了，等一会儿再点'
  if (code === AUTH_ERR.CODE_LIMIT_REACHED) return '今天的验证码次数用完了，明天再来'
  return '验证码没发出去，稍后再试一次'
}
