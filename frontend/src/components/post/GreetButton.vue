<template>
  <!--
    「搭句话」主按钮(PostDetail.vue.md §1 行 6 右)
    作者本人时**整个组件不渲染**(见 PostDetail 的 v-if)。

    ## 两态,不是三态
    1. 未认证(AC-11)→ **不隐藏按钮**,点了弹说明 + 「去认证」。
       依据:isIdentityVerified(后端 UserVO 无 verified 字段,用空壳判据永远判不出未认证)
    2. 正常 → 可点,跳转 Chat 并带上 peerId

    ## 2026-10-28 删掉了「额度用尽置灰」
    旧逻辑读 `/chat/stranger-quota` 的账户级 `remainingNewPeer`,额度为 0 就把按钮
    置灰成「今天到这儿」。新规则下额度是**按人**算的(对同一个人每天 1 条),
    与「某个帖子作者」没有账户级关系 —— 拿账户级数字去置灰单个按钮,
    会把一个还能发的人置灰成不能发。现在一律按「未知」处理:按钮可点,
    点完由 Chat 页按 peer 判定,超限时后端用具体文案拒绝,气泡上带失败重试。
  -->
  <div class="greet">
    <button
      type="button"
      class="greet__btn"
      :disabled="loading"
      @click="onClick"
    >
      {{ label }}
    </button>
  </div>
</template>

<script setup>
/**
 * 「搭句话」按钮。发消息逻辑复用 Chat(POST /chat/message),
 * 破冰走 ChatGuardService 的陌生人通道 —— 与 Chat.vue 是同一条链路,
 * 不在这里另造一套发送逻辑,否则两边的失败提示会不一样。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { isIdentityVerified } from '@/utils/mask'

const props = defineProps({
  /** 对方用户 id。空值(帖子作者信息缺失)时整个按钮不渲染 */
  peerId: { type: [Number, String], default: null },
  /** 按钮文案。默认「搭句话」;搭子推荐场景传「想认识」——同一个动作,语境不同 */
  label: { type: String, default: '搭句话' },
})

const emit = defineEmits(['need-verify', 'error'])

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)

async function onClick() {
  // AC-11:未认证不跳转。给说明 + 出口,而不是把人丢到引导页自己猜
  if (!isIdentityVerified(userStore.userInfo)) {
    emit('need-verify')
    return
  }
  loading.value = true
  try {
    // query 契约是 `?to={uid}`(Chat.vue 消费),不是 `peerId` ——
    // 写成 peerId 的话 Chat 收不到,点「搭句话」会静默停在列表页。
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

.greet__btn:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
