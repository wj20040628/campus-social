<template>
  <div class="auth-page">
    <div class="auth-card">
      <!--
        品牌区（2026-10-21 改版 B）。
        从「两行居中文字」改成「印鉴 + 左对齐文字」：
        产品名出自《诗经》「嘤其鸣矣，求其友声」，方形印章比抽象圆点更贴这个气质。
        印鉴里就一个「友」字，用系统字体渲染 —— 不需要任何图片资源。
        用字而不是图标：字体缺失时最多变丑，图标缺失会变成一个空框。
      -->
      <div class="auth-brand">
        <div class="auth-brand__seal" aria-hidden="true">友</div>
        <div class="auth-brand__txt">
          <p class="auth-brand__name">友声</p>
          <p class="auth-brand__slogan">嘤其鸣矣，求其友声</p>
        </div>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="onSubmit"
      >
        <!-- ================= 账号信息 ================= -->
        <div class="reg-card">
          <!--
            邮箱（2026-10-21 改为必填 + 验证码验证所有权）。
            它是登录主键之一 —— 学号 13~14 位记不住、填错改不了，
            且 SPEC AC-12 要求对外不得显示学号，不该同时充当登录名。
          -->
          <el-form-item label="邮箱" prop="email">
            <el-input
              v-model="form.email"
              placeholder="用来登录和找回密码"
              autocomplete="email"
            />
            <p v-if="fieldErrors.email" class="field-error" role="alert">
              {{ fieldErrors.email }}
            </p>
          </el-form-item>

        <el-form-item label="验证码" prop="emailCode">
          <div class="code-row">
            <el-input
              v-model="form.emailCode"
              placeholder="6 位数字"
              inputmode="numeric"
              maxlength="6"
              autocomplete="one-time-code"
            />
            <el-button
              class="code-row__btn"
              :disabled="codeCountdown > 0 || !emailLooksOk || sendingCode"
              :loading="sendingCode"
              @click="sendCode"
            >
              {{ codeCountdown > 0 ? `${codeCountdown}s` : '获取验证码' }}
            </el-button>
          </div>
          <p v-if="fieldErrors.emailCode" class="field-error" role="alert">
            {{ fieldErrors.emailCode }}
          </p>
        </el-form-item>

        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="form.nickname" placeholder="展示在站内的名字" maxlength="32" />
          <p v-if="fieldErrors.nickname" class="field-error" role="alert">
            {{ fieldErrors.nickname }}
          </p>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <PasswordInput
            v-model="form.password"
            placeholder="6~64 位"
            autocomplete="new-password"
          />
          <!-- 设计 §3 错误:密码太短。贴字段,不是顶部汇总 -->
          <p v-if="fieldErrors.password" class="field-error" role="alert">
            {{ fieldErrors.password }}
          </p>
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <PasswordInput
            v-model="form.confirmPassword"
            placeholder="再输一次密码"
            autocomplete="new-password"
          />
        </el-form-item>

        </div><!-- /.reg-card 账号 -->

        <!-- 同意协议勾选框（2026-10-20）：未勾选时按钮置灰 + onSubmit 兜底拦截；协议名可点打开弹窗 -->
        <el-form-item>
          <el-checkbox v-model="agreed" class="agree-box">
            <span class="agree-box__text">我已阅读并同意</span>
            <button type="button" class="legal-link" @click.stop.prevent="openLegal('user')">《用户协议》</button>
            <span class="agree-box__text">和</span>
            <button type="button" class="legal-link" @click.stop.prevent="openLegal('privacy')">《隐私政策》</button>
          </el-checkbox>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" native-type="submit" class="auth-submit" :loading="loading" :disabled="!agreed">
            注册并登录
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 表单级错误贴按钮下方、可点重试：网络失败时最不该让用户重填整表 -->
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
    </div>

    <LegalAgreementDialog ref="legalRef" />
  </div>
</template>

