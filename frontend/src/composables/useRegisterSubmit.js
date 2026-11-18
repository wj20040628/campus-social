/**
 * 注册页的提交动作:提交注册、字段级错误分流。
 *
 * [账号体系 2026-10-21 / 2026-11-14]
 * 主账号是邮箱,邮箱验证码证明所有权;昵称选填,留空由服务端自动生成。
 * 注册页不再收集学号与学校归属（2026-11-14）,所以这里只发账号字段,
 * 也不再有学号相关的「去登录 / 申诉」出口。
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
 * @param {{ form:object, userStore:object, onDone:Function }} deps
 */
export function useRegisterSubmit({ form, userStore, onDone }) {
  const loading = ref(false)
  /** 表单级错误(网络 / 服务端)。字段能归因的错误不进这里 */
  const formError = ref('')
  /** 网络失败才可点重试 —— 凭据错点重试没有意义 */
  const retryable = ref(false)
  /** 字段级错误。key 与模板里的 v-if 一一对应 */
  const fieldErrors = reactive({
    email: '', emailCode: '', nickname: '', password: '',
  })

  function clearErrors() {
    for (const k of Object.keys(fieldErrors)) fieldErrors[k] = ''
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
      await userStore.register({
        email: (form.email || '').trim().toLowerCase(),
        emailCode: (form.emailCode || '').trim(),
        password: form.password,
        nickname: (form.nickname || '').trim(),
      })
      // 一次刷新拿到最新 userInfo
      await userStore.fetchMe()
      ElMessage.success('注册成功')
      // 注册成功直接进首页（2026-11-17）：新账号基准信誉分 60，
      // 发帖、评论、私信、建圈、建群、集市发布都已可用，不再先填引导。
      onDone()
    } catch (e) {
      const f = registerFieldError(e)
      if (f) {
        fieldErrors[f.field] = f.text
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
    submit,
    clearErrors,
  }
}
