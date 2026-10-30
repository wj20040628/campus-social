<template>
  <div class="aa">
    <!-- 顶栏:返回型由 MainLayout 提供,本页只提供右侧「新建」入口 -->
    <div class="aa__head">
      <button v-if="!denied" type="button" class="aa__new" aria-label="新建公告" @click="goNew()">
        <el-icon :size="18"><Plus /></el-icon>
        新建
      </button>
    </div>

    <!-- 行 2:状态筛选条 -->
    <div class="chips" role="tablist" aria-label="公告状态">
      <button
        v-for="s in STATUS_CHIPS"
        :key="String(s.value)"
        type="button"
        role="tab"
        class="chip"
        :class="{ 'is-on': status === s.value }"
        :aria-selected="status === s.value"
        @click="pickStatus(s.value)"
      >
        {{ s.label }}
        <!--
          计数（2026-10-21 加）。
          改版前四个 chip 只有文字，管理员看不出「还有几条草稿没发、几条下架了待处理」，
          必须逐个点进去数。加上计数后，筛选条本身就成了概况。
          只在已加载数据里统计（列表 20 条一页），未翻完时数字会偏小 ——
          所以用 muted 弱化，它是个提示而非权威总数。
        -->
        <span v-if="countOf(s.value) > 0" class="chip__n ys-num">{{ countOf(s.value) }}</span>
      </button>
    </div>

    <!-- 单条操作失败:浮在列表上方,对应条目的按钮同时恢复可点 -->
    <p v-if="actionError" class="notice notice--danger" role="alert">{{ actionError }}</p>

    <!-- 权限不足(AC-20)。与 AdminReview / AdminAudit 共用同一个 StateBlock -->
    <StateBlock
      v-if="denied"
      variant="denied"
      title="这个页面只有管理员能看"
      description="如果你应该是管理员,找负责人开一下权限"
      action-label="回首页"
      :icon="VerifiedShield"
      @action="router.push('/')"
    />

    <template v-else>
      <SkeletonList v-if="loading" :count="6" />

      <StateBlock
        v-else-if="error"
        variant="error"
        title="公告列表没拉出来"
        description="可能是网络断了,点一下重试"
        action-label="重试"
        :icon="WarningFilled"
        @action="load(true)"
      />

      <!-- 空态分两种:全库为空 vs 当前筛选下为空,各自给不同出口 -->
      <StateBlock
        v-else-if="all.length === 0"
        variant="empty"
        title="还没有发过公告"
        description="学校有通知要发?在这里发一条,全校同学都能看到"
        action-label="新建公告"
        :icon="Bell"
        @action="goNew()"
      />

      <StateBlock
        v-else-if="list.length === 0"
        variant="empty"
        title="这个状态下没有公告"
        description="换一个筛选看看"
        action-label="看全部"
        :primary="false"
        :icon="Filter"
        @action="pickStatus(null)"
      />

      <template v-else>
        <ul class="aa__list">
          <AdminAnnItem
            v-for="a in list"
            :key="a.id"
            :item="a"
            :busy="busyId === a.id"
            @top="toggleTop(a)"
            @shelf="toggleShelf(a)"
            @edit="goEdit(a.id)"
            @unread="openUnread(a)"
          />
        </ul>

        <SkeletonList v-if="loadingMore" :count="2" variant="panel" />
        <p v-else-if="hasMore" class="more" @click="loadMore">上拉看更多</p>
      </template>
    </template>

    <!-- 行 4:底部兜底留白 -->
    <SafeArea position="bottom" />

    <UnreadPanel
      v-model="panelOpen"
      :list="unreadList"
      :total="unreadTotal"
      :loading="panelLoading"
      :failed="panelFailed"
      :reminding="reminding"
      :reminded-ids="remindedIds"
      @remind-all="remindAll"
      @remind-one="remindOne"
      @retry="loadUnread"
    />
  </div>
</template>

