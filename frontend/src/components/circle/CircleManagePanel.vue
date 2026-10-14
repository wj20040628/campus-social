<template>
  <el-drawer
    v-model="open"
    title="圈管理"
    :size="drawerSize"
    direction="rtl"
    class="cm-drawer"
    @close="emit('update:visible', false)"
  >
    <template v-if="myRole <= 2">
      <!-- 成员列表:圈主全功能,圈管理仅封禁/解禁(任命入口隐藏) -->
      <section class="cm__section">
        <h3 class="cm__h">成员</h3>
        <div v-if="loading" class="cm__loading">加载中…</div>
        <div v-else-if="!members.length" class="cm__empty">还没有成员</div>
        <ul v-else class="cm__list">
          <li v-for="m in members" :key="m.userId" class="cm__row" :class="{ 'is-banned': m.banStatus === 1 }">
            <span class="cm__avatar" aria-hidden="true">{{ (m.nickname || '?').slice(0, 1) }}</span>
            <div class="cm__id">
              <span class="cm__nick">{{ m.nickname }}</span>
              <span class="cm__role-tag">{{ roleText(m) }}</span>
            </div>
            <!-- 操作组:按 myRole + 目标 role 逐项显隐 -->
            <div class="cm__acts">
              <button
                v-if="myRole === 1 && m.role === 3 && m.userId !== me"
                type="button"
                class="cm__btn"
                @click="actions.appoint(m)"
              >任命管理</button>
              <button
                v-if="myRole === 1 && m.role === 2 && m.userId !== me"
                type="button"
                class="cm__btn"
                @click="actions.revoke(m)"
              >取消管理</button>
              <button
                v-if="m.role === 3 && m.userId !== me"
                type="button"
                class="cm__btn cm__btn--warn"
                @click="promptBan(m)"
              >{{ m.banStatus === 1 ? '改封禁' : '封禁' }}</button>
              <button
                v-if="m.role === 3 && m.banStatus === 1 && m.userId !== me"
                type="button"
                class="cm__btn"
                @click="actions.unban(m)"
              >解禁</button>
            </div>
          </li>
        </ul>
      </section>
    </template>

    <template v-else>
      <div class="cm__denied">你还没有管理权限</div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, watch, onMounted, computed } from 'vue'
import { useIsMobile } from '@/composables/useBreakpoint'
import { ElMessageBox } from '@/utils/feedback'
import { circleMembers } from '@/api/circle'
import { useCircleAdminActions } from '@/composables/useCircleAdminActions'

const props = defineProps({
  visible: { type: Boolean, default: false },
  circleId: { type: [Number, String], required: true },
  /** 当前查看者自己的圈内 role:1 圈主 / 2 圈管理 / 3 成员 / 0 非成员 */
  myRole: { type: Number, default: 0 },
  me: { type: [Number, String], default: 0 },
})
const emit = defineEmits(['update:visible', 'refresh-members'])

const isMobile = useIsMobile()
const drawerSize = computed(() => (isMobile.value ? '100%' : '420px'))

const open = ref(props.visible)
watch(() => props.visible, (v) => { open.value = v })

const loading = ref(false)
const members = ref([])

async function reload() {
  if (!props.myRole || props.myRole > 2) return
  loading.value = true
  try {
    const data = await circleMembers(props.circleId)
    members.value = data?.list || data || []
  } catch {
    members.value = []
  } finally {
    loading.value = false
  }
}

// 成员处置动作(封禁/解禁)——置顶/下架/移帖在帖子卡上,这里只管成员
const actions = useCircleAdminActions(ref(props.circleId), null, reload)

function roleText(m) {
  if (m.role === 1) return '圈主'
  if (m.role === 2) return '圈管理'
  return m.banStatus === 1 ? '被封禁' : '成员'
}

/** 封禁:选 长期 / 指定时长(小时)。until=null 长期。 */
async function promptBan(m) {
  const { value } = await ElMessageBox.prompt(
    `封禁时长(小时)。留空或填 0 = 长期封禁。`,
    '封禁成员',
    {
      confirmButtonText: '封禁',
      cancelButtonText: '取消',
      inputType: 'number',
      inputPlaceholder: '小时数,留空=长期',
      inputPattern: /^(0|[1-9]\d*)?$/,
      inputErrorMessage: '填小时数(正整数)或留空表示长期',
    },
  ).catch(() => ({ value: undefined, canceled: true }))
  if (value === undefined || value === '') return
  const hours = Number(value)
  if (Number.isNaN(hours) || hours < 0) return
  if (hours === 0) {
    await actions.ban(m, null) // 长期
  } else {
    const until = new Date(Date.now() + hours * 3600_000)
    // 后端收东八区 datetime,序列化为 'YYYY-MM-DDTHH:mm:ss'
    const pad = (n) => String(n).padStart(2, '0')
    const s = `${until.getFullYear()}-${pad(until.getMonth() + 1)}-${pad(until.getDate())}T${pad(until.getHours())}:${pad(until.getMinutes())}:${pad(until.getSeconds())}`
    await actions.ban(m, s)
  }
}

watch(open, (v) => {
  if (v) {
    reload()
    emit('refresh-members')
  }
})
onMounted(reload)

// 让父组件在「成员处置成功」后能重拉——actions 内部 reload 已覆盖
</script>

<style scoped src="./CircleManagePanel.css"></style>
