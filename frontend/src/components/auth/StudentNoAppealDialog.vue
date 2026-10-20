<template>
  <!--
    学号认证申诉弹层（2026-10-19）。

    ## 为什么走公开端点而不是复用意见反馈
    被六层校验挡下、注册没成功的人**没有账号、没有 token**，发不出需登录的
    POST /feedback（直接 401）；且 feedback.user_id 原本 NOT NULL 也存不进他。
    故开一条公开端点 POST /auth/appeal-student-no（/auth/** 已白名单放行），
    收集学号 + 联系邮箱 + 可选说明，写进 feedback 队列（user_id 置 NULL），
    管理员照 content 里的邮箱 mailto 联系。复用 feedback 表、不另建一张 appeal 表。

    ## 为什么必须收联系邮箱
    没有账号就没有站内通知渠道，邮箱是管理员唯一能主动联系到提交人的路。
    邮箱与学号一起落进 content，管理端直接 mailto，不迁表结构。
  -->
  <el-dialog
    v-model="open_"
    title="学号认证申诉"
    :width="isMobile ? '92%' : '480px'"
    append-to-body
    @closed="reset"
  >
    <div class="ap">
      <p class="ap__lead">
        如果你确实是本校学生、只是没通过校验，把下面两项填了提交，
        管理员会人工核对并通过邮箱联系你。
      </p>

      <label class="ap__label" for="ap-no">你的学号</label>
      <el-input
        id="ap-no"
        v-model="studentNo"
        maxlength="14"
        placeholder="13~14 位学号"
      />

      <label class="ap__label" for="ap-mail">联系邮箱（必填）</label>
      <el-input
        id="ap-mail"
        v-model="email"
        placeholder="管理员会据此联系你"
        maxlength="100"
      />

      <label class="ap__label" for="ap-note">补充说明（选填）</label>
      <el-input
        id="ap-note"
        v-model="note"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        resize="none"
        placeholder="比如：学院选错了、年级填错了一位，或者你确信这些信息都对但校验没通过"
      />

      <p v-if="error" class="ap__error" role="alert">{{ error }}</p>
    </div>

    <template #footer>
      <el-button @click="open_ = false">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">
        提交申诉
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 走公开端点 appealStudentNo（不带 token，未登录也能提交）。
 * silent:true → 全局拦截器不弹 toast，成功/失败都由弹层内联回执。
 * 成功文案必须给一个「等在哪里」的锚点（留意邮箱），否则用户提交完就没了下文。
 */
import { computed, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { appealStudentNo } from '@/api/auth'
import { useIsMobile } from '@/composables/useBreakpoint'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 注册页已经填过的学号，省得用户再输一遍 */
  presetStudentNo: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'submitted'])

const isMobile = useIsMobile()
const open_ = ref(false)
const saving = ref(false)
const studentNo = ref('')
const email = ref('')
const note = ref('')
const error = ref('')

/** 学号须 13/14 位数字 + 联系邮箱须合法——最小校验，与后端 DTO 同口径 */
const canSubmit = computed(
  () =>
    /^\d{13,14}$/.test((studentNo.value || '').trim()) &&
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test((email.value || '').trim()) &&
    !saving.value,
)

function open() {
  studentNo.value = props.presetStudentNo || ''
  open_.value = true
}

function reset() {
  studentNo.value = ''
  email.value = ''
  note.value = ''
  error.value = ''
}

async function submit() {
  if (!canSubmit.value) return
  saving.value = true
  error.value = ''
  try {
    await appealStudentNo({
      studentNo: studentNo.value.trim(),
      email: email.value.trim(),
      note: note.value.trim(),
    })
    open_.value = false
    ElMessage.success('申诉已提交，管理员会尽快处理，请留意你的邮箱')
    emit('submitted')
  } catch (e) {
    error.value = e?.isBusiness ? (e.message || '提交失败') : '网络异常，稍后再试'
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped src="./StudentNoAppealDialog.css"></style>
