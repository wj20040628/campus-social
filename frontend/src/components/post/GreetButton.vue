<template>
  <!--
    「搭句话」主按钮(PostDetail.vue.md §1 行 6 右)
    作者本人时**整个组件不渲染**(见 PostDetail 的 v-if)。

    ## 三态权限,不是三态随意猜
    设计 §3 要求四种情形下有四种不同的反应,这里逐条对应实现依据:

    1. 未认证(AC-11)→ **不隐藏按钮**,点了弹说明 + 「去认证」。
       依据:isIdentityVerified(后端 UserVO 无 verified 字段,用空壳判据永远判不出未认证)
    2. 对方关闭陌生人消息(AC-05)→ **按钮置灰** + 一句 meta 说明。
       依据:**没有**。后端只在发送时抛 STRANGER_MSG_CLOSED(1030),
       stranger-quota 返回的 strangerMsgEnabled 是「我」是否接收,不是对方。
       所以这一态只能**点下去才知道**,本组件按「未知」处理:
       不预先置灰(置灰却没有真实依据就是骗人),失败后由 emit 的 error 回执提示。
    3. 额度用尽(AC-04)→ 按钮置灰 + 明日再来。
       依据:strangerQuota(peerId) 的 remainingNewPeer === 0
    4. 正常 → 可点,跳转 Chat 并带上 peerId
  -->
  <div class="greet">
    <button
      type="button"
      class="greet__btn"
      :class="{ 'greet__btn--off': quotaUsed }"
      :disabled="quotaUsed || loading"
      :aria-describedby="quotaUsed ? 'greet-reason' : undefined"
      @click="onClick"
    >
      {{ quotaUsed ? '今天到这儿' : '搭句话' }}
    </button>
    <!-- 置灰必须给原因。只给一个点不动的按钮,用户会一直试。 -->
    <p v-if="quotaUsed" id="greet-reason" class="greet__why">
      今天打招呼的次数用完了，明天再来
    </p>
  </div>
</template>

<script setup>
/**
 * 「搭句话」按钮。发消息逻辑复用 Chat(POST /chat/message),
 * 破冰走 ChatGuardService 的陌生人通道 —— 与 Chat.vue 是同一条链路,
 * 不在这里另造一套发送逻辑,否则两边的失败提示会不一样。
 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useStrangerQuota } from '@/composables/useStrangerQuota'
import { useUserStore } from '@/stores/user'
import { isIdentityVerified } from '@/utils/mask'

const props = defineProps({
  /** 对方用户 id。空值(帖子作者信息缺失)时整个按钮不渲染 */
  peerId: { type: [Number, String], default: null },
})

const emit = defineEmits(['need-verify', 'error'])

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)

/**
 * 额度用尽。`quotaRemaining` 为 null 表示**取不到**而不是 0 ——
 * 取不到时不能置灰:置灰会让用户以为今天不能搭话,实际能,
 * 他去问人才发现是产品故障。宁可按钮能点、点完被后端拒,也不要假置灰。
 *
 * 该值由 useStrangerQuota 在全站共享一份(账户级额度),
 * 只在第一个挂载的 GreetButton 真正发请求,消除 feed 每卡 N+1。
 */
const { quotaRemaining } = useStrangerQuota()
const quotaUsed = computed(() => quotaRemaining.value !== null && quotaRemaining.value <= 0)

async function onClick() {
  // AC-11:未认证不跳转。给说明 + 出口,而不是把人丢到引导页自己猜
  if (!isIdentityVerified(userStore.userInfo)) {
    emit('need-verify')
    return
  }
  loading.value = true
  try {
    // query 契约是 `?to={uid}`(Chat.vue:175 消费),不是 `peerId` ——
    // 写成 peerId 的话 Chat 收不到,点「搭句话」会静默停在列表页,
    // 用户以为坏了。发消息本身由 Chat 页承接,与 Profile 的「发私信」同一条链路。
    router.push({ path: '/chat', query: { to: String(props.peerId) } })
  } catch (e) {
    emit('error', e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.greet {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--ys-space-1);
}

/* 主按钮:44px 高、最小宽 88px(设计 §1 行 6 硬要求) */
.greet__btn {
  min-width: 88px;
  min-height: 44px;
  padding: 0 var(--ys-space-4);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    opacity var(--ys-motion-instant) var(--ys-ease-out);
}

.greet__btn:hover:not(:disabled) {
  background: var(--ys-accent-hover);
  border-color: var(--ys-accent-hover);
}

.greet__btn:active:not(:disabled) {
  opacity: 0.7;
}

/* 置灰仍要可读:用 border + muted,不用 opacity 把整块推到看不见 */
.greet__btn--off {
  background: var(--ys-surface-2);
  border-color: var(--ys-border-control);
  color: var(--ys-muted);
  cursor: not-allowed;
}

.greet__why {
  margin: 0;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-meta);
}
</style>