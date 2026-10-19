<template>
  <!--
    拉人入群操作表(复刻 CreateGroupSheet,去掉群名输入、带 groupId)。
    选人只取「我关注的人」(关系已建立),拉进现有群不发搭话额度。
    拉人成功后 emit('added', userIds),由 GroupHead 调 useChatCore.onAddMembers 刷新。
  -->
  <ActionSheet :model-value="modelValue" title="拉人入群" height="60vh" @update:model-value="$emit('update:modelValue', $event)">
    <div class="am">
      <p class="am__label">选择成员(你关注的人 + 你的粉丝)</p>

      <div v-if="loadingMembers" class="am__hint">正在加载…</div>
      <!-- 区分三种空态:
           ①没登录态未就绪 ②候选全在群里(关注/粉丝都拉完了) ③真的没关注也没粉丝 -->
      <div v-else-if="!membersReady" class="am__hint">正在加载…</div>
      <div v-else-if="rawCandidates > 0 && !members.length" class="am__hint">你关注的人和粉丝都已在群里了,没有新的可拉</div>
      <div v-else-if="!members.length" class="am__hint">你还没有关注任何人、也还没有粉丝,先去找同学互动吧</div>
      <ul v-else class="am__members">
        <li
          v-for="u in members"
          :key="u.id"
          class="am__member"
          :class="{ 'is-checked': checked.has(u.id) }"
          @click="toggle(u.id)"
        >
          <el-avatar :size="36" :src="resolveThumb(u.avatarUrl)" />
          <span class="am__member-name">{{ u.nickname || u.username }}</span>
          <span class="am__check" aria-hidden="true"><el-icon v-if="checked.has(u.id)" :size="16"><Check /></el-icon></span>
        </li>
      </ul>

      <p v-if="error" class="am__error" role="alert">{{ error }}</p>

      <button
        type="button"
        class="am__submit"
        :disabled="submitting || !checked.size"
        @click="submit"
      >
        {{ submitting ? '拉取中' : `拉入 ${checked.size} 人` }}
      </button>
    </div>
  </ActionSheet>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { Check } from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import { following, followers } from '@/api/relation'
import { addGroupMembers } from '@/api/group'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 现有群 id */
  groupId: { type: [Number, String], required: true },
  /** 该群现有成员 id 列表;拉人时剔除这些人(避免"已在群里还能拉") */
  existingMemberIds: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'added'])

const userStore = useUserStore()
const members = ref([])
/** 剔除已在群里之前的候选总数(关注+粉丝去重后),用于区分"全在群里"vs"根本没有" */
const rawCandidates = ref(0)
const checked = ref(new Set())
const loadingMembers = ref(false)
/** 本次拉取是否已完成(区别于仍在 loading)。false 时不渲染「没有关注」的误报空态 */
const membersReady = ref(false)
const submitting = ref(false)
const error = ref('')

// 关键:本组件由 GroupHead 用 <AddMembersSheet v-if="addSheetOpen"> 条件渲染,
// 首次挂载时 modelValue 初始就为 true。watch 不加 immediate,挂载瞬间没有
// "false→true" 的变化,loadMembers 永不触发 → membersReady 永远 false → 一直"正在加载"。
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      checked.value = new Set()
      error.value = ''
      loadMembers()
    }
  },
  { immediate: true },
)

async function loadMembers() {
  loadingMembers.value = true
  membersReady.value = false
  try {
    const me = userStore.userInfo?.id
    if (!Number.isInteger(me) || me <= 0) {
      // 登录态还没就绪:不发请求、不假装拉完了(避免 uid 缺参 → 返回空 → 误报)
      members.value = []
      rawCandidates.value = 0
      return
    }
    // 候选 = 我关注的人 + 我粉丝(关注我的人),按 id 去重
    const [fdata, bdata] = await Promise.all([
      following(me, null, 50),
      followers(me, null, 50),
    ])
    const byId = new Map()
    for (const u of [
      ...(fdata?.list || []),
      ...(bdata?.list || []),
    ]) {
      if (u?.id) byId.set(u.id, u)
    }
    const all = [...byId.values()]
    rawCandidates.value = all.length
    // 剔除已在群里的人:他们不该出现在"拉人"列表里(拉来也没意义)
    const existing = new Set(props.existingMemberIds)
    members.value = all.filter((u) => !existing.has(u.id))
  } catch {
    members.value = []
    rawCandidates.value = 0
  } finally {
    loadingMembers.value = false
    membersReady.value = true
  }
}

function toggle(id) {
  const next = new Set(checked.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  checked.value = next
}

async function submit() {
  if (!checked.value.size) return
  // 群 id 无效(上游没传对)时直接拦,别打出 /group/undefined/members 这种坏 URL
  const gid = Number(props.groupId)
  if (!Number.isFinite(gid) || gid <= 0) {
    error.value = '群信息未就绪,稍后再试'
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const ids = [...checked.value]
    await addGroupMembers(gid, ids)
    ElMessage.success('已拉入群聊')
    emit('update:modelValue', false)
    emit('added', ids)
  } catch (e) {
    // 满员(1045)给专门文案
    error.value = e?.code === 1045 ? '群已满员,无法继续拉人' : '拉人失败,再试一次'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped src="./AddMembersSheet.css"></style>
