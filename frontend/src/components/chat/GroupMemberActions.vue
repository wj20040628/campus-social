<template>
  <!--
    对单个成员的操作(2026-10-21 新增)。
    改版前这些动作直接铺在名单的每一行上:三个热区并排、总宽约 190px,
    昵称区被压到 120px 以内,而其中「移出群聊」不可逆,和「查看主页」只隔 8px。
    现在收进一层:点行 → 针对这一个人 → 选一个动作。
  -->
  <ActionSheet
    :model-value="modelValue"
    :title="`对 ${name}`"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="gma">
      <!--
        G16:表头再放一次头像 + 昵称 + 入群时间。
        用户点错人时,这是唯一能立刻发现的地方 —— 如果标题只写「成员操作」,
        点错人和点对人在视觉上完全一样。
      -->
      <div class="gma__head">
        <InitialAvatar
          :size="46"
          :src="target?.user?.avatarUrl ? resolveThumb(target.user.avatarUrl) : ''"
          :seed="target?.user?.id"
          :name="name"
        />
        <div class="gma__hi">
          <div class="gma__hn">{{ name }}</div>
          <div class="gma__hs">{{ roleText }}</div>
        </div>
      </div>

      <!-- 中性项在上,危险项在最后一行(单手操作时离拇指最远) -->
      <button type="button" class="gma__item" @click="onProfile">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <circle cx="12" cy="8" r="3.4" />
          <path d="M5 20a7 7 0 0 1 14 0" />
        </svg>
        查看主页
      </button>

      <button v-if="canModerate" type="button" class="gma__item" @click="onMute">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <path d="M4 9.6h2.4L11 6v12l-4.6-3.6H4v-4.8Z" />
          <path v-if="!target?.muted" d="M15.5 9.5 20 14M20 9.5 15.5 14" />
          <path v-else d="M15.2 9.4a4.2 4.2 0 0 1 0 5.2M18 7.6a7.6 7.6 0 0 1 0 8.8" />
        </svg>
        {{ target?.muted ? '解除禁言' : '禁言' }}
      </button>

      <button v-if="canModerate" type="button" class="gma__item gma__item--d" @click="kickOpen = true">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true">
          <path d="M5 7h14M9 7V4h6v3" />
          <path d="M6.5 7l.8 13h9.4l.8-13" />
        </svg>
        移出群聊
      </button>
    </div>

    <!--
      G15:移出必须二次确认。改版前 onKick 直接发请求,一次误触就把人踢出群,
      而且被踢的人没有任何提示。文案写清后果,不写「确定吗」。
    -->
    <ConfirmDialog
      v-model="kickOpen"
      :title="`把 ${name} 移出群聊?`"
      text="TA 将不再收到本群消息,之后可以重新邀请。"
      :confirm-text="`移出 ${name}`"
      danger
      @confirm="onKick"
    />
  </ActionSheet>
</template>

<script setup>
/**
 * 针对单个成员的底部操作表。
 *
 * 三层信息的顺序是刻意的:表头(对谁)→ 中性动作(看看他)→ 危险动作(移出)。
 * 「禁言」只在看的人是群主、且对方既不是自己也不是群主时出现 —— 后端
 * setMuted / kick 都限群主,前端不显没用的入口。
 */
import { ref, computed, watch } from 'vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { resolveThumb } from '@/utils/resolve'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** GroupMemberInfoVO.members 里的一项 */
  target: { type: Object, default: null },
  /** 看这个页面的我是不是群主 */
  isOwner: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'profile', 'mute', 'kick'])

const kickOpen = ref(false)

const name = computed(
  () => props.target?.user?.nickname || props.target?.user?.username || '成员',
)

const uid = computed(() => props.target?.user?.id)

/** 群主能管普通成员;对自己、对群主都不显示管理项 */
const canModerate = computed(
  () => props.isOwner && !props.target?.isSelf && !props.target?.isOwner,
)

const roleText = computed(() => {
  const d = props.target?.joinDate || ''
  const role = props.target?.isOwner ? '群主' : '群成员'
  return d ? `${role} · ${d} 入群` : role
})

/** 换人时关掉上一层残留的确认框,避免「刚点了移出 A,又打开 B 看到确认框」 */
watch(
  () => props.target,
  () => {
    kickOpen.value = false
  },
)

/**
 * ConfirmDialog 关闭时会把 body.overflow 复位成 '',
 * 但下面还压着这张操作表 —— 不补这一行,操作表开着背景却能滚,
 * 手机上表现为「滑动面板时底下的消息列表跟着动」。
 *
 * 必须 flush:'post':两个 watch 都在同一个 tick 里跑,谁后跑谁说了算。
 * ConfirmDialog 的 watch 是它自己(子组件)的,默认 pre 队列;
 * 不指定 post 的话本行会先执行、随即被子组件复位成 '',等于没写。
 */
watch(
  kickOpen,
  (v) => {
    if (!v && props.modelValue) document.body.style.overflow = 'hidden'
  },
  { flush: 'post' },
)

function close() {
  emit('update:modelValue', false)
}

function onProfile() {
  const id = uid.value
  close()
  if (id) emit('profile', id)
}

function onMute() {
  const id = uid.value
  const next = !props.target?.muted
  close()
  if (id) emit('mute', id, next)
}

function onKick() {
  const id = uid.value
  close()
  if (id) emit('kick', id)
}
</script>

<style scoped src="./GroupMemberActions.css"></style>
