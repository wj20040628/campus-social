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
        <!-- ================= 卡片一：账号（全部必填） ================= -->
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
            <p class="field-hint">邮箱和学号都能登录，也能用来找回密码</p>
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

        </div><!-- /.reg-card 账号 -->

        <!-- ================= 卡片二：校内身份（可跳过） =================
          把「可跳过」做成结构本身：这张卡带着「可选」角标，
          用户不需要读一条分隔线才明白下面这部分可以不填。
          代价是页面长一点 —— 换来的是判断成本从「读文案」降到「看角标」。
        -->
        <div class="reg-card">
          <p class="reg-card__h">
            <i class="reg-card__sq reg-card__sq--muted" />校内身份
            <span class="reg-card__tag">可选</span>
          </p>

          <!--
            学号（2026-10-21 改为选填）。
            填了走六层校验、立即认证；不填则注册为未认证，
            之后在引导页补（未认证时不能发私信/建圈子/加圈子/发集市）。
          -->
          <el-form-item label="学号" prop="studentNo">
            <el-input
              v-model="form.studentNo"
             
              maxlength="32"
              autocomplete="username"
              :aria-describedby="fieldErrors.studentNo ? 'studentno-err' : undefined"
            />
            <p class="field-hint">认证后才能发私信、建圈子、加入圈子、发布集市活动</p>
            <!--
              设计 §3 错误:学号已被注册。
              除了文案还要给一个「去登录」的**内联**出口 ——
              发现学号已注册时,最优出路是去登录而不是重新注册,
              让用户在两页之间自己找是设计想避免的。
            -->
            <p v-if="fieldErrors.studentNo" id="studentno-err" class="field-error" role="alert">
              {{ fieldErrors.studentNo }}
              <router-link v-if="usernameTaken" to="/login/form" class="field-error__link">
                去登录
              </router-link>
              <!-- 申诉出口（2026-10-19）：被自己的规则挡在外面时用户没有出路 -->
              <button
                v-if="appealOpen"
                type="button"
                class="field-error__link field-error__link--btn"
                @click="appealRef?.open()"
              >我确实是本校学生，提交申诉</button>
            </p>
          </el-form-item>

          <StudentNoAppealDialog ref="appealRef" :preset-student-no="form.studentNo" />

          <!--
            学校归属:与引导第 1 步共用 SchoolFields。
            只在用户填了 13/14 位学号时才出现(注册即认证);留空则只注册不认证。
          -->
          <el-form-item v-if="showSchoolFields" label="学校归属" :error="identityError">
            <SchoolFields v-model="identity" />
          </el-form-item>
        </div><!-- /.reg-card 校内身份 -->

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
    </div>

    <!-- 协议弹窗：由勾选框里的《用户协议》/《隐私政策》链接调起 -->
    <LegalAgreementDialog ref="legalRef" />
  </div>
</template>

<script setup>
/**
 * 注册页(账号体系重构 2026-10-17)。
 *
 * 主账号是学号,昵称必填,邮箱选填(只作安全凭证,绑定走单独流程)。
 * 注册不再发 / 校验验证码 —— 字段顺序即动作链:学号 → 昵称 → 密码 → 邮箱,
 * 学校归属在填了合法学号时才展开(注册即认证)。
 *
 * 学院 / 年级是字典下拉而非自由文本:自由文本会让「计算机学院」与「计科」
 * 算两个院系,同院系推荐(AC-14)漏人。
 */
