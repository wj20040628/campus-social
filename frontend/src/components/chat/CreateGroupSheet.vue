<template>
  <!--
    建群操作表(Chat.vue.md §1.x)。
    从会话列表的「新建群聊」进入:选一批关注的人 + 起名,提交后由父级拉群并打开。
    选人只取「我关注的人」(关系已建立,拉进群不发搭话额度),够用且最自然。
  -->
  <ActionSheet :model-value="modelValue" title="新建群聊" @update:model-value="$emit('update:modelValue', $event)">
    <div class="cg">
      <label class="cg__label" for="cg-name">群名称</label>
      <el-input
        id="cg-name"
        v-model="name"
        class="cg__name"
        maxlength="20"
        placeholder="给群起个名字"
      />

      <p class="cg__label">选择成员（你关注的人）</p>

      <div v-if="loadingMembers" class="cg__hint">正在加载…</div>
      <!-- 空态分流(2026-10-24):加载失败≠没关注人,误报会引导用户白忙一场 -->
      <div v-else-if="!members.length && memberLoadFailed" class="cg__hint">
        没拉到你关注的人，关掉重开试试
      </div>
      <div v-else-if="!members.length" class="cg__hint">你还没有关注任何人，先去关注几个同学</div>
      <ul v-else class="cg__members">
        <li
          v-for="u in members"
          :key="u.id"
          class="cg__member"
          :class="{ 'is-checked': checked.has(u.id) }"
          @click="toggle(u.id)"
        >
          <el-avatar :size="36" :src="resolveThumb(u.avatarUrl)" />
          <span class="cg__member-name">{{ u.nickname || u.username }}</span>
          <span class="cg__check" aria-hidden="true"><el-icon v-if="checked.has(u.id)" :size="16"><Check /></el-icon></span>
        </li>
      </ul>

      <p v-if="error" class="cg__error" role="alert">{{ error }}</p>

      <button
        type="button"
        class="cg__submit"
        :disabled="submitting || !members.length"
        @click="submit"
      >
        {{ submitting ? '建群中' : '创建群聊' }}
      </button>
    </div>
  </ActionSheet>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { Check } from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import { resolveThumb } from '@/utils/resolve'
import { following } from '@/api/relation'
import { createGroup } from '@/api/group'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'created'])

const userStore = useUserStore()
const name = ref('')
const members = ref([])
const checked = ref(new Set())
const loadingMembers = ref(false)
const submitting = ref(false)
/** 成员列表加载失败(区别于「确实没关注人」的空态) */
const memberLoadFailed = ref(false)
const error = ref('')

/** 打开时拉一次「我关注的人」;关闭时重置选择 */
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      name.value = ''
      checked.value = new Set()
      error.value = ''
      loadMembers()
    }
  },
)

async function loadMembers() {
  loadingMembers.value = true
  try {
    /*
     * uid 拿不到时显式置空并给可读提示(2026-10-24 修):
     * 原实现 uid=undefined → 后端 400「缺少参数: uid」→ catch 静默吞 →
     * 界面显示「先去关注几个同学」——把加载失败误报成「你没关注人」,
     * 用户明明关注过,照提示去关注一圈回来还是空(实测复现)。
     * 空态文案按真实原因分流:没登录信息 vs 确实没人可拉。
     */
    const me = userStore.userInfo?.id
    if (!me) {
      members.value = []
      memberLoadFailed.value = true
      return
    }
    const data = await following(me, null, 50)
    members.value = data?.list || []
    memberLoadFailed.value = false
  } catch {
    members.value = []
    memberLoadFailed.value = true
  } finally {
    loadingMembers.value = false
  }
}

function toggle(id) {
  const next = new Set(checked.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  checked.value = next
}

async function submit() {
  if (!members.value.length) return
  submitting.value = true
  error.value = ''
  try {
    const ids = [...checked.value]
    const data = await createGroup(name.value.trim(), null, ids)
    const groupId = data?.groupId
    ElMessage.success('群聊已创建')
    emit('update:modelValue', false)
    emit('created', groupId)
  } catch {
    error.value = '建群失败，再试一次'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped src="./CreateGroupSheet.css"></style>
