<template>
  <!--
    「搭句话」主按钮(PostDetail.vue.md §1 行 6 右)
    作者本人时**整个组件不渲染**(见 PostDetail 的 v-if)。

    2026-10-30：校园信息退出权限判定。按钮只拦游客；
    账号年龄/信誉分由后端 ChatGuardServiceImpl 判定，
    被拒时 Chat 页展示后端给出的具体原因，前端不再预判。

    2026-10-28 删掉了「额度用尽置灰」：额度按人算，
    账户级数字会把还能发的人错误置灰。
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

const props = defineProps({
  /** 对方用户 id。空值(帖子作者信息缺失)时整个按钮不渲染 */
  peerId: { type: [Number, String], default: null },
  /** 按钮文案。默认「搭句话」;搭子推荐场景传「想认识」——同一个动作,语境不同 */
  label: { type: String, default: '搭句话' },
})

const emit = defineEmits(['need-login', 'error'])

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)

async function onClick() {
  // 游客没有身份,搭话要先登录;提示交给页面统一弹,不在按钮里放常驻说明
  if (!userStore.hasToken) {
    emit('need-login')
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
