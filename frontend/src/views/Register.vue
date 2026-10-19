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
    </div>
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
import { useDictStore } from '@/stores/dict'
import { IDENTITY_MODEL, matchStudentNoDept, matchStudentNoLevel } from '@/constants/schoolDict'
import { useRegisterSubmit } from '@/composables/useRegisterSubmit'
import SchoolFields from '@/components/common/SchoolFields.vue'
import PasswordInput from '@/components/auth/PasswordInput.vue'
import AuthErrorLine from '@/components/auth/AuthErrorLine.vue'
import StudentNoAppealDialog from '@/components/auth/StudentNoAppealDialog.vue'

const appealRef = ref(null)

const router = useRouter()
const userStore = useUserStore()
const dict = useDictStore()

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
 * 学院归属在表单里排在学号下方,用户可能先填学号再选学院。
 * 学生号 validator 只在 blur 时跑一次,那时 deptId 可能还空着 →
 * 3-6 位交叉校验被跳过。这里在 deptId 变化时重跑一次学号校验,
 * 保证无论填写顺序如何,学院与学号大类对不上都能拦下来。
 */
watch(
  () => [identity.value.deptId, identity.value.level],
  () => {
    if (form.studentNo && formRef.value) {
      formRef.value.validateField('studentNo').catch(() => {})
    }
  },
)

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
        // 与后端六层校验一致:非空时长度必须 13 或 14 位
        if (s.length !== 13 && s.length !== 14) return cb(new Error('学号格式不正确'))
        // 学号末两位是班内序号,取值 00~69(镜像后端 CampusVerifyServiceImpl.MAX_CLASS_RANK=69)
        const lastTwo = Number(s.slice(-2))
        if (lastTwo > 69) return cb(new Error('学号格式不正确'))
        // 学号位 3-6 = 专业大类编码,需与所选学院对应(镜像后端认证交叉校验)。
        // 学院名从字典下拉取:取不到 / 大类未收录时返回 null,不拦,交后端判定。
        const deptName = dict.deptOptions.find((d) => d.id === identity.value.deptId)?.name
        const matched = matchStudentNoDept(s, deptName)
        if (matched === false) {
          return cb(new Error('学号 6 位专业代码与你所选学院不一致，请核对学号或学院'))
        }
        // 学号层次标记(位 11-12:本科 05/5、专科 06/6)需与所选本专科一致。
        // 选了本科却填了专科开头的学号,基本是学院或本专科选错,提前拦下。
        // 未选本专科 / 学号长度不符则无法判定,返回 null 不拦,交后端。
        const levelHit = matchStudentNoLevel(s, identity.value.level)
        if (levelHit === false) {
          return cb(new Error('学号层次（本科/专科）与你选择的本专科不一致，请核对学号或本专科'))
        }
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
