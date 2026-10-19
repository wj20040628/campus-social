<template>
  <div class="user-list">
    <div v-for="u in list" :key="u.id" class="user-row" @click.stop="go(u)">
      <el-avatar :size="40" :src="resolveThumb(u.avatarUrl)" />
      <div class="u-info">
        <div class="u-name">{{ nick(u) }}</div>
        <div class="u-sub">{{ mask(u) }}<template v-if="u.dept"> · {{ u.dept }}</template></div>
      </div>
      <el-button class="u-go-btn" link type="primary" @click.stop="go(u)">查看主页</el-button>
    </div>
    <div v-if="loading" class="u-loading">
      <el-skeleton :rows="3" animated />
    </div>
<!--
空态文案由调用方传入:同一个组件被「粉丝」「关注」「被@推荐人」等场景复用,
写死「暂无」会让所有场景都变成同一句废话(AC-19)。
出口按钮同样由调用方给:「回首页」与「去逛逛」在两个场景里是不同的出路。
    -->
    <StateBlock
      v-if="!loading && !list.length"
      variant="empty"
      :title="emptyTitle"
      :description="emptyDesc"
      :action-label="emptyAction"
      :icon="User"
      @action="$emit('empty-action')"
    />
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { User } from '@element-plus/icons-vue'
import { maskUsername, displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import StateBlock from '@/components/common/StateBlock.vue'

const router = useRouter()

defineProps({
  list: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  /** 空态主句,由调用方按语境给 */
  emptyTitle: { type: String, default: '还没有人' },
  emptyDesc: { type: String, default: '' },
  /** 空态出口按钮文案。为空则不渲染按钮(AC-19 要求有出口,调用方应给出) */
  emptyAction: { type: String, default: '' },
})

defineEmits(['empty-action'])

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
