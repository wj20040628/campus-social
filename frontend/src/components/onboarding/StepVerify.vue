<template>
  <!--
    引导第 1 步：学校归属 + 学号认证（2026-10-21 新增）。

    【为什么加这一步】改版后注册只要求邮箱，学号变成选填 ——
    没填学号的人注册完就是「未认证」，而未认证不能发私信、建圈子、加圈子、发集市。
    这一步就是那条「补认证」的路径。

    【为什么把归属和学号放在同一步，而不是拆成两步】
    后端 submitStudentNo 强依赖学校归属：没有院系/年级/专业就没法做六层校验
    （学号里的年级、层次、专业编码都要与归属比对）。
    拆成两步会让用户先选归属、再去下一步填学号，中间仍然跳不过任何东西；
    放同一步反而能让「改归属 → 重填学号」在一个屏内完成。

    【为什么两个按钮是两个独立动作】
    「保存归属」调 /verify/school-profile，「提交认证」调 /verify/student-no。
    前者即使学号没填也该能保存（用户可能只想先把归属填了），
    后者要求归属已存在 —— 后端会拦并返回 SCHOOL_PROFILE_INCOMPLETE。
  -->
  <section class="sv">
    <h2 class="sv__title">完善校内身份</h2>
    <p class="sv__sub">认证后才能发私信、建圈子、加入圈子、发布集市活动</p>

    <!-- 学校归属：复用全站的 SchoolFields（学校/学院/本专科/专业/年级 五个字段） -->
    <SchoolFields v-model="identity" />

    <button
      type="button"
      class="sv__save"
      :disabled="savingProfile || !identityComplete"
      @click="saveProfile"
    >
      {{ savingProfile ? '保存中…' : '保存学校归属' }}
    </button>

    <p v-if="profileSaved" class="sv__ok">学校归属已保存，可以填学号了</p>

    <!-- 学号：归属保存后才可用 -->
    <div class="sv__sep" />

    <label class="sv__label" for="sv-no">学号</label>
    <input
      id="sv-no"
      v-model.trim="studentNo"
      class="sv__input"
      inputmode="numeric"
      maxlength="14"
      placeholder="13~14 位学号"
    />
    <p class="sv__hint">会与你上面选的学院、本专科、专业、年级逐项比对</p>

    <!--
      姓名后 1~2 位（2026-10-26 补）：
      后端 StudentNoVerifyDTO.nameTail 是 @NotBlank，人工核验要拿它比对。
      没有这个输入框时点「提交认证」必然 400「请填写姓名后 1 到 2 位」。
    -->
    <label class="sv__label" for="sv-name-tail">姓名后 1~2 位</label>
    <input
      id="sv-name-tail"
      v-model.trim="nameTail"
      class="sv__input"
      maxlength="4"
      placeholder="如「三」或「娜娜」"
      autocomplete="off"
    />
    <p class="sv__hint">用于管理员人工核验，不会公开展示</p>

    <button
      type="button"
      class="sv__submit"
      :disabled="submitting || !canSubmit"
      @click="submit"
    >
      {{ submitting ? '校验中…' : '提交认证' }}
    </button>

    <p v-if="error" class="sv__err" role="alert">{{ error }}</p>
    <!--
      申诉出口（2026-10-26 补）：学号唯一冲突(1004)与六层不过(1058)都可能是
      "字典过期/规则误伤"，不是用户填错。没有这条出路，被挡住的真实学生只能放弃。
      复用全站学号申诉弹层（公开端点，无需登录）。
    -->
    <p v-if="appealOpen" class="sv__appeal">
      确认信息无误但被挡住？
      <button type="button" class="sv__appeal-btn" @click="appealRef?.open()">提交人工申诉</button>
    </p>
    <StudentNoAppealDialog ref="appealRef" v-model="appealOpen" :preset-student-no="studentNo" />
  </section>
</template>

<script setup>
/**
 * 引导第 1 步：学校归属 + 学号认证。
 *
 * 数据与提交都就近处理（不像 StepTags/StepFollow 把状态放在父组件）：
 * 这一步的「归属」与「学号」是一个整体动作，父组件不需要知道中间状态，
 * 只需要在认证成功后继续下一步。父组件通过 @verified 事件得知结果。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { saveSchoolProfile } from '@/api/dict'
import { useDictStore } from '@/stores/dict'
import StudentNoAppealDialog from '@/components/auth/StudentNoAppealDialog.vue'
import { useVerifyStatus } from '@/composables/useVerifyStatus'
import { submitStudentNo } from '@/api/verify'
import SchoolFields from '@/components/common/SchoolFields.vue'

const emit = defineEmits(['verified'])

/**
 * 学校归属与父页面双向绑定（2026-10-26 修）。
 *
 * 原实现是组件内 `ref({...})`，而父页面 Onboarding.vue 另有一份 identity。
 * 用户在这里点「保存学校归属」只写进了子组件的局部状态，父页面那份仍为 null，
 * 于是点「下一步」调用 /onboarding/complete 时后端回 400
 * 「请选择年级 / 请选择专业 / 请选择学院」——这正是手机端截图里的第二个报错。
 */
