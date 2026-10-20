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

        <!--
          个人介绍（2026-10-19）。位置取代了原先这里展示的脱敏学号 ——
          学号结构会泄露入学年份与专业大类，够外人反推冒充；
          介绍是这个产品里唯一安全又能说明「你是谁」的字段。

          没写介绍时：本人点这段话直接编辑（文案本身就是按钮，不用额外按钮），
          他人只给一句静态文案。用 button 承载文案而不是「p + 透明按钮盖住」——
          后者依赖绝对定位对不准，且键盘 Tab 时会落到一个看不见的按钮上。
        -->
        <p v-if="hasBio" class="p-bio">{{ user.bio }}</p>
        <button
          v-else-if="isMe"
          type="button"
          class="p-bio p-bio--empty p-bio--action"
          @click="$emit('edit')"
        >点击添加介绍，让大家了解你吧</button>
        <p v-else class="p-bio p-bio--empty">这家伙很懒，什么都没有留下</p>

        <router-link v-if="isMe && !verified" to="/profile" class="p-verify-link">
          去认证
        </router-link>
      </div>
    </div>

    <!--
      操作行(2026-10-20):本人视角不再渲染任何按钮 —— 编辑资料与设置
      已收进全局抽屉(用户拍板移除主页上的重复入口);他人视角保留
      关注/私信/举报,手机端拉满宽度。
    -->
    <div v-if="!isMe && showActions" class="p-actions">
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
    </div>
  </div>
</template>

<script setup>
/**
 * 身份头。**纯展示 + 抛事件**:关注与私信的真实行为在 Profile.vue,
 * 因为它们要联动粉丝计数、未认证弹窗与路由,放进子组件会多一层双向耦合。
 * 本人视角的操作入口在全局抽屉(2026-10-20 移除主页上的编辑/设置按钮),
 * 空介绍的「点击添加介绍」仍走 edit 事件进编辑页。
 */
import { CircleCheck, ChatDotRound, Plus, WarningFilled } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'

const props = defineProps({
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

defineEmits(['follow', 'chat', 'edit', 'report'])

/**
 * 有没有写过介绍。
 *
 * <p>判据要 trim：后端已把纯空白归一成 null，但老数据与并发写入仍可能留下
 * 只有空格的值，模板里 v-if 用原始字符串会在主页上排出一行空白。
 */
const hasBio = computed(() => !!(props.user?.bio || '').trim())
</script>

<style scoped src="./ProfileHead.css"></style>