<script setup>
/**
 * 公告管理(AdminAnnouncement.vue.md),OD-19:后台页必须手机可用。
 *
 * 本页的条目与「谁还没看」面板拆到 components/admin/ 下,
 * 主文件只管数据流 —— 单文件 300 行门禁。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { Plus, WarningFilled, Bell, Filter } from '@element-plus/icons-vue'
import * as api from '@/api/announcement'
import { useUserStore } from '@/stores/user'
import { STATUS_CHIPS, MAX_TOP } from '@/constants/announcement'
import { useUnreadPanel } from '@/composables/useUnreadPanel'
import VerifiedShield from '@/icons/VerifiedShield.vue'
import AdminAnnItem from '@/components/admin/AdminAnnItem.vue'
import UnreadPanel from '@/components/admin/UnreadPanel.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import SafeArea from '@/components/mobile/SafeArea.vue'

const router = useRouter()
const userStore = useUserStore()

const all = ref([])
const status = ref(null)
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref(false)
const denied = ref(false)
const actionError = ref('')
const busyId = ref(null)

const list = computed(() => all.value)

/**
 * 各状态下的条数（2026-10-21 加），供筛选 chip 显示计数。
 *
 * 只在**已加载的 all[]** 里统计：列表是 20 条一页的游标分页，
 * 未翻完时数字偏小。所以模板里用 muted 弱化，它是「提示」不是「权威总数」；
 * 真要精确总数得后端在 adminList 里一并下发各状态 count。
 *
 * @param {number|null} v STATUS_CHIPS 的值；null = 全部
 */
function countOf(v) {
  if (v == null) return all.value.length
  return all.value.filter((a) => a.status === v).length
}

/** 待处理条数：草稿 + 已下架（两者都需要管理员动作） */
const pendingCount = computed(
  () => all.value.filter((a) => a.status === 0 || a.status === 2).length,
)

/* ---- 「谁还没看」面板状态 ---- */
const {
  panelOpen, panelLoading, panelFailed,
  unreadList, unreadTotal, reminding, remindedIds, currentAnn,
  open: openUnread, load: loadUnread, remindAll, remindOne,
} = useUnreadPanel()

async function load(reset = false) {
  if (reset) {
    loading.value = true
    error.value = false
  } else {
    loadingMore.value = true
  }
  try {
    const data = await api.adminList({ status: status.value, cursor: reset ? null : cursor.value })
    all.value = reset ? data.list || [] : [...all.value, ...(data.list || [])]
    cursor.value = data.nextCursor ?? null
    hasMore.value = !!data.hasMore
  } catch (e) {
    // 403 是权限问题,不是加载失败 —— 两者必须分开处理(AC-20)
    if (e?.response?.status === 403 || e?.code === 403) denied.value = true
    else if (reset) error.value = true
    else actionError.value = '没拉出来,再点一次'
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  await load(false)
}

function pickStatus(v) {
  if (status.value === v) return
  status.value = v
  all.value = []
  cursor.value = null
  hasMore.value = false
  load(true)
}

/** 置顶位满时的判定:已置顶数 >= MAX_TOP 且本条未置顶 */
const topFull = computed(
  () => all.value.filter((a) => a.isTop === 1).length >= MAX_TOP,
)

async function patch(ann, patchBody, okMsg) {
  busyId.value = ann.id
  actionError.value = ''
  try {
    await api.update(ann.id, patchBody)
    // 本地同步:接口不回完整对象,前端自己改,避免整列表重拉导致滚动位置丢失
    Object.assign(ann, patchBody)
    if (okMsg) ElMessage.success(okMsg)
    return true
  } catch (e) {
    actionError.value =
      e?.code === 'TOP_LIMIT_EXCEEDED' ? '置顶位满了,先取消一条' : '没成功,再点一次'
    return false
  } finally {
    busyId.value = null
  }
}

async function toggleTop(a) {
  if (a.isTop !== 1 && topFull.value) {
    actionError.value = '置顶位满了,先取消一条'
    return
  }
  const next = a.isTop === 1 ? 0 : 1
  // openapi:isTop=1 时必须给 topExpireTime(否则置顶永不过期,会变成事实上的永久置顶)
  await patch(a, { isTop: next, ...(next === 1 ? { topExpireTime: expireIn7Days() } : {}) },
    next === 1 ? '已置顶' : '已取消置顶')
}

async function toggleShelf(a) {
  const next = a.status === 1 ? 2 : 1
  await patch(a, { status: next }, next === 1 ? '已上架' : '已下架')
}

function expireIn7Days() {
  const d = new Date()
  d.setDate(d.getDate() + 7)
  // 与后端约定 ISO 8601;toISOString 带 Z,后端按 UTC 解析再转本地,不会错日
  return d.toISOString()
}

/* ---- 谁还没看 ---- */

function goNew() {
  router.push('/admin/announcement/edit')
}

function goEdit(id) {
  router.push(`/admin/announcement/edit/${id}`)
}

onMounted(() => {
  // 路由守卫已拦非管理员,这里是第二道:localStorage 里的 userInfo 可能过期
  if (!userStore.isAdmin) {
    denied.value = true
    loading.value = false
    return
  }
  load(true)
})
</script>

<style scoped src="./AdminAnnouncement.css"></style>
