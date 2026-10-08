<template>
  <!--
    个人主页身份头(Profile.vue.md §1.1)
    抽成组件而不是留在 Profile.vue:它是本页的核心区块,
    独立出来后模板行数离开门禁,也能单独演进(比如后续加兴趣标签 chips)。
  -->
  <div class="p-head">
    <!--
      el-avatar 的 size 是**属性**不是 CSS,媒体查询改不动它(设计 §7)。
      移动端 56 / 桌面端 72 必须走 computed,不能写死 :size="72"。
    -->
    <el-avatar :size="avatarSize" :src="resolveUrl(user.avatarUrl)" class="p-avatar" />

    <div class="p-info">
      <div class="p-name">{{ displayName(user) }}</div>

      <!--
        学院 · 年级。设计 §1.1 要求单行 13px muted 文本,
        不是两个彩色 tag —— 两个 tag 会把次要信息抬成视觉重点。
      -->
      <div v-if="identity.deptName || identity.gradeName" class="p-sub">
        {{ identityLine }}
      </div>
      <div v-else class="p-sub p-sub--empty">还没填学校归属</div>

      <div class="p-tags">
        <!--
          认证徽章(AC-12):只到「已认证 · XX 大学」这一层,
          **不显示真实姓名与学号**。未认证时给本人一个「去认证」的出口。
          判据由父级传入 —— 后端 UserVO 没有 verified 字段,
          各处自己写 `user.verified` 会恒为 false(徽章全站消失)。
        -->
        <span v-if="verified" class="p-badge">
          <CircleCheck class="p-badge__icon" aria-hidden="true" />
          已认证<template v-if="user.schoolName"> · {{ user.schoolName }}</template>
        </span>
        <span v-else class="p-badge p-badge--off">未认证</span>
        <router-link v-if="isMe && !verified" to="/onboarding" class="p-verify-link">
          去认证
        </router-link>
      </div>
    </div>

    <!-- 右侧操作区三态(设计 §1.1):本人 / 他人已关注 / 他人未关注 -->
    <div class="p-actions">
      <template v-if="isMe">
        <el-button @click="$emit('settings')">编辑资料</el-button>
      </template>
      <template v-else-if="showActions">
        <el-button
          :type="following ? 'default' : 'primary'"
          :loading="followLoading"
          :aria-pressed="!!following"
          :aria-label="`${following ? '已关注' : '关注'} ${displayName(user)}`"
          @click="$emit('follow')"
        >
          <el-icon><Plus /></el-icon> {{ following ? '已关注' : '关注' }}
        </el-button>
        <!--
          AC-11:未认证**不置灰也不隐藏**,点了弹说明 + 认证出口(设计 §3)。
          与 PostDetail 的「搭句话」是同一套未认证处理。
        -->
        <el-button @click="$emit('chat')">
          <el-icon><ChatDotRound /></el-icon> 发私信
        </el-button>
      </template>
    </div>
  </div>
</template>

<script setup>
/**
 * 身份头。**纯展示 + 抛事件**:关注与私信的真实行为在 Profile.vue,
 * 因为它们要联动粉丝计数、未认证弹窗与路由,放进子组件会多一层双向耦合。
 */
import { CircleCheck, ChatDotRound, Plus } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'

defineProps({
  user: { type: Object, required: true },
  /** 学院 · 年级名称(父级已做三级兜底) */
  identity: { type: Object, default: () => ({}) },
  identityLine: { type: String, default: '' },
  /** AC-12 判据,由父级传入(不自行推断) */
  verified: { type: Boolean, default: false },
  isMe: { type: Boolean, default: false },
  following: { type: Boolean, default: false },
  followLoading: { type: Boolean, default: false },
  /**
   * 是否显示关注/私信按钮。本人看不到,未登录也看不到 ——
   * 未登录时给一个点不动的「关注」按钮比不给更糟(AC-19)。
   */
  showActions: { type: Boolean, default: false },
  /** 移动端 56 / 桌面端 72(属性硬编码,媒体查询改不动) */
  avatarSize: { type: Number, default: 56 },
})

defineEmits(['follow', 'chat', 'settings'])
</script>

<style scoped>
.p-head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-4) 0;
}

/*
  头像样式原来在 Profile.css 里。搬进本组件后父级那条规则不再命中
  (scoped 隔离),所以这里必须重述 —— 少了会退回 EP 默认的圆角头像,
  桌面端也会丢掉压在横幅上的白描边。
*/
.p-avatar {
  flex-shrink: 0;
  /* 原 0 2px 8px rgba(0,0,0,.1) 是纯黑投影,与白描边叠加成"贴纸"效果 */
  box-shadow: var(--ys-shadow-1);
}

/* 桌面端:头像压在横幅左下角(设计 §2 的 Instagram 式叠放) */
@media (min-width: 1024px) {
  .p-head {
    margin-top: -36px;
  }

  .p-avatar {
    border: 4px solid var(--ys-surface);
  }
}

.p-info {
  flex: 1;
  min-width: 0;
}

.p-name {
  font-size: var(--ys-text-lg);
  line-height: var(--ys-lh-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.p-sub {
  margin-top: var(--ys-space-1);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.p-sub--empty {
  color: var(--ys-meta);
}

.p-tags {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-2);
}

.p-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-success);
}

.p-badge--off {
  color: var(--ys-meta);
}

.p-badge__icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.p-verify-link {
  font-size: var(--ys-text-sm);
  color: var(--ys-accent);
  text-decoration: none;
}

.p-verify-link:hover {
  color: var(--ys-accent-hover);
}

.p-actions {
  margin-left: auto;
  display: flex;
  gap: var(--ys-space-2);
}
</style>