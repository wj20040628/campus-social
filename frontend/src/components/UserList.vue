<template>
  <div class="user-list">
    <div v-for="u in list" :key="u.id" class="user-row" @click.stop="go(u)">
      <el-avatar :size="40" :src="resolveUrl(u.avatarUrl)" />
      <div class="u-info">
        <div class="u-name">{{ nick(u) }}</div>
        <div class="u-sub">{{ mask(u) }}<template v-if="u.dept"> · {{ u.dept }}</template></div>
      </div>
      <el-button class="u-go-btn" link type="primary" @click.stop="go(u)">查看主页</el-button>
    </div>
    <div v-if="loading" class="u-loading">
      <el-skeleton :rows="3" animated />
    </div>
    <el-empty v-if="!loading && !list.length" description="暂无" :image-size="60" />
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { maskUsername, displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'

const router = useRouter()

defineProps({
  list: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
})

function mask(u) { return maskUsername(u?.username) }
function nick(u) { return displayName(u) }

function go(u) {
  const id = Number(u?.id)
  if (!id) return
  router.push(`/user/${id}`).catch(() => {})
}
</script>

<style scoped>
.user-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
}
.user-row:hover {
  background: var(--el-fill-color);
}
.u-info { flex: 1; }
.u-name { font-weight: 600; font-size: 14px; }
.u-sub { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.u-go-btn {
  flex-shrink: 0;
}
.u-loading { padding: 12px; }
</style>
