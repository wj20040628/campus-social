<template>
  <div class="user-list">
    <div v-for="u in list" :key="u.id" class="user-row" @click.stop="go(u)">
      <el-avatar :size="40" :src="resolveThumb(u.avatarUrl)" />
      <div class="u-info">
        <div class="u-name">{{ nick(u) }}</div>
        <!--
          副行改为个人介绍（2026-10-19）。原先这里是脱敏学号，
          但学号结构会泄露入学年份与专业大类，足以让人反推填表并冒充，
          故不再对外展示（本人查学号走 /user/me 与导航抽屉）。
          没写介绍时给一句静态文案，不给「点我填写」这类暗示 ——
          别人不能替你填，暗示可点只会让人白点一下。
        -->
        <div class="u-sub" v-if="hasBio(u)">{{ u.bio }}</div>
        <div class="u-sub u-sub--empty" v-else>这家伙很懒，什么都没有留下</div>
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
import { displayName } from '@/utils/mask'
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

function nick(u) { return displayName(u) }

/** 与 ProfileHead 同判据：trim 后为空即「没写」，避免只有空格时排出一行空白 */
function hasBio(u) { return !!(u?.bio || '').trim() }

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
