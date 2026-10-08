<template>
  <div class="auth-page">
    <div class="auth-card">
      <!--
        品牌区。移动端在卡外(纯底色),桌面端由 .auth-card 提供卡片容器。
        渐变背景已删(AI模板味 + 硬编码色 + 移动端合成层开销,见 Login.vue.md §1.2)。
      -->
      <div class="auth-brand">
        <p class="auth-brand__name">友声</p>
        <p class="auth-brand__slogan">嘤其鸣矣，求其友声</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <el-form-item label="邮箱/手机号" prop="username">
          <el-input
            v-model="form.username"
            placeholder="you@qq.com 或 13800000000"
            autocomplete="username"
            :aria-describedby="fieldErrors.username ? 'username-err' : undefined"
          />
          <!--
            设计 §3 错误 1):邮箱已被注册。
            除了文案还要给一个「去登录」的**内联**出口 ——
            发现邮箱已注册时,最优出路是去登录而不是重新注册,
            让用户在两页之间自己找是设计想避免的。
          -->
          <p v-if="fieldErrors.username" id="username-err" class="field-error" role="alert">
            {{ fieldErrors.username }}
            <router-link v-if="usernameTaken" to="/login/form" class="field-error__link">
              去登录
            </router-link>
          </p>
        </el-form-item>

        <el-form-item label="验证码" prop="verifyCode">
          <div class="code-row">
            <el-input
              v-model="form.verifyCode"
              maxlength="6"
              placeholder="6位验证码"
              inputmode="numeric"
            />
            <!-- aria-live 挂在独立 span 上,倒计时能被读屏播报(设计 §4) -->
            <el-button
              :disabled="sending || cd > 0"
              :loading="sending"
              aria-disabled="sending || cd > 0"
              @click="sendCode"
            >
              获取验证码
              <span class="sr-only" aria-live="polite">{{ cdHint }}</span>
            </el-button>
          </div>
          <p v-if="cd > 0" class="code-cd" aria-hidden="true">{{ cd }}s 后重发</p>
          <p v-if="fieldErrors.verifyCode" class="field-error" role="alert">
            {{ fieldErrors.verifyCode }}
          </p>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <PasswordInput
            v-model="form.password"
            placeholder="6~64 位"
            autocomplete="new-password"
          />
          <!-- 设计 §3 错误 3):密码太短。贴字段,不是顶部汇总 -->
          <p v-if="fieldErrors.password" class="field-error" role="alert">
            {{ fieldErrors.password }}
          </p>
        </el-form-item>

        <el-form-item label="学号（选填）" prop="studentNo">
          <el-input
            v-model="form.studentNo"
            placeholder="如 2024001234"
            maxlength="32"
            aria-describedby="student-no-hint"
          />
          <!--
            学号的提示不是错误,用 meta 色;设计 §5 要求它被 aria-describedby
            关联到输入框,读屏聚焦学号时会一起念出来。
          -->
          <p id="student-no-hint" class="field-hint">填了能更快通过校内认证；用校园邮箱注册可以自动认证。</p>
          <p v-if="fieldErrors.studentNo" class="field-error" role="alert">
            {{ fieldErrors.studentNo }}
          </p>
        </el-form-item>

        <el-form-item label="昵称（选填）" prop="nickname">
          <el-input v-model="form.nickname" placeholder="不填就用账号" maxlength="32" />
        </el-form-item>

        <!--
          学校归属:与引导第 1 步共用 SchoolFields。
          注册时先填一次,引导页会带回填值,不用填两遍。
        -->
        <el-form-item label="学校归属" :error="identityError">
          <SchoolFields v-model="identity" />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" native-type="submit" class="auth-submit" :loading="loading">
            注册并登录
          </el-button>
        </el-form-item>
      </el-form>

      <!--
        表单级错误(网络失败 / 服务端错误)。设计 §3 错误 4):
        「网络好像断了,点这里重试」,「点这里」可点 ——
        纯文本的话用户只能把整个表单重填一遍,而网络失败恰恰最不该让人重填。
      -->
      <AuthErrorLine
        v-if="formError"
        :message="formError"
        :retryable="retryable"
        @retry="onSubmit"
      />

      <div class="auth-link">
        已有账号？
        <router-link to="/login/form">去登录</router-link>
      </div>
      <!-- 仅开发模式提示:生产环境出现「验证码打后端控制台」是把内部实现漏给用户 -->
      <div v-if="isDev" class="auth-tip">开发模式：验证码打后端控制台；prod 模式发到填的邮箱</div>
    </div>
  </div>
