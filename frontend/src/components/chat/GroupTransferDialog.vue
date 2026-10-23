<template>
  <!--
    转让群主(2026-10-21 从 GroupMenu.vue 内联弹窗抽出并改造)。
    改版前:成员行右端一个青框「转让」按钮,点一下就转让,而同一行右边的
    「取消」孤零零在右下角 —— 两个按钮不成组,且"点行"到底是"选中"还是
    "立即转让"分不清。
    现在:整行可点 = 选中(出现对勾),底部「取消 / 转让给 XX」两步确认。
  -->
  <el-dialog v-model="open" title="转让群主" width="400px" append-to-body>
    <p class="gtd__p">群主身份会交给对方,你降为普通成员。这个操作不能撤销。</p>

    <p v-if="!targets.length" class="gtd__empty">群里还没有其他成员可以接手。</p>
    <ul v-else class="gtd__list">
      <li
        v-for="mm in targets"
        :key="mm.user?.id"
        class="gtd__row"
        :class="{ 'is-picked': picked === mm.user?.id }"
        @click="picked = mm.user?.id"
      >
        <InitialAvatar
          :size="34"
          :src="mm.user?.avatarUrl ? resolveThumb(mm.user.avatarUrl) : ''"
          :seed="mm.user?.id"
          :name="mm.user?.nickname || mm.user?.username || ''"
        />
        <span class="gtd__name">{{ mm.user?.nickname || mm.user?.username || '成员' }}</span>
        <span class="gtd__ck" aria-hidden="true">
          <svg v-if="picked === mm.user?.id" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="m5 12.5 4.5 4.5L19 7" /></svg>
        </span>
      </li>
    </ul>

    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="danger" :disabled="!picked" @click="submit">
        {{ pickedName ? `转让给 ${pickedName}` : '转让' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 转让群主。
 *
 * 为什么删掉 window.confirm:原生 confirm 和全站视觉脱节,而且在部分
 * Android WebView / iOS PWA 下会被静默拦截(直接返回 false),表现为
 * 「点了没反应」。现在的确认就是「选中一个人 + 点带名字的按钮」这两步,
 * 比再加一层确认框更轻,信息也更足。
 *
 * 只有一个候选人时默认选中 —— 此时列表不是"选择",只是"确认对象"。
 */
import { ref, computed, watch } from 'vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { resolveThumb } from '@/utils/resolve'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** GroupMemberInfoVO.members */
  members: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue', 'submit'])

const open = ref(false)
const picked = ref(null)

/** 可接手的人:排除自己和群主 */
const targets = computed(() =>
  (props.members || []).filter((m) => !m.isSelf && !m.isOwner),
)

const pickedName = computed(() => {
  const mm = targets.value.find((x) => x.user?.id === picked.value)
  return mm?.user?.nickname || mm?.user?.username || ''
})

watch(
  () => props.modelValue,
  (v) => {
    open.value = v
    if (v) picked.value = targets.value.length === 1 ? targets.value[0].user?.id : null
  },
  { immediate: true },
)

watch(open, (v) => {
  if (!v && props.modelValue) emit('update:modelValue', false)
})

function submit() {
  if (!picked.value) return
  const uid = picked.value
  open.value = false
  emit('submit', uid)
}
</script>

<style scoped src="./GroupTransferDialog.css"></style>
