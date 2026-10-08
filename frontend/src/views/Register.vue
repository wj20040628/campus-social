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
          <el-input v-model="form.username" placeholder="you@qq.com 或 13800000000" autocomplete="username" />
        </el-form-item>

        <el-form-item label="验证码" prop="verifyCode">
          <div class="code-row">
            <el-input v-model="form.verifyCode" maxlength="6" placeholder="6位验证码" />
            <el-button
              :disabled="sending || cd > 0"
              :loading="sending"
              @click="sendCode"
            >
              {{ cd > 0 ? `${cd}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="6~64 位"
            autocomplete="new-password"
          />
        </el-form-item>

        <el-form-item label="学号（选填）" prop="studentNo">
          <el-input v-model="form.studentNo" placeholder="如 2024001234" maxlength="32" />
          <p class="field-hint">填了能更快通过校内认证；用校园邮箱注册可以自动认证。</p>
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
import { reactive, ref, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { sendCode as sendCodeApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { IDENTITY_MODEL } from '@/constants/schoolDict'
import { dictWriteError, applyDictInvalid } from '@/utils/dictError'
import SchoolFields from '@/components/common/SchoolFields.vue'

const router = useRouter()
const userStore = useUserStore()

const isDev = import.meta.env.DEV

const formRef = ref()
const loading = ref(false)
const sending = ref(false)
const cd = ref(0)           // 倒计时秒
let cdTimer = null
const form = reactive({
  username: '', password: '', studentNo: '', nickname: '', verifyCode: '',
})

/** 学校归属。与引导第 1 步同一个模型,{ schoolId, deptId, gradeId } */
const identity = ref({ ...IDENTITY_MODEL })
/** 后端 1040/1041 判定的无效字段 */
const identityInvalid = ref({})

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

async function sendCode() {
  const isEmail = form.username.includes('@') && form.username.split('@').length === 2
  const isPhone = /^1[3-9]\d{9}$/.test(form.username)
  if (!isEmail && !isPhone) {
    ElMessage.warning('先填合法的邮箱或手机号')
    return
  }
  sending.value = true
  try {
    await sendCodeApi(form.username, 'register')
    ElMessage.success('验证码已发送（开发模式见后端控制台）')
    cd.value = 60
    cdTimer = setInterval(() => {
      cd.value--
      if (cd.value <= 0) clearInterval(cdTimer)
    }, 1000)
  } catch {
    // request.js 已弹错
  } finally {
    sending.value = false
  }
}

async function onSubmit() {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  if (identityError.value) {
    ElMessage.warning(identityError.value)
    return
  }
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
    router.push('/onboarding')
  } catch {
    // request.js 已弹错
  } finally {
    loading.value = false
  }
}

/**
 * 学校归属提交失败**不阻断注册成功**。
 * 用户已经拿到账号了,只丢了一个选填的归属字段 —— 为此把整个注册流程报失败
 * 会让用户以为注册没成功、然后重复注册(真实姓名与学号会被占用)。
 * 归属没存上的后果只是引导页第1 步要重填一次,这个代价小得多。
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
      // 清掉已失效的 id:它多半已不在新字典里,留着会在下拉里显示成一个裸数字
      identityInvalid.value = applyDictInvalid(identity.value, bad)
      // 注册页不强制刷字典(引导页会拉,这里拦住会拖慢注册),但**必须说清后果**:
      // 不说的话就是「注册成功 → 跳引导 → 第 1 步又卡住」,用户会以为注册坏了。
      ElMessage.warning(`${bad.hint},注册已成功,进引导后重选一下就行`)
      return
    }
    ElMessage.warning('学校归属没存上，进引导后补一下就行')
  }
}

onUnmounted(() => {
  if (cdTimer) clearInterval(cdTimer)
})
</script>

<style scoped src="./Register.css"></style>