<template>
  <div v-if="loaded" class="tstats" aria-label="社区人数">
    <span class="tstats__item">
      <span class="tstats__label tstats__label--full">用户数：</span>
      <span class="tstats__label tstats__label--short">用户</span>
      <b>{{ fmt(users) }}</b>
    </span>
    <span class="tstats__item">
      <span class="tstats__label tstats__label--full">在线数：</span>
      <span class="tstats__label tstats__label--short">在线</span>
      <b>{{ fmt(online) }}</b>
    </span>
  </div>
</template>

<script setup>
/**
 * 顶栏人数（2026-11-13 从首页信息流移到顶栏）。
 *
 * <p>在线口径 = 有活跃 WS 连接的用户数，**含隐身用户**（隐身只影响头像绿点，
 * 不影响社区活跃度展示）。
 *
 * <p>2026-11-14：数字改为服务端变化时主动推送（type=presence_stats），
 * 收到即更新，通常在有人上/下线后 1 秒内变化。60 秒轮询保留作兜底
 * （游客没有 WS 连接，只能轮询；已登录但连接短暂异常时也不会停在旧值）。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { stats as statsApi } from '@/api/presence'
import { getWsClient } from '@/utils/ws'

const users = ref(0)
const online = ref(0)
const loaded = ref(false)
let timer = null

const ws = getWsClient()

function onPush(payload) {
  const v = Number(payload?.online)
  if (Number.isFinite(v)) {
    online.value = v
    loaded.value = true
  }
}

function onVisible() {
  if (document.visibilityState === 'visible') load()
}

function fmt(n) {
  return Number(n || 0).toLocaleString('zh-CN')
}

async function load() {
  try {
    const d = await statsApi()
    users.value = Number(d?.users) || 0
    online.value = Number(d?.online) || 0
    loaded.value = true
  } catch {
    // 统计失败不打扰用户，保留上一次数字或整组不显示
  }
}

onMounted(() => {
  ws.on('presence_stats', onPush)
  document.addEventListener('visibilitychange', onVisible)
  load()
  timer = setInterval(() => {
    if (document.visibilityState === 'visible') load()
  }, 60000)
})

onBeforeUnmount(() => {
  ws.off('presence_stats', onPush)
  document.removeEventListener('visibilitychange', onVisible)
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.tstats {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
  margin-left: 10px;
  padding-left: 10px;
  border-left: 1px solid var(--ys-border-soft);
  font-size: 11px;
  color: var(--ys-meta);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.tstats__item {
  display: inline-flex;
  align-items: baseline;
}
.tstats__label--short {
  display: none;
}
.tstats b {
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
  margin-left: 2px;
}

/* 320px 级窄屏：顶栏右侧图标已占满，人数让位避免挤压 */
@media (max-width: 359px) {
  .tstats {
    display: none;
  }
}
</style>