<script setup>
/**
 * 注册页(账号体系重构 2026-10-17)。
 *
 * 主账号是邮箱,昵称选填（不填由服务端自动生成）。
 * 学号与学校归属不在注册页收集，用户之后可在个人资料里补。
 */
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'
import { sendCode as sendVerifyCode } from '@/api/auth'
import { useRegisterSubmit } from '@/composables/useRegisterSubmit'
import { useCodeCountdown } from '@/composables/useCodeCountdown'
import PasswordInput from '@/components/auth/PasswordInput.vue'
import AuthErrorLine from '@/components/auth/AuthErrorLine.vue'
import LegalAgreementDialog from '@/components/auth/LegalAgreementDialog.vue'

// 协议弹窗 + 同意状态（2026-10-20）。未勾选时注册按钮置灰，onSubmit 再兜底拦截
const legalRef = ref(null)
const agreed = ref(false)
function openLegal(doc) { legalRef.value?.open(doc) }

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const form = reactive({
  email: '', emailCode: '', nickname: '', password: '', confirmPassword: '',
})

const {
  loading,
  formError,
  retryable,
  fieldErrors,
  submit: submitRegister,
} = useRegisterSubmit({
  form,
  userStore,
  onDone: () => router.replace('/'),
})

/**
 * 校验规则（2026-10-21 调整，2026-11-14 去掉学号）。
 *
 * 邮箱必填并加验证码；验证码固定 6 位数字；昵称选填，只限制长度。
 */
const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    {
      validator: (r, v, cb) => {
        const s = (v || '').trim()
        if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)) cb()
        else cb(new Error('邮箱格式不正确'))
      },
      trigger: 'blur',
    },
  ],
  emailCode: [
    { required: true, message: '请输入邮箱验证码', trigger: 'blur' },
    {
      validator: (r, v, cb) => (/^\d{6}$/.test((v || '').trim()) ? cb() : cb(new Error('验证码为 6 位数字'))),
      trigger: 'blur',
    },
  ],
  nickname: [
    // 选填：空值由服务端自动生成（「用户」+6 位随机），只限制长度
    { max: 32, message: '昵称最长 32 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码要 6 位以上', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再输一次密码', trigger: 'blur' },
    {
      validator: (r, v, cb) => ((v || '') === form.password ? cb() : cb(new Error('两次输入的密码不一致'))),
      trigger: 'blur',
    },
  ],
}

/* ---------------- 邮箱验证码（2026-10-21 新增） ---------------- */
// 倒计时复用 useCodeCountdown，与登录页同一实现（自写版漏了卸载清理）
const { cd: codeCountdown, sending: sendingCode, start: startCodeCountdown } = useCodeCountdown()

/** 邮箱格式看起来对，才允许点「获取验证码」—— 免得白发一次 */
const emailLooksOk = computed(() => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test((form.email || '').trim()))

async function sendCode() {
  if (!emailLooksOk.value || codeCountdown.value > 0 || sendingCode.value) return
  sendingCode.value = true
  formError.value = ''
  try {
    /*
     * sendCode(target, type) 是位置参数，不是对象。
     * type 传 "register"：与后端 sendCode 的默认值、以及注册时的 verify 调用一致，
     * 三处必须用同一个字符串，否则「发的码验不过」。
     */
    await sendVerifyCode(form.email.trim().toLowerCase(), 'register', { silent: true })
    ElMessage.success('验证码已发到邮箱')
    startCodeCountdown()
  } catch (e) {
    formError.value = e?.message || '验证码没发出去，稍后再试'
  } finally {
    sendingCode.value = false
  }
}

/**
 * 表单提交入口。
 *
 * 本地校验留在这里,发请求之后的事全在 useRegisterSubmit 里 ——
 * 这个分界与 Login.vue 一致。
 */
async function onSubmit() {
  // 兜底：按钮置灰仍可能被回车绕过，这里再拦一道
  if (!agreed.value) { ElMessage.warning('请先阅读并同意《用户协议》和《隐私政策》'); return }
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  await submitRegister()
}
</script>

<style scoped src="./Register.css"></style>
