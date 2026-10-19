<template>
  <!--
    群成员名单弹层(2026-10-15 从 GroupHead.vue 拆出)。
    拆出的理由:GroupHead.vue 566 行超 300 行门禁;且成员名单是独立
    关注点(全屏列表 + 自己的标签/操作/底部动作),与顶栏的群名/公告/
    管理菜单不是一件事 —— 微信/QQ 的群成员也是独立页面。
    聊天核心的 handler(kick/mute/leave)由父组件透传,本组件不自己发请求。
  -->
  <ActionSheet
    :model-value="modelValue"
    :title="`${group.name} 成员`"
    height="70vh"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p v-if="info?.announcement" class="ghm__announce">
      <el-icon :size="14"><Bell /></el-icon> {{ info.announcement }}
    </p>
    <ul class="ghm__list">
      <li
        v-for="mm in info?.members || []"
        :key="mm.user?.id"
        class="ghm__row"
      >
        <InitialAvatar :size="40" :src="mm.user?.avatarUrl ? resolveThumb(mm.user.avatarUrl) : ''" :seed="mm.user?.id" :name="mm.user?.nickname || mm.user?.username || ''" />
        <div class="ghm__info">
          <div class="ghm__name">
            {{ mm.user?.nickname || mm.user?.username || '成员' }}
            <span v-if="mm.isOwner" class="ghm__tag ghm__tag--owner">群主</span>
            <span v-if="mm.isSelf" class="ghm__tag ghm__tag--self">我</span>
            <span v-if="mm.muted" class="ghm__tag ghm__tag--muted">已禁言</span>
          </div>
          <div class="ghm__sub">{{ maskUsername(mm.user?.username) }}</div>
        </div>
        <!-- 群主可对普通成员「移出群聊」/「禁言」(不能对自己、不能对群主自己) -->
        <button
          v-if="isOwner && !mm.isSelf && !mm.isOwner"
          type="button"
          class="ghm__kick"
          @click="onKick(mm.user.id)"
        >移出群聊</button>
        <button
          v-if="isOwner && !mm.isOwner && !mm.isSelf"
          type="button"
          class="ghm__mute"
          :class="{ 'is-on': mm.muted }"
          @click="onMuteMember(mm.user.id, !mm.muted)"
        >{{ mm.muted ? '解除禁言' : '禁言' }}</button>
        <button
          v-if="!mm.isSelf"
          type="button"
          class="ghm__go"
          @click="onGoProfile(mm.user.id)"
        >查看主页</button>
      </li>
    </ul>
    <!-- 群主在名单底部:转让 + 拉人 + 退出 -->
    <template v-if="isOwner">
      <button type="button" class="ghm__foot" @click="close(); emit('add')">＋ 拉人入群</button>
      <button type="button" class="ghm__foot" @click="close(); emit('transfer')">转让群主</button>
      <button type="button" class="ghm__foot ghm__foot--danger" @click="close(); onLeaveGroup()">退出群聊</button>
    </template>
    <!-- 普通成员:拉人 + 退出(拉人对群成员开放,转让仍限群主) -->
    <template v-else>
      <button type="button" class="ghm__foot" @click="close(); emit('add')">＋ 拉人入群</button>
      <button type="button" class="ghm__foot ghm__foot--danger" @click="close(); onLeaveGroup()">退出群聊</button>
    </template>
  </ActionSheet>
</template>

<script setup>
/**
 * 群成员名单。开合状态由父组件持有(v-model),本组件只渲染与交互。
 */
import { useRouter } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import { maskUsername } from '@/utils/mask'

defineProps({
  /** 弹层开合(v-model) */
  modelValue: { type: Boolean, default: false },
  /** 当前群卡片(至少含 name) */
  group: { type: Object, required: true },
  /** 群信息头(GroupMemberInfoVO),含 announcement + members */
  info: { type: Object, default: null },
  isOwner: { type: Boolean, default: false },
  /** 踢人:透传 useChatCore.onKick(userId) */
  onKick: { type: Function, required: true },
  /** 禁言某成员:透传 useChatCore.onMuteMember(userId, muted) */
  onMuteMember: { type: Function, default: () => {} },
  /** 退出群聊:透传 useChatCore.onLeaveGroup() */
  onLeaveGroup: { type: Function, required: true },
})

const emit = defineEmits(['update:modelValue', 'add', 'transfer'])

const router = useRouter()

function close() {
  emit('update:modelValue', false)
}

/** 查看主页:纯导航,不经过聊天核心,关层后跳 */
function onGoProfile(uid) {
  if (!uid) return
  close()
  router.push(`/user/${uid}`).catch(() => {})
}
</script>

<style scoped>
/* 成员名单弹层内部(样式随组件从 GroupHead.vue 搬迁,未改一个值) */
.ghm__announce {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  margin: 0;
  padding: var(--ys-space-1) var(--ys-space-2);
  border-radius: var(--ys-radius-md);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
}
.ghm__list { list-style: none; margin: 0; padding: 0; }
.ghm__row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: var(--ys-space-2) 0;
  border-bottom: 1px solid var(--ys-border-soft);
}
.ghm__info { flex: 1; min-width: 0; }
.ghm__name {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
}
.ghm__tag {
  font-size: 10px;
  padding: 0 4px;
  border-radius: 4px;
  line-height: 16px;
}
.ghm__tag--owner { background: var(--ys-accent-soft); color: var(--ys-accent); }
.ghm__tag--self { background: var(--ys-surface-2); color: var(--ys-muted); }
.ghm__tag--muted { background: var(--ys-danger-soft); color: var(--ys-accent-2); }
.ghm__sub {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  margin-top: 1px;
}
.ghm__kick {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: 1px solid var(--ys-danger);
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-danger);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
.ghm__kick:hover { background: var(--ys-danger-soft); }
.ghm__mute {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: 1px solid var(--ys-muted);
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-muted);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
.ghm__mute:hover { background: var(--ys-surface-2); }
.ghm__mute.is-on { border-color: var(--ys-accent); color: var(--ys-accent); background: var(--ys-accent-soft); }
.ghm__go {
  flex: none;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: none;
  background: transparent;
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
.ghm__foot {
  display: block;
  width: 100%;
  min-height: var(--ys-touch-min);
  margin-top: var(--ys-space-1);
  padding: 0 var(--ys-space-2);
  border: none;
  border-radius: var(--ys-radius-md);
  background: transparent;
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  text-align: center;
  cursor: pointer;
}
.ghm__foot:hover { background: var(--ys-surface-2); }
.ghm__foot--danger { color: var(--ys-accent-2); }
.ghm__foot--danger:hover { background: var(--ys-danger-soft); }
</style>
