/**
 * 登录 / 注册的错误分类。
 *
 * [为什么需要这个文件]
 * 设计 §3 要求「所有错误都贴近字段,禁止汇总到顶部、禁止只弹 toast」,
 * 且给了四条明确文案。但 request.js 的拦截器对任何失败一律
 * `ElMessage.error(message)`,页面侧如果不分类,就只能把同一个后端 message
 * 原样回显 —— 于是出现「邮箱格式不正确」这种把内部术语抛给用户的话。
 *
 * 分类还解决第二个问题:**账号状态**。后端 AuthServiceImpl 在密码登录与
 * 验证码登录两条路径上都会先判 status 再校验密码:
 *   status=1 → USER_FROZEN(1001)   status=2 → USER_CANCELLED(1002)
 * 这两个码在 BusinessHttpStatus 里被映射成 **HTTP 403**(不是 200 + 业务码),
 * 所以码在 `error.response.data.code`,前端拿得到,判据是真实的、不是猜的。
 * 设计 §3 权限态要求「账号被封禁 → 整页替换」,没有这个分类就只能弹个 toast。
 *
 * [为什么不复用 dictError.js]
 * dictError 只认 1040/1041 两个字典码,这里是账号与凭据类,语义不同一组。
 * 共用的只有取码那一行,不值得为它引入耦合。
 */

/** 后端 ResultCode 中与登录/注册相关的码。与后端 common/ResultCode.java 一一对应。 */
export const AUTH_ERR = {
  USER_FROZEN: 1001,
  USER_CANCELLED: 1002,
  USERNAME_EXISTS: 1003,
  STUDENT_NO_EXISTS: 1004,
  WRONG_PASSWORD: 1005,
  CODE_INVALID: 1020,
  CODE_TOO_FREQUENT: 1021,
  CODE_LIMIT_REACHED: 1022,
  PHONE_NOT_REGISTERED: 1023,
  EMAIL_NOT_REGISTERED: 1024,
  SCHOOL_PROFILE_INCOMPLETE: 1039,
  STUDENT_NO_MISMATCH: 1058,
  // 账号体系重构:邮箱安全凭证
  EMAIL_ALREADY_BOUND: 1059,
  EMAIL_NOT_BOUND: 1060,
}

/**
 * 从任意写法的错误里取业务码。
 *
 * 两条路径都要试:
 *   - HTTP 4xx/5xx(1001/1003/1020 等已登记真实状态码的):原始 axios error,
 *     码在 error.response.data.code
 *   - HTTP 200 + code!=200:拦截器 reject 的是 `new Error(message)`,**码会丢失**,
 *     此时返回 null,调用方走通用文案(不会误判成「账号被封禁」)
 */
export function authErrCode(e) {
  const c = e?.response?.data?.code
  return typeof c === 'number' && Number.isFinite(c) && c > 0 ? c : null
}

/**
 * 这个账号是不是处于「登录不了」的状态。
 *
 * 冻结(1001)与注销(1002)都意味着:再输多少次密码都没用,
 * 所以不该继续展示输入框让人反复试 —— 设计 §3 要求整页替换。
 *
 * @returns {null | { code:number, title:string, reason:string }}
 *   title 是主句,reason 是副句(含用户下一步能做的事)
 */
export function accountBlocked(e) {
  const code = authErrCode(e)
  if (code === AUTH_ERR.USER_FROZEN) {
    return {
      code,
      title: '这个账号被暂停使用了',
      // 必须说清能做什么。设计 §3 明确「如果你觉得是误判,联系管理员申诉」——
      // 只说「被封禁」而不给出路,用户除了放弃没有第二个选项。
      reason: '如果觉得是误判,联系管理员申诉。管理员解封后就能正常登录。',
    }
  }
  if (code === AUTH_ERR.USER_CANCELLED) {
    return {
      code,
      title: '这个账号已经注销了',
      reason: '注销后数据不再保留。想继续用校园社交的话,重新注册一个账号就行。',
    }
  }
  return null
}

/**
 * 判断是不是「根本没连上服务器」类错误。
 *
 * 判据:axios 在网络层失败时**没有 response 字段**(ERR_NETWORK / ECONNABORTED /
 * 超时都属这类);有 response 说明请求到了服务端并拿到了状态码。
 * 超时也算在内 —— 30s 无响应对用户来说和断网没区别。
 *
 * @returns {boolean}
 */
