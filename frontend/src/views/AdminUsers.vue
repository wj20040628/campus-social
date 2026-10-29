<template>
  <!--
    用户处置(2026-10-24 新增,补齐的管理空白)。

    此前的困境:封人唯一入口是举报流程,解封没有任何端点——永久封禁误判
    只能改数据库。本页提供:找人(昵称/登录名/学号) → 直接封禁 / 手动解封。

    交互约束:
    - 封禁面板行内展开而非弹窗:理由输入框在弹窗里会被手机键盘顶出视口。
    - 解封用 ConfirmDialog(轻动作);封禁不用确认框——它有「选时长 + 写
      必填理由」两道天然摩擦,再叠确认框就是三步,而且理由本身就是承诺。
    - 管理员行不显示「封禁」按钮(后端 422 拦,前端不给入口)。
  -->
  <div class="uadmin">
    <div v-if="!ready && !denied" class="uadmin__boot" aria-hidden="true">
      <span class="uadmin__boot-line" />
      <span class="uadmin__boot-line uadmin__boot-line--short" />
    </div>

    <AdminDenied v-else-if="denied" class="uadmin__state" @action="router.replace('/')" />

    <template v-else>
      <header class="uadmin__bar">
        <h1 class="uadmin__title">
          <button type="button" class="uadmin__back" aria-label="返回" @click="router.back()">
            <ArrowLeft class="uadmin__back-icon" aria-hidden="true" />
            <span>用户处置</span>
          </button>
        </h1>
        <p class="uadmin__hint">共 {{ total }} 人 · 封禁会同时断开登录态</p>
      </header>

      <!-- 筛选:关键词 + 状态。词变回第一页 -->
      <div class="uadmin__filters">
        <el-input
          v-model="keyword"
          placeholder="搜昵称 / 登录名 / 学号"
          clearable
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="search"
        />
        <div class="uadmin__segs" role="group" aria-label="按状态筛选">
          <button
            v-for="opt in STATUS_FILTERS"
            :key="String(opt.v)"
            type="button"
            class="uadmin__seg"
            :class="{ 'uadmin__seg--on': statusFilter === opt.v }"
            :aria-pressed="statusFilter === opt.v"
            @click="setStatus(opt.v)"
          >
            {{ opt.t }}
          </button>
        </div>
      </div>

      <ul v-if="loading && list.length === 0" class="uadmin__skeletons" aria-hidden="true">
        <li v-for="n in 4" :key="n" class="uadmin__skeleton">
          <span class="uadmin__skeleton-avatar" />
          <span class="uadmin__skeleton-line" />
        </li>
      </ul>

      <ul v-else class="uadmin__rows">
        <li v-for="u in list" :key="u.userId" class="uadmin__row">
          <div class="uadmin__who">
            <el-avatar :size="38" :src="resolveThumb(u.avatarUrl)">
              {{ (u.nickname || '友').slice(0, 1) }}
            </el-avatar>
            <div class="uadmin__meta">
              <p class="uadmin__name">
                <!--
                  昵称可点跳主页(2026-10-24 补):封人前先看他在站内的画像——
                  这是自己刚犯的"管理端信息不足"同款错,补上。
                -->
                <button
                  type="button"
                  class="uadmin__name-link"
                  @click="router.push(`/user/${u.userId}`)"
                >
                  {{ u.nickname || '(未设置昵称)' }}
                </button>
                <span v-if="u.role === 1" class="uadmin__roletag">管理员</span>
              </p>
              <p class="uadmin__sub">
                <span v-if="u.studentNo">{{ u.studentNo }}</span>
                <span v-else>未提交学号</span>
                <span v-if="u.dept"> · {{ u.dept }}</span>
              </p>
            </div>
            <span class="uadmin__right">
              <span class="uadmin__status" :data-status="u.status">
                {{ statusText(u) }}
              </span>
              <!--
                认证状态（2026-10-28）：账号「正常」不等于「已认证」。
                此前驳回过的用户在这一页看起来和正常人一样，
                管理员以为出了 bug —— 其实只是这一列没显示。
                未认证不显示，否则每个新号都挂一条灰字，噪音大于信息。
              -->
              <span v-if="verifyText(u)" class="uadmin__verify" :data-v="u.verifyStatus">
                {{ verifyText(u) }}
              </span>
            </span>
          </div>

          <!--
            行内封禁面板:只在目标行展开。时长默认 7 天,
            「永久」在下拉最后——最重的一档不该是默认值。
          -->
          <div v-if="banning && banning.userId === u.userId" class="uadmin__ban-panel">
            <div class="uadmin__ban-row">
              <label for="uadmin-days">封禁时长</label>
              <select id="uadmin-days" v-model.number="banDays" class="uadmin__ban-select">
                <option v-for="d in BAN_DAYS" :key="d.v" :value="d.v">{{ d.t }}</option>
              </select>
            </div>
            <input
              v-model="banReason"
              type="text"
              maxlength="200"
              placeholder="封禁理由(必填,写进操作日志)"
              @keyup.enter="submitBan"
            />
            <p v-if="actionError" class="uadmin__err" role="alert">{{ actionError }}</p>
            <div class="uadmin__ban-btns">
              <button type="button" class="uadmin__btn" @click="cancelBan">取消</button>
              <button
                type="button"
                class="uadmin__btn uadmin__btn--danger"
                :disabled="busy"
                @click="submitBan"
              >
                {{ banDays === 0 ? '永久封禁' : `封禁 ${banDays} 天` }}
              </button>
            </div>
          </div>

          <div v-else class="uadmin__acts">
            <button
              v-if="u.status === 1"
              type="button"
              class="uadmin__btn"
              :disabled="busy"
              @click="openUnban(u)"
            >
              解封
            </button>
            <button
              v-if="u.role !== 1"
              type="button"
              class="uadmin__btn uadmin__btn--danger-ghost"
              :disabled="busy"
              @click="openBan(u)"
            >
              封禁
            </button>
          </div>
        </li>
      </ul>

      <div ref="sentinelEl" class="uadmin__sentinel" aria-hidden="true">
        <p v-if="loading && list.length" class="uadmin__sentinel-text">正在加载更多</p>
        <p v-else-if="!hasMore && list.length" class="uadmin__sentinel-text">到底了</p>
      </div>

      <StateBlock
        v-if="!loading && list.length === 0"
        :variant="failed ? 'error' : 'empty'"
        :title="failed ? '列表没拉出来' : keyword ? '没找到这个人' : '还没有用户'"
        :description="failed ? '可能是网络断了,点一下重试' : '换个关键词,或检查是不是搜的学号'"
        :icon="failed ? WarningFilled : Search"
        :action-label="failed ? '重试' : ''"
        @action="failed ? load(true) : undefined"
      />
    </template>

    <!-- 解封确认:轻动作,一句后果说清即可 -->
    <ConfirmDialog
      v-model="unbanOpen"
      :title="`解封 ${unbanTarget?.nickname || '该用户'}?`"
      text="账号将立即恢复正常:可以登录、发帖、搭话。"
      confirm-text="解封"
      @confirm="onUnbanConfirm"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ArrowLeft, Search, WarningFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useAdminUsers, STATUS_FILTERS, BAN_DAYS } from '@/composables/useAdminUsers'
