<template>
  <!--
    邀请成员(复刻 CreateGroupSheet,去掉群名输入、带 groupId)。
    候选只取「我关注的人 + 我的粉丝」(关系已建立),邀请进现有群不发搭话额度。
    邀请成功后 emit('added', userIds),由 GroupMenu 调 useChatCore.onAddMembers 刷新。

    2026-10-21 改版(G19/G20/G21):
    - 「拉人入群」→「邀请成员」。「拉人」把同学当成资源往群里搬,和「友声」这个
      名字的口吻不搭;动词一改,按钮文案自然从「拉入 N 人」变成「邀请 N 人」。
    - 说明去掉括号里的数据来源 —— 用户不关心这批人是哪张表 JOIN 出来的,
      他只想知道「这些人为什么出现在这里」。
    - 空状态不再放大一个点不动的禁用按钮,而是给一个出口。
  -->
  <ActionSheet
    :model-value="modelValue"
    title="邀请成员"
    height="60vh"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="am">
      <p class="am__label">从你关注的人和粉丝里选</p>

      <div v-if="loadingMembers || !membersReady" class="am__hint">正在加载…</div>

      <!--
        G21:空状态给出口。原来的空态是一个 44px 高、占满整行、浅青色的
        「拉入 0 人」按钮 + 近 300px 空白 —— 禁用态做得比可用态还显眼,
        而且点不动,用户唯一能做的事是关掉这一层。
        空状态的职责不是报告「没有」,是把人送到有东西的地方。
      -->
      <div v-else-if="!members.length" class="am__empty">
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true">
          <circle cx="9.2" cy="8.4" r="3.2" />
          <path d="M3.4 19.4a5.8 5.8 0 0 1 11.6 0" />
          <path d="M18.4 9.4v5.2M15.8 12h5.2" />
        </svg>
        <h3 class="am__empty-t">{{ emptyTitle }}</h3>
        <p class="am__empty-p">{{ emptyText }}</p>
        <button type="button" class="am__empty-btn" @click="goDiscover">去发现同好</button>
      </div>

      <template v-else>
        <ul class="am__members">
          <li
            v-for="u in members"
            :key="u.id"
            class="am__member"
            :class="{ 'is-checked': checked.has(u.id) }"
            @click="toggle(u.id)"
          >
            <InitialAvatar
              :size="36"
              :src="u.avatarUrl ? resolveThumb(u.avatarUrl) : ''"
              :seed="u.id"
              :name="u.nickname || u.username || ''"
            />
            <span class="am__member-name">{{ u.nickname || u.username }}</span>
            <span class="am__check" aria-hidden="true">
              <el-icon v-if="checked.has(u.id)" :size="16"><Check /></el-icon>
            </span>
          </li>
        </ul>

        <p v-if="error" class="am__error" role="alert">{{ error }}</p>

        <button
          type="button"
          class="am__submit"
          :disabled="submitting || !checked.size"
          @click="submit"
        >
          {{ submitting ? '邀请中' : `邀请 ${checked.size} 人` }}
        </button>
      </template>
    </div>
  </ActionSheet>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { Check } from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { resolveThumb } from '@/utils/resolve'
import { following, followers } from '@/api/relation'
import { addGroupMembers } from '@/api/group'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 现有群 id */
  groupId: { type: [Number, String], required: true },
  /** 该群现有成员 id 列表;邀请时剔除这些人(避免"已在群里还能邀请") */
  existingMemberIds: { type: Array, default: () => [] },
})
const emit = defineEmits(['update:modelValue', 'added'])

const userStore = useUserStore()
const router = useRouter()
const members = ref([])
/** 剔除已在群里之前的候选总数(关注+粉丝去重后),用于区分"全在群里"vs"根本没有" */
const rawCandidates = ref(0)
const checked = ref(new Set())
const loadingMembers = ref(false)
/** 本次拉取是否已完成(区别于仍在 loading)。false 时不渲染「没有关注」的误报空态 */
const membersReady = ref(false)
const submitting = ref(false)
const error = ref('')

/** 两种空态分开写,因为它们要引导用户去的地方不同 */
const emptyTitle = computed(() =>
  rawCandidates.value > 0 ? '这些人都已经在群里了' : '还没有可以邀请的人',
)
const emptyText = computed(() =>
  rawCandidates.value > 0
    ? '你关注的人和粉丝都已经在群里。想加新朋友,先去发现同好。'
    : '先关注几个同学,或者等他们关注你,他们就会出现在这里。',
)

// 关键:本组件由 GroupMenu 条件渲染,首次挂载时 modelValue 初始就为 true。
// watch 不加 immediate,挂载瞬间没有 "false→true" 的变化,loadMembers 永不触发
// → membersReady 永远 false → 一直「正在加载」。
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
    for (const u of [...(fdata?.list || []), ...(bdata?.list || [])]) {
      if (u?.id) byId.set(u.id, u)
    }
    const all = [...byId.values()]
    rawCandidates.value = all.length
    // 剔除已在群里的人:他们不该出现在候选里(邀请也会被后端拒)
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

/** 空态的出口:关掉这一层再去发现页,否则返回时还压着一个空面板 */
function goDiscover() {
  emit('update:modelValue', false)
  router.push('/discover').catch(() => {})
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
    ElMessage.success('已邀请加入群聊')
    emit('update:modelValue', false)
    emit('added', ids)
  } catch (e) {
    // 满员(1045)给专门文案
    error.value = e?.code === 1045 ? '群已满员,无法继续邀请' : '邀请失败,再试一次'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped src="./AddMembersSheet.css"></style>
