<template>
  <!--
    学号申诉弹层（2026-10-19）。

    ## 为什么复用意见反馈而不是另建一套
    认证申诉与普通意见反馈在管理员侧的处理动作几乎重合：都进同一个队列、
    都写处理说明、都能收到回复通知。单独建一张 appeal 表意味着再写一套
    列表页、详情页与状态机，而字段几乎相同。故复用 feedback，
    用 content 前缀「【认证申诉】」标记，管理端一眼可辨（见 FeedbackServiceImpl）。

    ## 为什么必须有这个出口
    六层校验会误伤真实用户：字典过期、学号规则有例外、恰好撞上某条硬规则。
    没有申诉通道时，界面只回一句模糊的「注册失败」——而错的人恰恰是无辜的，
    他既不知道自己哪里填错，也没有别的路可走，只能放弃注册。

    ## 收集什么
    只收学号与补充说明。**不要求姓名比对**：无名册可比，重名又无法消歧，
    收集了也只是给管理员一个参考项。这里刻意保持表单极简，降低提交摩擦。
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
        如果你确实是本校学生、只是没通过校验，把下面两项填了提交，管理员会人工核对。
      </p>

      <label class="ap__label" for="ap-no">你的学号</label>
      <el-input
        id="ap-no"
        v-model="studentNo"
        maxlength="14"
        placeholder="13~14 位学号"
      />

      <label class="ap__label" for="ap-note">补充说明（选填）</label>
      <el-input
        id="ap-note"
        v-model="note"
        type="textarea"
        :rows="4"
        maxlength="900"
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
 * 提交失败提示由 request.js 全局拦截器负责，这里不重复弹。
 * 提交成功后给出明确回执：管理员会处理，且回复会走站内通知。
 */
import { computed, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { submitFeedback } from '@/api/feedback'
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
const note = ref('')
const error = ref('')

/** 学号必填且须为 13/14 位数字——与后端 DTO 同一口径的最小校验 */
const canSubmit = computed(() =>
  /^\d{13,14}$/.test((studentNo.value || '').trim()) && !saving.value,
)

function open() {
  studentNo.value = props.presetStudentNo || ''
  open_.value = true
}

function reset() {
  studentNo.value = ''
  note.value = ''
  error.value = ''
}

async function submit() {
  if (!canSubmit.value) return
  saving.value = true
  error.value = ''
  try {
    // category=2「使用问题」：申诉本质是「我卡在某一步进不去」，
    // kind=1 让服务层加「【认证申诉】」前缀，管理员端按前缀识别。
    // 学号拼在正文里而非单独字段：申诉表结构不动，管理员在同一处看全信息。
    const body = {
      category: 2,
      kind: 1,
      content: `学号 ${studentNo.value.trim()} 认证未通过。${note.value.trim()}`.trim(),
    }
    await submitFeedback(body)
    open_.value = false
    ElMessage.success('申诉已提交，管理员会尽快处理')
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