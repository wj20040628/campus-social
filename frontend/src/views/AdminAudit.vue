<template>
  <!--
    操作日志(AdminAudit.vue.md)
    只读页:没有任何写操作,所以本页不做撤销/确认交互(设计 §4)。
  -->
  <div class="audit">
    <!-- 顶栏:返回 + 标题 + 筛选开关 -->
    <header class="audit__bar">
      <button type="button" class="audit__back" aria-label="返回" @click="router.back()">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <h1 class="audit__title">操作日志</h1>
      <button
        type="button"
        class="audit__filter-btn"
        :class="{ 'audit__filter-btn--on': expanded }"
        :aria-expanded="expanded"
        aria-controls="audit-filter"
        aria-label="筛选"
        @click="filterOpen = !filterOpen"
      >
        <el-icon :size="20"><Filter /></el-icon>
      </button>
    </header>

    <!-- 筛选区:移动端默认收起。grid 0fr→1fr,不用 max-height 魔法值 -->
    <div id="audit-filter" class="audit__filter" :class="{ 'audit__filter--open': expanded }">
      <div class="audit__filter-inner">
        <label class="af-field">
          <span class="af-label">动作</span>
          <el-select v-model="f.action" placeholder="全部动作" clearable class="af-control">
            <el-option v-for="a in ACTIONS" :key="a.v" :label="a.l" :value="a.v" />
          </el-select>
        </label>

        <label class="af-field">
          <span class="af-label">用户</span>
          <!--
            桌面端恢复全量下拉(设计 §2):管理员在桌面上要精确选人,
            网络与弹层空间都撑得住。移动端走下面的关键字搜索(设计 §7)。
          -->
          <el-select
            v-if="!isMobile"
            :model-value="f.userId"
            placeholder="全部用户"
            clearable
            filterable
            class="af-control"
            @update:model-value="pickUserById"
          >
            <el-option v-for="u in allUsers" :key="u.id" :label="userName(u)" :value="u.id" />
          </el-select>
          <template v-else>
            <el-input
              v-model="userInput"
              placeholder="输入昵称查人"
              clearable
              class="af-control"
              @input="onUserInput"
            />
            <ul v-if="userOptions.length" class="af-hits">
              <li v-for="u in userOptions" :key="u.id">
                <button type="button" class="af-hit" @click="pickUser(u)">{{ userName(u) }}</button>
              </li>
            </ul>
          </template>
        </label>

        <label class="af-field">
          <span class="af-label">关键字</span>
          <el-input
            v-model="f.keyword"
            placeholder="搜 detail / IP / action"
            clearable
            class="af-control"
            @keyup.enter="load(true)"
          />
        </label>

        <label class="af-field">
          <span class="af-label">操作者昵称</span>
          <el-input
            v-model="f.nickname"
            placeholder="按操作者昵称筛选"
            clearable
            class="af-control"
            @keyup.enter="load(true)"
          />
        </label>

        <label class="af-field">
          <span class="af-label">时间范围</span>
          <el-date-picker
            v-model="timeRange"
            type="datetimerange"
            range-separator="~"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            :clearable="true"
            class="af-control"
          />
        </label>

        <!-- 两条独立的失败提示,位置不同语义也不同 -->
        <p v-if="userSearchError" class="af-inline-error" role="alert">{{ userSearchError }}</p>

        <div class="af-actions">
          <button type="button" class="af-btn" @click="resetFilters">重置</button>
          <button type="button" class="af-btn af-btn--primary" :disabled="loading" @click="load(true)">
            {{ loading ? '查询中' : '查询' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 已选条件回显:折叠态之下常驻,收起筛选区也不会丢掉「我在筛什么」 -->
    <div v-if="activeChips.length" class="audit__chips">
      <ul class="chips">
        <li v-for="c in activeChips" :key="c.key">
          <span class="chip">
            <span class="chip__text">{{ c.label }}</span>
            <button
              type="button"
              class="chip__x"
              :aria-label="`移除筛选条件：${c.label}`"
              @click="removeChip(c.key)"
            >
              <el-icon :size="14"><Close /></el-icon>
            </button>
          </span>
        </li>
        <li>
          <button type="button" class="chips__clear" @click="resetFilters">清空</button>
        </li>
      </ul>
    </div>

    <!-- 权限不足:整页替换。与 AdminReview 共用同一组件(设计 §3) -->
    <AdminDenied v-if="denied" class="audit__state" @action="goHome" />

    <template v-else>
      <!-- 加载骨架:8 条,形状对齐真实条目(头像 + 三行,第三行短) -->
      <SkeletonList v-if="loading && !list.length" class="audit__list" variant="audit" :count="8" />

      <!-- 查询失败:给重试,与「查了没结果」区分开 -->
      <StateBlock
        v-else-if="loadFailed && !list.length"
        class="audit__state"
        variant="error"
        title="日志没拉出来，可能是网络断了"
        description="连上网络再试一次，或者稍后再来"
        action-label="重试"
        :icon="WarningFilled"
        @action="load(true)"
      />

      <!-- 空态分两种:库里真没有 vs 条件太窄(设计 §3) -->
      <StateBlock
        v-else-if="!list.length && !hasFilter"
        class="audit__state"
        variant="empty"
        title="还没有任何操作记录"
        description="有人发帖、登录、被审核之后这里就有了"
        action-label="回首页"
        :primary="false"
        :icon="Files"
        @action="goHome"
      />
      <StateBlock
        v-else-if="!list.length"
        class="audit__state"
        variant="empty"
        title="这几个条件下没有记录"
        description="把条件放宽一点试试"
        action-label="清空筛选"
        :icon="Filter"
        @action="resetFilters"
      />

      <ul v-else class="audit__list">
        <li v-for="l in list" :key="l.id" class="log" role="button" tabindex="0"
          @click="openDetail(l)" @keyup.enter="openDetail(l)">
          <el-avatar :size="34" :src="resolveThumb(l.operatorAvatar)" class="log__avatar" />
          <div class="log__body">
            <p class="log__line1">
              <span class="log__op">{{ l.operatorName || '未知用户' }}</span>
              <span class="log__tag" :class="`log__tag--${actionTag(l.action)}`">
                {{ actionLabel(l.action) }}
              </span>
            </p>
            <p v-if="l.detail" class="log__detail">{{ l.detail }}</p>
            <p class="log__meta">
              <span class="log__time">{{ fmt(l.createTime) }}</span>
              <span v-if="l.ip && l.ip !== 'system'" class="log__ip">· IP {{ l.ip }}</span>
              <span v-if="l.targetType" class="log__target">
                · {{ l.targetType }}<template v-if="l.targetId"> #{{ l.targetId }}</template>
              </span>
            </p>
          </div>
        </li>
      </ul>

      <div class="sentinel">
        <SkeletonList v-if="loading" variant="audit" :count="3" />
        <p v-else-if="list.length && !hasMore" class="sentinel__tip">没有更多了</p>
      </div>
    </template>

    <!-- 详情抽屉:点列表条目展开完整信息 -->
    <el-drawer v-model="drawerOpen" :with-header="false" direction="btt" size="86%" class="audit-drawer">
      <div v-if="selected" class="ad">
        <div class="ad__head">
          <el-avatar :size="40" :src="resolveThumb(selected.operatorAvatar)" />
          <div class="ad__who">
            <p class="ad__name">{{ selected.operatorName || '未知用户' }}</p>
            <span class="log__tag" :class="`log__tag--${actionTag(selected.action)}`">{{ actionLabel(selected.action) }}</span>
          </div>
          <button type="button" class="ad__close" aria-label="关闭" @click="drawerOpen = false">
            <el-icon :size="20"><Close /></el-icon>
          </button>
        </div>
        <dl class="ad__list">
          <div class="ad__row"><dt>操作对象</dt><dd>{{ selected.targetType || '-' }}<template v-if="selected.targetId"> #{{ selected.targetId }}</template></dd></div>
          <div class="ad__row"><dt>时间</dt><dd class="ad__mono">{{ fmt(selected.createTime) }}</dd></div>
          <div class="ad__row" v-if="selected.ip && selected.ip !== 'system'"><dt>IP</dt><dd class="ad__mono">{{ selected.ip }}</dd></div>
          <div class="ad__row ad__row--block"><dt>详情</dt><dd class="ad__detail">{{ selected.detail || '-' }}</dd></div>
        </dl>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import StateBlock from '@/components/common/StateBlock.vue'
import AdminDenied from '@/components/common/AdminDenied.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import { ArrowLeft, Close, Files, Filter, WarningFilled } from '@element-plus/icons-vue'
import { useIsMobile } from '@/composables/useBreakpoint'
import { useAuditLog } from '@/composables/useAuditLog'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'

const router = useRouter()
const isMobile = useIsMobile()
const {
  denied, ACTIONS, actionLabel, actionTag, fmt,
  f, timeRange, expanded, filterOpen, activeChips, hasFilter,
  userInput, userOptions, userSearchError, allUsers,
  onUserInput, pickUser, pickUserById, removeChip, resetFilters,
  list, loading, hasMore, loadFailed, load,
  selected, drawerOpen, openDetail,
} = useAuditLog()

const userName = (u) => displayName(u) || '未命名用户'

/** 空态的「回首页」出口。管理员未必知道路由,给一个明确去处而不是让他自己找 */
const goHome = () => router.push('/')
</script>

<style scoped src="./AdminAudit.css"></style>