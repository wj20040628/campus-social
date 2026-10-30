<template>
  <!--
    引导第 1 步：学校归属 + 学号（可选资料，2026-10-30 调整定位）。

    学校归属本身仍要填：推荐同院系/同年级的人依赖它，/onboarding/complete 也要求。
    学号是选填资料：不填不影响发帖、评论、私信、圈子、集市；
    填了只用于与所选归属做一致性比对，结果只影响资料上的登记标记。

    【为什么两个按钮是两个独立动作】
    「保存归属」调 /verify/school-profile，「提交校园信息」调 /verify/student-no。
    后者要求归属已存在 —— 后端会拦并返回 SCHOOL_PROFILE_INCOMPLETE。
  -->
  <section class="sv">
    <h2 class="sv__title">完善校园信息</h2>
    <p class="sv__sub">学号选填，不影响发帖、评论、私信、圈子和集市；填了只是完善个人资料</p>

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
    <p class="sv__hint">会与你选的学院、专业、年级比对：一致直接登记，不一致转人工核对</p>

    <button
      type="button"
      class="sv__submit"
      :disabled="submitting || !canSubmit"
      @click="submit"
    >
      {{ submitting ? '校验中…' : '提交校园信息' }}
    </button>

    <p v-if="error" class="sv__err" role="alert">{{ error }}</p>
    <!--
      申诉出口：学号被其他账号占用(1004)时可能是本人旧账号或录入错误，
      给一条人工申诉的通路，避免真实学生只能放弃。
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
 * 引导第 1 步：学校归属 + 学号（可选资料）。
 *
 * 数据与提交都就近处理（不像 StepTags/StepFollow 把状态放在父组件）：
 * 父组件只需要在提交成功后刷新 userInfo。父组件通过 @verified 事件得知结果。
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

/** 学号 13~14 位纯数字即可提交(姓名后缀已不再收集) */
const canSubmit = computed(() => /^\d{13,14}$/.test(studentNo.value))

/**
 * 归属一改，之前那次「已保存」就不再成立：
 * 用户改完学院/专业后直接提交学号会拿服务器上的旧归属去比对，
 * 得出「不一致」并被转人工核对，而页面看不出是哪一步旧了。
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
    const data = await submitStudentNo({ studentNo: studentNo.value })
    /*
     * 后端回 { verifyStatus, status }：
     *   verifyStatus === 1 一致，直接登记
     *   verifyStatus === 2 不一致，转人工核对
     * 两种都算这一步完成 —— 核对中的用户不该被卡在引导页。
     */
    appealOpen.value = false
    ElMessage.success(data?.verifyStatus === 1 ? '校园信息已登记' : '已提交，等待人工核对')
    /*
     * 状态是模块级单例缓存（useVerifyStatus），提交成功后必须强制重拉，
     * 否则回到首页仍读旧状态。
     */
    await reloadVerifyStatus(true)
    emit('verified', data)
  } catch (e) {
    error.value = e?.message || '提交失败，学号需为 13~14 位纯数字'
    // 1004 学号已被占用：给人工申诉出口
    const code = e?.businessCode ?? e?.response?.data?.code
    if (code === 1004) appealOpen.value = true
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped src="./StepVerify.css"></style>