export function isNetworkFail(e) {
  if (!e) return false
  if (e.response) return false
  // 有 message 的无 response 错误才是网络层;编程错误(如 TypeError)也会没 response,
  // 但那种错误 message 里是 JS 术语,让它走通用兜底比谎称「网络断了」好。
  return typeof e.message === 'string' && e.message.length > 0
}

/**
 * 凭据类错误的文案。
 *
 * 不回显后端原文:1005 的 message 是「用户名或密码错误」,但登录框里
 * 填的可能是邮箱也可能是手机号,说「用户名」会让用户去找用户名输入框。
 *
 * @param {Error} e
 * @param {'password'|'code'} kind 走密码登录还是验证码登录
 * @returns {string}
 */
export function credentialMessage(e, kind) {
  const code = authErrCode(e)
  if (kind === 'code') {
    if (code === AUTH_ERR.CODE_INVALID) return '验证码不对或者过期了，重新获取一次'
    return '验证码登录没成功，再试一次'
  }
  if (code === AUTH_ERR.WRONG_PASSWORD) return '学号或密码不对，再试一次'
  if (code === AUTH_ERR.EMAIL_NOT_REGISTERED) return '这个邮箱还没绑定账号'
  if (code === AUTH_ERR.EMAIL_NOT_BOUND) return '这个学号还没绑定邮箱，先去绑定邮箱再试'
  return '登录没成功，再试一次'
}

/**
 * 注册端的字段级错误。
 *
 * 设计 §3 把注册的错误拆到字段上,而不是一个顶部汇总:
 *   1003(USERNAME_EXISTS,账号体系重构后 username 即学号) → 学号字段,并给一个「去登录」的 inline 出口
 *   1004(STUDENT_NO_EXISTS) → 学号字段(同一个学号被别的账号绑了)
 *   1059(EMAIL_ALREADY_BOUND) → 邮箱字段
 *   1058(STUDENT_NO_MISMATCH) / 1039(SCHOOL_PROFILE_INCOMPLETE) → 学号字段(注册即认证的归属冲突)
 * 其余(网络/服务端)不适合按字段归因,交给调用方做表单级提示。
 * 注:注册已不发验证码,1020/1021/1022 的 verifyCode 分支仅历史兼容,注册流程不会触发。
 *
 * @returns {null | { field:'studentNo'|'email', text:string, action?:'login' }}
 */
export function registerFieldError(e) {
  const code = authErrCode(e)
  if (code === null) return null
  if (code === AUTH_ERR.USERNAME_EXISTS) {
    // 账号体系重构后 username 即学号,这里映射到学号字段 + 去登录出口
    return { field: 'studentNo', text: '这个学号已经注册过了，直接登录吧', action: 'login' }
  }
  if (code === AUTH_ERR.STUDENT_NO_EXISTS) {
    return { field: 'studentNo', text: '这个学号已经被别的账号绑了，换一个或者留空' }
  }
  if (code === AUTH_ERR.EMAIL_ALREADY_BOUND) {
    return { field: 'email', text: '这个邮箱已被其他账号绑定了' }
  }
  if (code === AUTH_ERR.STUDENT_NO_MISMATCH) {
    // 后端六层校验(纯数字/长度/年级/层次/专业编码/班内序号)任一不过的统一码。
    // 用后端具体 message(如「学号专业编码与所选专业不一致」),让用户知道改哪。
    return { field: 'studentNo', text: e.response?.data?.message || '学号与所选学校归属不一致，请核对' }
  }
  if (code === AUTH_ERR.SCHOOL_PROFILE_INCOMPLETE) {
    return { field: 'studentNo', text: '请完善学校归属（学院/年级/专业）后再提交学号' }
  }
  if (code === AUTH_ERR.CODE_INVALID) {
    return { field: 'verifyCode', text: '验证码不对或者过期了，重新获取一次' }
  }
  if (code === AUTH_ERR.CODE_TOO_FREQUENT) {
    return { field: 'verifyCode', text: '验证码发得太频繁了，等一会儿再点' }
  }
  if (code === AUTH_ERR.CODE_LIMIT_REACHED) {
    return { field: 'verifyCode', text: '今天的验证码次数用完了，明天再来' }
  }
  return null
}