import { reactive, ref, computed, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'
import { IDENTITY_MODEL } from '@/constants/schoolDict'
import { sendCode as sendVerifyCode } from '@/api/auth'
import { useRegisterSubmit } from '@/composables/useRegisterSubmit'
import SchoolFields from '@/components/common/SchoolFields.vue'
import PasswordInput from '@/components/auth/PasswordInput.vue'
import AuthErrorLine from '@/components/auth/AuthErrorLine.vue'
import StudentNoAppealDialog from '@/components/auth/StudentNoAppealDialog.vue'
import LegalAgreementDialog from '@/components/auth/LegalAgreementDialog.vue'

const appealRef = ref(null)

// 协议弹窗 + 同意状态（2026-10-20）。未勾选时注册按钮置灰，onSubmit 再兜底拦截
const legalRef = ref(null)
const agreed = ref(false)
function openLegal(doc) { legalRef.value?.open(doc) }

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const form = reactive({
  email: '', emailCode: '', nickname: '', password: '', studentNo: '',
})

/** 学校归属。与引导第 1 步同一个模型,{ schoolId, deptId, gradeId } */
const identity = ref({ ...IDENTITY_MODEL })
/** 后端 1040/1041 判定的无效字段 */
const identityInvalid = ref({})

const {
  loading,
  formError,
  retryable,
  fieldErrors,
  usernameTaken,
  appealOpen,
  submit: submitRegister,
} = useRegisterSubmit({
  form,
  identity,
  identityInvalid,
  userStore,
  onDone: () => router.push('/onboarding'),
})

/**
 * 学校归属显隐:用户填到 13/14 位合法学号时才展开(注册即认证)。
 * 否则归属区隐藏,注册只建账号不认证。
 */
const showSchoolFields = computed(() => /^\d{13,14}$/.test(form.studentNo || ''))

// 学号被清空或变短(不再满足 13/14 位)时,把已选归属清掉,
// 避免脏数据随提交带上去(此时归属区本就隐藏,不该提交)。
watch(
  () => form.studentNo,
  (val) => {
    if (!/^\d{13,14}$/.test(val || '')) {
      identity.value = { ...IDENTITY_MODEL }
      identityInvalid.value = {}
    }
  },
)

/**
 * 校验规则（2026-10-21 调整）。
 *
 * 学号从必填改为选填 —— 只在填了内容时才校验格式；
 * 邮箱从选填改为必填并加验证码；验证码固定 6 位数字。
 *
 * 归属与学号的交叉比对一律交后端 1058 判定，本地不拦：
 * 本地硬拦会逐条泄露学号编码规则（与后端 2026-10-19 安全修正矛盾），
 * 而且本地一拦提交就发不出、申诉出口也出不来。
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
  studentNo: [
    {
      // 选填：空值合法，不触发下面的格式校验
      validator: (r, v, cb) => {
        const s = (v || '').trim()
        if (!s) return cb()
        if (!/^\d+$/.test(s)) return cb(new Error('学号需为纯数字'))
        if (s.length !== 13 && s.length !== 14) return cb(new Error('学号格式不正确'))
        // 末两位是班内序号，取值 00~69（镜像后端 MAX_CLASS_RANK=69）
        if (Number(s.slice(-2)) > 69) return cb(new Error('学号格式不正确'))
        cb()
      },
      trigger: 'blur',
    },
  ],
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 32, message: '昵称最长 32 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码要 6 位以上', trigger: 'blur' },
  ],
}

/* ---------------- 邮箱验证码（2026-10-21 新增） ---------------- */
const sendingCode = ref(false)
const codeCountdown = ref(0)
let countdownTimer = null

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
    await sendVerifyCode(form.email.trim().toLowerCase(), 'register')
    ElMessage.success('验证码已发到邮箱')
    codeCountdown.value = 60
    countdownTimer = setInterval(() => {
      codeCountdown.value -= 1
      if (codeCountdown.value <= 0) clearInterval(countdownTimer)
    }, 1000)
  } catch (e) {
    formError.value = e?.message || '验证码没发出去，稍后再试'
  } finally {
    sendingCode.value = false
  }
}

onBeforeUnmount(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})

/**
 * 学校归属的错误提示。
 * 设计要求:所有错误贴近字段,不汇总到顶部、不只弹 toast。
 * 学校归属没填满时给出原因,而不是让用户点提交后毫无反馈。
 */
const identityError = computed(() => {
  // 服务端判定的无效字段优先 —— 本地五项都填了也不能盖掉它
  if (identityInvalid.value.dept) return identityInvalid.value.dept
  if (identityInvalid.value.grade) return identityInvalid.value.grade
  if (identityInvalid.value.level) return identityInvalid.value.level
  if (identityInvalid.value.major) return identityInvalid.value.major
  const i = identity.value
  if (!i.schoolId) return '选一个学校'
  if (!i.deptId) return '选一个学院'
  if (!i.level) return '选一个本专科'
  if (!i.gradeId) return '选一个年级'
  if (!i.majorId) return '选一个专业'
  return ''
})

/**
 * 表单提交入口。
 *
 * 本地校验与「归属没填满」的短路留在这里,发请求之后的事全在
 * useRegisterSubmit 里 —— 这个分界与 Login.vue 一致。
 */
async function onSubmit() {
  // 兜底：按钮置灰仍可能被回车绕过，这里再拦一道
  if (!agreed.value) { ElMessage.warning('请先阅读并同意《用户协议》和《隐私政策》'); return }
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  /*
   * 归属只在「填了学号(注册即认证)」时才必填(2026-10-24 修)。
   * 此前无条件校验 identityError,而归属表单只在填学号后才渲染——
   * 「学号可选」的用户看不到归属控件,却被「选一个学院」永远拦死,
   * PRD 的主路径(先注册、引导页再认证)整个走不通。
   */
  if (showSchoolFields.value && identityError.value) {
    ElMessage.warning(identityError.value)
    return
  }
  await submitRegister()
}
</script>

<style scoped src="./Register.css"></style>