</template>

<script setup>
/**
 * 注册页(Register.vue.md)
 *
 * 字段顺序遵循设计的「动作链」理由:验证码紧接邮箱(填邮箱 → 点获取 → 填验证码),
 * 把密码插在中间会打断这个动作链。学号/昵称标为选填。
 *
 * 学院 / 年级是字典下拉而非自由文本:自由文本会让「计算机学院」与「计科」
 * 算两个院系,同院系推荐(AC-14)漏人。
 */
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'
import { IDENTITY_MODEL } from '@/constants/schoolDict'
import { useCodeCountdown } from '@/composables/useCodeCountdown'
import { useRegisterSubmit } from '@/composables/useRegisterSubmit'
import SchoolFields from '@/components/common/SchoolFields.vue'
import PasswordInput from '@/components/auth/PasswordInput.vue'
import AuthErrorLine from '@/components/auth/AuthErrorLine.vue'

const router = useRouter()
const userStore = useUserStore()

const isDev = import.meta.env.DEV

const formRef = ref()
const form = reactive({
  username: '', password: '', studentNo: '', nickname: '', verifyCode: '',
})

/** 学校归属。与引导第 1 步同一个模型,{ schoolId, deptId, gradeId } */
const identity = ref({ ...IDENTITY_MODEL })
/** 后端 1040/1041 判定的无效字段 */
const identityInvalid = ref({})

const { cd, sending, start: startCd } = useCodeCountdown()

const {
  loading,
  formError,
  retryable,
  fieldErrors,
  usernameTaken,
  sendCode: sendCodeAction,
  submit: submitRegister,
} = useRegisterSubmit({
  form,
  identity,
  identityInvalid,
  userStore,
  onDone: () => router.push('/onboarding'),
})

function sendCode() {
  sendCodeAction({ sending, startCd })
}

/** 倒计时读屏播报文本。每 10 秒换一次,避免每秒打断(设计 §4) */
const cdHint = computed(() => {
  const s = cd.value
  if (s === 0) return '可以重新获取验证码'
  if (s % 10 === 0 || s <= 3) return `${s} 秒后可以重新获取验证码`
  return ''
})

const rules = {
  username: [
    { required: true, message: '请输入邮箱或手机号', trigger: 'blur' },
    {
      validator: (r, v, cb) => {
        const isEmail = v.includes('@') && v.split('@').length === 2
        const isPhone = /^1[3-9]\d{9}$/.test(v)
        if (isEmail || isPhone) cb()
        else cb(new Error('需为合法邮箱或 11 位手机号'))
      },
      trigger: 'blur',
    },
  ],
  verifyCode: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码要 6 位以上', trigger: 'blur' },
  ],
}

/**
 * 学校归属的错误提示。
 * 设计要求:所有错误贴近字段,不汇总到顶部、不只弹 toast。
 * 学校归属没填满时给出原因,而不是让用户点提交后毫无反馈。
 */
const identityError = computed(() => {
  // 服务端判定的无效字段优先 —— 本地三项都填了也不能盖掉它
  if (identityInvalid.value.dept) return identityInvalid.value.dept
  if (identityInvalid.value.grade) return identityInvalid.value.grade
  const i = identity.value
  if (!i.schoolId) return '选一个学校'
  if (!i.deptId) return '选一个学院'
  if (!i.gradeId) return '选一个年级'
  return ''
})

/**
 * 表单提交入口。
 *
 * 本地校验与「归属没填满」的短路留在这里,发请求之后的事全在
 * useRegisterSubmit 里 —— 这个分界与 Login.vue 一致。
 */
async function onSubmit() {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  // 归属三项是必填(AC-18),没填满就别白发请求
  if (identityError.value) {
    ElMessage.warning(identityError.value)
    return
  }
  await submitRegister()
}
</script>

<style scoped src="./Register.css"></style>