import { resolveThumb } from '@/utils/resolve'
import AdminDenied from '@/components/common/AdminDenied.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'

const router = useRouter()
const userStore = useUserStore()

const {
  list, total, hasMore, loading, failed,
  keyword, statusFilter,
  banning, banDays, banReason, busy, actionError,
  load, loadMore, search, setStatus,
  openBan, cancelBan, submitBan, submitUnban,
} = useAdminUsers()

load(true)

/* 权限判定与 AdminReview 同口径:ready 与 denied 分开,加载中不误判成无权限 */
const ready = computed(() => !!userStore.userInfo)
const denied = computed(() => ready.value && userStore.isAdmin !== true)

function statusText(u) {
  if (u.status === 1) {
    return u.bannedUntil ? `封禁中 · 至 ${dayjs(u.bannedUntil).format('MM-DD HH:mm')}` : '永久封禁'
  }
  return '正常'
}

/**
 * 认证状态文案。未认证(0)返回空串——新号默认就是未认证，
 * 每行都挂一条只会让「审核中 / 已驳回」这两条真正要处理的淹在灰字里。
 */
function verifyText(u) {
  switch (u.verifyStatus) {
    case 1: return '已认证'
    case 2: return '认证审核中'
    case 3: return '认证被驳回'
    default: return ''
  }
}

/* ---------------- 解封确认框 ---------------- */
/*
 * 开关与目标必须分开（2026-10-26 修）。
 * 原实现把 unbanOpen 算成「unbanTarget 是否有值」，而 ConfirmDialog 点确认时
 * 先 emit update:modelValue(false) 再 emit confirm —— 父级 setter 已把
 * unbanTarget 清空，confirm 里读到的永远是 null，解封请求根本没发出去。
 */
const unbanTarget = ref(null)
const unbanOpen = ref(false)

function openUnban(u) {
  unbanTarget.value = u
  unbanOpen.value = true
}

async function onUnbanConfirm() {
  // 先取目标再关框：关框只把 unbanOpen 置 false，不会动这里的局部变量
  const t = unbanTarget.value
  unbanOpen.value = false
  unbanTarget.value = null
  if (t) await submitUnban(t)
}

/* ---------------- 无限滚动哨兵 ---------------- */
const sentinelEl = ref(null)
let observer = null

function connectObserver() {
  observer?.disconnect()
  observer = null
  const el = sentinelEl.value
  if (!el) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting) loadMore()
    },
    { threshold: 0.1, rootMargin: '300px' },
  )
  observer.observe(el)
}

watch(sentinelEl, () => connectObserver())
onBeforeUnmount(() => observer?.disconnect())

watch(
  () => userStore.isAdmin,
  (v) => {
    // 权限在会话中途变化(被降权):立刻离开本页,别让用户数据留在屏幕上
    if (v === false) router.replace('/')
  },
)
</script>

<style scoped src="./AdminUsers.css"></style>