const identity = defineModel({ type: Object, required: true })
const dict = useDictStore()
const { load: reloadVerifyStatus } = useVerifyStatus()

const studentNo = ref('')
const nameTail = ref('')
const savingProfile = ref(false)
const profileSaved = ref(false)
const submitting = ref(false)
const error = ref('')
const appealOpen = ref(false)
const appealRef = ref(null)

/**
 * 归属是否填全。
 *
 * 后端 SchoolProfileDTO 只要求 deptId/gradeId/majorId，level 不落库
 * （专业 id 已隐含层次），但前端必须先选 level 才能选出专业 ——
 * 所以这里仍然要求它，否则专业下拉是空的、用户会以为页面坏了。
 */
const identityComplete = computed(() => {
  const i = identity.value
  return !!(i.deptId && i.level && i.majorId && i.gradeId)
})

/** 姓名后 1~4 位（后端 @Size(max=4)，常规是 1~2 位） */
const nameTailOk = computed(() => {
  const v = (nameTail.value || '').trim()
  return v.length >= 1 && v.length <= 4
})

/** 学号 13~14 位纯数字 + 姓名后 1~4 位才允许提交 */
const canSubmit = computed(() => /^\d{13,14}$/.test(studentNo.value) && nameTailOk.value)

/**
 * 归属一改，之前那次「已保存」就不再成立：
 * 用户改完学院/专业后直接点「提交认证」会拿服务器上的旧归属去比对，
 * 结果是六层校验失败但页面看不出来是哪一步旧了。
 */
watch(
  identity,
  () => {
    if (profileSaved.value) profileSaved.value = false
  },
  { deep: true },
)

/**
 * 回填本专科。
 *
 * user 表只存 major_id，不存 level；父页面从 userInfo 预填时 level 为空，
 * 导致「已经保存过归属」的用户回到引导页看到本专科空白、专业下拉也被禁用。
 * 专业字典加载完成后按 majorId 反查 level 补上。
 */
watch(
  () => [identity.value.majorId, dict.majorsLoaded],
  () => {
    if (!identity.value.majorId || identity.value.level) return
    const lv = dict.levelOfMajor(identity.value.majorId)
    if (lv) identity.value = { ...identity.value, level: lv }
  },
  { immediate: true },
)

async function saveProfile() {
  if (!identityComplete.value || savingProfile.value) return
  savingProfile.value = true
  error.value = ''
  try {
    /*
     * 只传后端认识的三个 id。level 是前端筛选用，发了也不会被接收 ——
     * 但保留在 identity 里，因为专业下拉依赖它。
     */
    await saveSchoolProfile({
      schoolId: identity.value.schoolId,
      campus: identity.value.campus ?? null,
      deptId: identity.value.deptId,
      gradeId: identity.value.gradeId,
      majorId: identity.value.majorId,
    })
    profileSaved.value = true
    ElMessage.success('学校归属已保存')
  } catch (e) {
    error.value = e?.message || '没保存成功，再试一次'
  } finally {
    savingProfile.value = false
  }
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  if (!profileSaved.value) {
    // 先保存归属再提交学号：后端 submitStudentNo 会检查归属，提前提示比让它报错好
    error.value = '请先保存学校归属'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const data = await submitStudentNo({
      studentNo: studentNo.value,
      nameTail: nameTail.value.trim(),
    })
    /*
     * 后端回 { verifyStatus, status }：
     *   verifyStatus === 1 已认证（autoPass 命中六层后自动通过）
     *   verifyStatus === 2 审核中（未命中自动通过规则，转人工）
     * 两种都算这一步完成 —— 审核中的用户不该被卡在引导页。
     */
    appealOpen.value = false
    ElMessage.success(data?.verifyStatus === 1 ? '认证通过' : '已提交，等待人工核验')
    /*
     * 认证状态是模块级单例缓存（useVerifyStatus），提交成功后必须强制重拉。
     * 否则回到首页时仍读缓存里的 verifyStatus=0，提示条继续显示
     * 「还没完成校内认证 / 去认证」，用户会以为刚才的提交没生效。
     */
    await reloadVerifyStatus(true)
    emit('verified', data)
  } catch (e) {
    // 六层校验失败时后端只回一句「不匹配」，具体哪层不对在日志里（防规则泄露）
    error.value = e?.message || '认证没通过，检查一下学号与上面选的是否一致'
    // 1004 学号已被占用 / 1058 六层不过：给人工申诉出口（2026-10-26）
    const code = e?.businessCode ?? e?.response?.data?.code
    if (code === 1004 || code === 1058) appealOpen.value = true
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped src="./StepVerify.css"></style>
