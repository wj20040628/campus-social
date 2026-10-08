/**
 * 注册页的提交动作:发验证码、提交注册、学校归属容错提交。
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
import { sendCode as sendCodeApi } from '@/api/auth'
import { dictWriteError, applyDictInvalid } from '@/utils/dictError'
import { registerFieldError, isNetworkFail } from '@/utils/authError'
import { sendCodeFailMessage } from '@/composables/useLoginSubmit'

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
    username: '', studentNo: '', verifyCode: '', password: '',
  })
  /** 邮箱是否已被注册。只有这个错才给「去登录」的出口 */
  const usernameTaken = ref(false)

  function clearErrors() {
    for (const k of Object.keys(fieldErrors)) fieldErrors[k] = ''
    usernameTaken.value = false
    formError.value = ''
    retryable.value = false
  }

  async function sendCode({ sending, startCd }) {
    // 防重入。el-button 的 :loading 会禁用按钮,但网络慢时用户在
    // disabled 生效前的那一瞬还能点下去 —— 结果是后端收到两条发码请求,
    // 第二次大概率撞上 1021 限频,用户看到的是「刚点的这次失败了」。
    if (sending.value) return
    const isEmail = form.username.includes('@') && form.username.split('@').length === 2
    const isPhone = /^1[3-9]\d{9}$/.test(form.username)
    if (!isEmail && !isPhone) {
      // 贴到邮箱字段,不弹 toast(设计 §3「禁止只弹 toast」)
      fieldErrors.username = '先填合法的邮箱或手机号'
      return
    }
    clearErrors()
    sending.value = true
    try {
      await sendCodeApi(form.username, 'register')
      ElMessage.success('验证码已发送（开发模式见后端控制台）')
      startCd()
    } catch (e) {
      // 发码失败归到验证码字段:用户下一步要动的是它,不是邮箱
      const f = registerFieldError(e)
      if (f) {
        fieldErrors[f.field] = f.text
        return
      }
      retryable.value = isNetworkFail(e)
      formError.value = sendCodeFailMessage(e)
    } finally {
      sending.value = false
    }
  }

  /**
   * 提交注册。字段校验已在页面的 el-form 层做完,这里只管发请求与错误分流。
   */
  async function submit() {
    // 同上:按钮禁用有生效延迟,重入要在这里挡住。
    // 重复提交的后果比重复发码严重 —— 第二次会拿到 1003「已注册」,
    // 把第一次成功的注册报成失败,用户以为没注册成功就去换邮箱。
    if (loading.value) return
    clearErrors()
    loading.value = true
    try {
      await userStore.register(
        form.username,
        form.password,
        form.studentNo || undefined,
        form.verifyCode,
        form.nickname || undefined,
      )
      await userStore.fetchMe()
      // 归属一起提交,避免注册后再走一遍引导第 1 步
      await saveSchoolProfileSafe(identity.value)
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
        return
      }
      retryable.value = isNetworkFail(e)
      formError.value = retryable.value ? '网络好像断了，点这里重试' : '注册没成功，再试一次'
    } finally {
      loading.value = false
    }
  }

  /**
   * 学校归属提交失败**不阻断注册成功**。
   *
   * 用户已经拿到账号了,只丢了一个选填的归属字段 —— 为此把整个注册流程报失败
   * 会让用户以为注册没成功、然后重复注册(学号会被占用)。
   * 归属没存上的后果只是引导页第 1 步要重填一次,这个代价小得多。
   *
   * 但**字典失效**(1040/1041)要落到字段上:本地校验全过、只有服务端知道
   * 该学院已停用。不提示的话用户进引导页会再选一次同一个无效项。
   */
  async function saveSchoolProfileSafe(payload) {
    try {
      const { saveSchoolProfile } = await import('@/api/dict')
      await saveSchoolProfile(payload)
    } catch (e) {
      const bad = dictWriteError(e)
      if (bad) {
        // applyDictInvalid 同时做两件事:清掉已失效的 id,并返回该字段的提示文案。
        // 只调一次 —— 它的第二个作用是**就地改 payload**,调两次会拿到两个不同结果,
        // 而且第一次已经把 identity 改掉了。
        const hints = applyDictInvalid(identity.value, bad)
        identityInvalid.value = { ...identityInvalid.value, ...hints }
        // 不说的话就是「注册成功 → 跳引导 → 第 1 步又卡住」,用户会以为注册坏了
        ElMessage.warning(`${bad.hint},注册已成功,进引导后重选一下就行`)
        return
      }
      ElMessage.warning('学校归属没存上，进引导后补一下就行')
    }
  }

  return {
    loading,
    formError,
    retryable,
    fieldErrors,
    usernameTaken,
    sendCode,
    submit,
    clearErrors,
  }
}
