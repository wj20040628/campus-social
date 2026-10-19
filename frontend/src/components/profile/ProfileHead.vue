<template>
  <!--
    个人主页身份头(2026-10-18 hero 化,参考小红书):
    上一行是父级的装饰带(.p-cover),头像用负 margin 叠上去(白描边);
    昵称+认证徽章同行,操作按钮独立成行放信息块下方 ——
    不再挤在头像右侧(原布局按钮多时右侧溢出,手机上更是放不下三个)。
    emits/props 与旧版完全一致,父级无需改动交互接线。
  -->
  <div class="p-head">
    <div class="p-id">
      <!--
        el-avatar 的 size 是**属性**不是 CSS,媒体查询改不动它(设计 §7)。
        移动端 64 / 桌面端 84 必须走 computed,不能写死。
      -->
      <el-avatar :size="avatarSize" :src="resolveThumb(user.avatarUrl)" class="p-avatar" />

      <div class="p-info">
        <div class="p-name-row">
          <span class="p-name">{{ displayName(user) }}</span>
          <!--
            认证徽章(AC-12):判据由父级传入 —— 后端 UserVO 的 verified,
            各处自己推断会误判(历史事故见 useProfileData.js 注释)。
          -->
          <span v-if="verified" class="p-badge">
            <CircleCheck class="p-badge__icon" aria-hidden="true" />
            已认证<template v-if="user.schoolName"> · {{ user.schoolName }}</template>
          </span>
          <span v-else class="p-badge p-badge--off">未认证</span>
        </div>
        <div v-if="identity.deptName || identity.gradeName" class="p-sub">
          {{ identityLine }}
        </div>
        <div v-else class="p-sub p-sub--empty">还没填学校归属</div>
        <router-link v-if="isMe && !verified" to="/profile" class="p-verify-link">
          去认证
        </router-link>
      </div>
    </div>

    <!-- 操作行:本人(编辑资料+设置) / 他人(关注+私信+举报),手机端拉满宽度 -->
    <div class="p-actions">
      <template v-if="isMe">
        <el-button type="primary" class="p-act" @click="$emit('edit')">编辑资料</el-button>
        <button
          type="button"
          class="p-gear"
          aria-label="设置"
          @click="$emit('settings')"
        >
          <el-icon :size="20"><Setting /></el-icon>
        </button>
      </template>
      <template v-else-if="showActions">
        <el-button
          :type="following ? 'default' : 'primary'"
          :loading="followLoading"
          :aria-pressed="!!following"
          :aria-label="`${following ? '已关注' : '关注'} ${displayName(user)}`"
          class="p-act"
          @click="$emit('follow')"
        >
          <el-icon><Plus /></el-icon> {{ following ? '已关注' : '关注' }}
        </el-button>
        <!--
          AC-11:未认证**不置灰也不隐藏**,点了弹说明 + 认证出口(设计 §3)。
          与 PostDetail 的「搭句话」是同一套未认证处理。
        -->
        <el-button class="p-act" @click="$emit('chat')">
          <el-icon><ChatDotRound /></el-icon> 发私信
        </el-button>
        <!-- 举报入口(AC-05 闭环):同级但不抢视觉,纯文字按钮 -->
        <el-button class="p-report" text @click="$emit('report')">
          <el-icon><WarningFilled /></el-icon> 举报
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
import { CircleCheck, ChatDotRound, Plus, WarningFilled, Setting } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'

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
  /** 移动端 64 / 桌面端 84(属性硬编码,媒体查询改不动) */
  avatarSize: { type: Number, default: 64 },
})

defineEmits(['follow', 'chat', 'edit', 'settings', 'report'])
</script>

<style scoped>
.p-head {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
  padding: 0 0 var(--ys-space-1);
}

/* 头像行:align-end 让头像底边与信息块底边对齐,负 margin 叠上装饰带 */
.p-id {
  display: flex;
  align-items: flex-end;
  gap: var(--ys-space-3);
  margin-top: -26px;
}

.p-avatar {
  flex-shrink: 0;
  /* 白描边把头像从装饰带上「托」出来;淡投影是 token 的暖墨 shadow-1 */
  border: 3px solid var(--ys-surface);
  box-shadow: var(--ys-shadow-1);
}

.p-info {
  flex: 1;
  min-width: 0;
  padding-bottom: 2px;
}

.p-name-row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  flex-wrap: wrap;
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
  max-width: 100%;
}

.p-sub {
  margin-top: 2px;
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

.p-badge {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
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
  font-size: var(--ys-text-xs);
  color: var(--ys-accent);
  text-decoration: none;
}

.p-verify-link:hover {
  color: var(--ys-accent-hover);
}

/*
 * 操作行。手机端主动作拉满宽度(小红书式),桌面端靠右:
 * el-button 根节点在组件内部,scoped 打不进去,用 :deep。
 */
.p-actions {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
}

.p-actions :deep(.el-button) {
  border-radius: var(--ys-radius-full);
}

.p-actions .p-act {
  flex: 1;
}

/* 设置齿轮:44x44 触控热区,次要视觉 */
.p-gear {
  flex: none;
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: 1px solid var(--ys-border);
  background: transparent;
  color: var(--ys-fg-2);
  border-radius: var(--ys-radius-full);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.p-gear:hover {
  background: var(--ys-surface-2);
}

@media (min-width: 1024px) {
  .p-id {
    margin-top: -36px;
  }

  .p-avatar {
    border-width: 4px;
  }

  /* 桌面端操作行靠右,按钮恢复内容宽度 */
  .p-actions {
    justify-content: flex-end;
  }

  .p-actions .p-act {
    flex: 0 0 auto;
  }
}
</style>
