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
        <el-form-item label="学号" prop="studentNo">
          <el-input
            v-model="form.studentNo"
            placeholder="13~14 位学号"
            maxlength="32"
            autocomplete="username"
            :aria-describedby="fieldErrors.studentNo ? 'studentno-err' : undefined"
          />
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

        <el-form-item label="邮箱（选填）" prop="email">
          <el-input
            v-model="form.email"
            placeholder="用于找回密码,绑定后更安全"
            autocomplete="email"
          />
          <p v-if="fieldErrors.email" class="field-error" role="alert">
            {{ fieldErrors.email }}
          </p>
        </el-form-item>

        <!--
          学校归属:与引导第 1 步共用 SchoolFields。
          只在用户填了 13/14 位学号时才出现(注册即认证);留空则只注册不认证。
        -->
        <el-form-item v-if="showSchoolFields" label="学校归属" :error="identityError">
          <SchoolFields v-model="identity" />
        </el-form-item>

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
import { reactive, ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'
import { IDENTITY_MODEL } from '@/constants/schoolDict'
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
  studentNo: '', nickname: '', password: '', email: '',
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

const rules = {
  studentNo: [
    { required: true, message: '请输入学号', trigger: 'blur' },
    {
      validator: (r, v, cb) => {
        const s = (v || '').trim()
        if (!/^\d+$/.test(s)) return cb(new Error('学号需为纯数字'))
        // 非空时长度必须 13 或 14 位
        if (s.length !== 13 && s.length !== 14) return cb(new Error('学号格式不正确'))
        // 末两位是班内序号,取值 00~69(镜像后端 CampusVerifyServiceImpl.MAX_CLASS_RANK=69)
        const lastTwo = Number(s.slice(-2))
        if (lastTwo > 69) return cb(new Error('学号格式不正确'))
        // 学院/本专科/年级/专业与学号是否对得上,一律交后端 1058 判定,不在本地拦。
        // 本地做归属交叉硬拦会逐条泄露学号编码规则(与后端 2026-10-19 安全修正矛盾),
        // 而且本地一拦提交就发不出、申诉出口出不来。归属不符统一走后端的模糊文案 + 申诉按钮。
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
  email: [
    {
      validator: (r, v, cb) => {
        const s = (v || '').trim()
        if (!s) return cb() // 选填,空不校验
        // 与后端 RegisterDTO 的邮箱格式判据保持一致
        if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)) cb()
        else cb(new Error('邮箱格式不正确'))
      },
      trigger: 'blur',
    },
  ],
}

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
  // 归属三项是必填(AC-18),没填满就别白发请求
  if (identityError.value) {
    ElMessage.warning(identityError.value)
    return
  }
  await submitRegister()
}
</script>

<style scoped src="./Register.css"></style>
