<template>
  <!--
    群公告对话框(2026-10-21 从 GroupAnnounce.vue 抽出)。
    抽出的理由:改版后公告有两个入口 —— 聊天页顶部那条单行条、以及 ⋯ 菜单里的
    「群公告」行。入口有两个,对话框只能有一个,否则两边会各自维护一份
    「查看 ↔ 编辑」状态并逐渐漂移。
  -->
  <el-dialog
    v-model="open"
    :title="mode === 'edit' ? '编辑群公告' : '群公告'"
    width="360px"
    append-to-body
  >
    <p v-if="mode === 'view'" class="gad__full">{{ announcement || '还没有群公告' }}</p>
    <el-input
      v-else
      v-model="draft"
      type="textarea"
      :rows="4"
      maxlength="500"
      show-word-limit
      placeholder="群公告(留空=清除)"
    />
    <template #footer>
      <template v-if="mode === 'view'">
        <el-button @click="open = false">关闭</el-button>
        <el-button v-if="isOwner" type="primary" @click="startEdit">
          {{ announcement ? '编辑' : '写一条' }}
        </el-button>
      </template>
      <template v-else>
        <el-button @click="mode = 'view'">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 公告的查看与编辑。开合由父组件持有(v-model),因为两个入口都要能打开它。
 * 查看 ↔ 编辑在同一个对话框里切换,不再维护第二个弹窗。
 */
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  announcement: { type: String, default: '' },
  isOwner: { type: Boolean, default: false },
  /** 透传 useChatCore.onSetAnnouncement(text) */
  onSetAnnouncement: { type: Function, default: () => {} },
})

const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const mode = ref('view')
const draft = ref('')

/** 父 → 子同步;每次打开都回到查看态,避免上次停在编辑态 */
watch(
  () => props.modelValue,
  (v) => {
    open.value = v
    if (v) mode.value = 'view'
  },
  { immediate: true },
)

/** 子 → 父同步:点 × 或「关闭」时把状态交回去 */
watch(open, (v) => {
  if (!v && props.modelValue) emit('update:modelValue', false)
})

function startEdit() {
  draft.value = props.announcement || ''
  mode.value = 'edit'
}

function save() {
  props.onSetAnnouncement(draft.value.trim())
  open.value = false
}
</script>

<style scoped>
.gad__full {
  margin: 0;
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-base);
  color: var(--ys-fg);
  white-space: pre-wrap;
}
</style>
