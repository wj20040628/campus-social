<template>
  <!--
    兴趣标签管理弹层(从 SettingsProfile.vue 拆出)。
    复用引导第 2 步的 StepTags 标签云,只是从「首次选」变成「管理」:
    数据源换成全量字典 tree(),写回走已有的 PUT /user/tags(replaceMine),后端零改动。
    编辑态没有「以后再选」出口 —— 既然是来改的,就得选够下限才能存。
  -->
  <el-dialog
    v-model="open_"
    title="管理兴趣"
    :width="isMobile ? '92%' : '520px'"
    class="ptd__dialog"
    append-to-body
  >
    <StepTags
      :groups="groups"
      :picked="picked"
      :loading="loading"
      :failed="failed"
      :min-select="TAG_EDIT_MIN_SELECT"
      :max-select="TAG_EDIT_MAX_SELECT"
      :allow-skip="false"
      @toggle="onToggle"
      @retry="loadDict"
    />
    <template #footer>
      <span class="ptd__count">已选 {{ picked.length }} 个(至少 {{ TAG_EDIT_MIN_SELECT }} 个)</span>
      <el-button @click="open_ = false">取消</el-button>
      <el-button
        type="primary"
        :disabled="picked.length < TAG_EDIT_MIN_SELECT || saving"
        :loading="saving"
        @click="save"
      >保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 兴趣标签管理。picked 是工作副本:打开弹层时从 ofUser(me) 拷贝当前选中,
 * 取消不污染父级;保存才回写。溢出移除逻辑与引导一致(选满 max 后去掉最旧的)。
 */
import { ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { TAG_EDIT_MIN_SELECT, TAG_EDIT_MAX_SELECT } from '@/constants/schoolDict'
import { tree, replaceMine, ofUser } from '@/api/tag'
import { useUserStore } from '@/stores/user'
import { useIsMobile } from '@/composables/useBreakpoint'
import StepTags from '@/components/onboarding/StepTags.vue'

const emit = defineEmits(['saved'])

const userStore = useUserStore()
const isMobile = useIsMobile()
const groups = ref([])
const picked = ref([])
const loading = ref(false)
const failed = ref(false)
const saving = ref(false)
/** 弹层开关由 open() 独占:拉数据与显示必须同时发生,父级不该管这个状态 */
const open_ = ref(false)

/** 打开即拉字典 + 当前选中(并发,不串行) */
async function open(userId) {
  open_.value = true
  loading.value = true
  failed.value = false
  const [mine, dict] = await Promise.allSettled([ofUser(userId), tree()])
  if (dict.status === 'fulfilled') {
    groups.value = dict.value?.groups || []
  } else {
    failed.value = true
  }
  picked.value = mine.status === 'fulfilled' ? (mine.value || []).map((t) => t.id) : []
  loading.value = false
}

async function loadDict() {
  loading.value = true
  failed.value = false
  try {
    const data = await tree()
    groups.value = data?.groups || []
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/** 选中/取消:选满上限后去掉最旧的(与引导同逻辑),不让用户以为控件坏了 */
function onToggle(id) {
  const i = picked.value.indexOf(id)
  if (i >= 0) {
    picked.value.splice(i, 1)
    return
  }
  picked.value.push(id)
  if (picked.value.length > TAG_EDIT_MAX_SELECT) picked.value.shift()
}

async function save() {
  if (picked.value.length < TAG_EDIT_MIN_SELECT) return
  saving.value = true
  try {
    await replaceMine([...picked.value])
    open_.value = false
    emit('saved')
    ElMessage.success('兴趣已更新')
  } catch {
    ElMessage.error('保存失败,稍后再试')
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped>
/* 弹层内复用 StepTags 标签云,弹层本身只补头部留白与底部计数;
   chip / 分组的样式都在 StepTags 的 scoped 里,这里不重造。 */
.ptd__dialog .el-dialog__body {
  padding-top: var(--ys-space-4);
}

.ptd__count {
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
  margin-right: auto;
}
</style>
