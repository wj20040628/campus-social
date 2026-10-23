/**
 * 注册页的提交动作:提交注册、学校归属容错提交。
 *
 * [账号体系重构 2026-10-17]
 * 注册不再发 / 校验验证码(邮箱只是选填的安全凭证,绑定走单独的 bind-email 流程)。
 * 主账号是学号,昵称必填。所以本 composable 只管「发注册请求 + 字段级错误分流」,
 * 不再有 sendCode —— 对应的「获取验证码」按钮与倒计时也已从 Register.vue 移除。
 *
 * [为什么抽出来]
 * Register.vue 原有 243 行,补完设计 §3 的四条字段级错误后涨到 345 行,
 * 顶破了 300 行门禁。副作用整块挪到这里,页面只留结构与模型。
 *
 * [错误分流的原则]
 * 设计 §3 明确「所有错误都贴近字段,禁止汇总到顶部、禁止只弹 toast」。
 * 所以顺序是:**先试字段级,归不了类才做表单级,归不了类也不吞掉**。
 * request.js 的 toast 在这里是干扰项 —— 它会挡住用户正在点的输入框,
 * 而且一闪而过,用户根本来不及记住该改哪个字段。
 */
import { ref, reactive } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { registerFieldError, isNetworkFail } from '@/utils/authError'

/**
 * @param {{ form:object, identity:import('vue').Ref<object>, identityInvalid:import('vue').Ref<object>, userStore:object, onDone:Function }} deps
 */
export function useRegisterSubmit({ form, identity, identityInvalid, userStore, onDone }) {
  const loading = ref(false)
  /** 表单级错误(网络 / 服务端)。字段能归因的错误不进这里 */
  const formError = ref('')
  /** 网络失败才可点重试 —— 凭据错点重试没有意义 */
  const retryable = ref(false)
  /** 字段级错误。key 与模板里的 v-if 一一对应 */
  const fieldErrors = reactive({
    studentNo: '', nickname: '', email: '', password: '',
  })
  /** 学号是否已被注册。只有这个错才给「去登录」的出口 */
  const usernameTaken = ref(false)
  /**
   * 是否该给「申诉」出口（2026-10-19）。
   *
   * 1004(学号已被占用)与 1058(六层不过)都可能意味着「用户其实没填错」——
   * 字典过期、学号规则有例外、恰好被规则误伤。没有这条出路时,
   * 界面只回一句冷冰冰的「注册失败」，真实用户就只能放弃，
   * 而他恰恰是无辜的。申诉复用反馈模块，管理员人工核对。
   */
  const appealOpen = ref(false)

  function clearErrors() {
    for (const k of Object.keys(fieldErrors)) fieldErrors[k] = ''
    usernameTaken.value = false
    appealOpen.value = false
    formError.value = ''
    retryable.value = false
  }

  /**
   * 提交注册。字段校验已在页面的 el-form 层做完,这里只管发请求与错误分流。
   */
  async function submit() {
    // 按钮禁用有生效延迟,重入要在这里挡住。
    // 重复提交的后果比重复发码严重 —— 第二次会拿到 1003「已注册」,
    // 把第一次成功的注册报成失败,用户以为没注册成功就去换邮箱。
    if (loading.value) return
    clearErrors()
    loading.value = true
    try {
      // 只有填了 13/14 位学号才把归属一并提交(注册即认证);否则只注册不认证。
      // 后端 register 内部判断:带学号 + 归属则存归属并跑六层,不符整体事务回滚。
      const withProfile = !!(form.studentNo && /^\d{13,14}$/.test(form.studentNo.trim()))
      const identityPayload = withProfile ? identity.value : null
      await userStore.register({
        /*
         * 2026-10-21 账号体系调整：
         * - email 变成必填主键，emailCode 是它所有权的证明
         * - studentNo 选填；为空时不传（不传比传空串好，后端能少一次 trim 判断）
         */
        email: (form.email || '').trim().toLowerCase(),
        emailCode: (form.emailCode || '').trim(),
        password: form.password,
        nickname: (form.nickname || '').trim(),
        ...(withProfile ? { studentNo: form.studentNo.trim() } : {}),
        identity: identityPayload,
      })
      // 注册即认证已把归属 + 认证一并落库,一次刷新拿到最新 userInfo(含 verified)
      await userStore.fetchMe()
      ElMessage.success('注册成功')
      // AC-18:注册后进引导,不是直接进 Feed。
      // 直接进 Feed 的话引导守卫会把用户弹回 /onboarding,看起来像"点了没反应"
      onDone()
    } catch (e) {
      const f = registerFieldError(e)
      if (f) {
        fieldErrors[f.field] = f.text
        if (f.action === 'login') usernameTaken.value = true
        // 申诉出口:仅 1004/1058 带这个 action,其余字段错不给
        if (f.action === 'appeal') appealOpen.value = true
        return
      }
      retryable.value = isNetworkFail(e)
      formError.value = retryable.value ? '网络好像断了，点这里重试' : '注册没成功，再试一次'
    } finally {
      loading.value = false
    }
  }

  return {
    loading,
    formError,
    retryable,
    fieldErrors,
    usernameTaken,
    appealOpen,
    submit,
    clearErrors,
  }
}
