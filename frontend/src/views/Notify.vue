<template>
  <div class="notify">
    <div class="notify-inner">
      <el-card>
        <template #header>
          <div class="n-head">
            <span>通知</span>
            <el-button v-if="list.some((n) => !n.isRead)" link type="primary" @click="readAll">
              全部已读
            </el-button>
          </div>
        </template>
        <div class="n-list">
          <!-- 首屏加载骨架:列表还没回来时占位,避免进入页面纯白 -->
          <template v-if="loading && list.length === 0">
            <div v-for="i in 4" :key="'ns' + i" class="n-skeleton">
              <el-skeleton :rows="1" animated />
            </div>
          </template>
          <div
            v-for="n in list"
            :key="n.id"
            class="n-item"
            :class="{ unread: !n.isRead }"
            @click="openAndRead(n)"
          >
            <!-- 反馈回复匿名:不显示管理员真实头像,用中性图标占位并保持与其他条左对齐 -->
            <el-avatar v-if="n.type === 11" :size="40" class="n-avatar n-avatar-anon">
              <el-icon><Service /></el-icon>
            </el-avatar>
            <el-avatar v-else :size="40" :src="resolveThumb(n.actor?.avatarUrl)" class="n-avatar" />
            <div class="n-body">
              <div class="n-text">
                <!-- 官方回复固定署名「管理员」,不暴露具体处置人;与 summary 同一行避免中间空格 -->
                <template v-if="n.type === 11"><b>管理员</b>{{ n.summary }}</template>
                <!--
                  AI 机审通知(6/7/8)actorId 为空 = 系统判定,无具体操作人。
                  原兜底「用户」毫无信息量(实测:'用户 你的内容未通过审核'),
                  说清是系统自动判定,与人工审核区分。
                -->
                <!-- 群聊邀请（22）：平台代发，但语义是某个群邀请了你 -->
                <template v-else-if="n.type === 22"><b>群聊邀请</b>{{ n.summary }}</template>
                <!-- 处置结果（18-21）是平台动作，署名「系统通知」而不是「系统审核」 -->
                <template v-else-if="n.type >= 18 && !n.actor"><b>系统通知</b>{{ n.summary }}</template>
                <template v-else-if="(n.type === 6 || n.type === 7 || n.type === 8 || n.type >= 13) && !n.actor"><b>系统审核</b>{{ n.summary }}</template>
                <template v-else>
                  <b>{{ n.actor ? displayName(n.actor) : '用户' }}</b>
                  {{ n.summary }}
                </template>
              </div>
              <!-- 反馈回复(11)/认证驳回(17)/处置结果(18-23)/群聊邀请(22):原文或群名,引用块展示 -->
              <div v-if="(n.type === 11 || n.type === 17 || (n.type >= 18 && n.type <= 23)) && n.content" class="n-reply">{{ n.content }}</div>
              <div class="n-time">{{ n.createTime }}</div>
            </div>
            <el-tag v-if="!n.isRead" type="danger" size="small" effect="dark" class="n-badge">新</el-tag>
          </div>
          <!--
            错误态（2026-10-21 加）：优先于空态。
            接口失败时不能显示「没有新通知」—— 那是把网络故障说成没人通知你。
          -->
          <StateBlock
            v-if="!loading && loadFailed && list.length === 0"
            variant="error"
            title="通知没加载出来"
            description="可能是网络断了，点一下重试"
            action-label="重试"
            :icon="WarningFilled"
            @action="retryLoad"
          />
          <StateBlock
            v-else-if="list.length === 0"
            variant="empty"
            title="没有新通知"
            description="有人点赞、评论或关注你时会出现在这儿"
            :icon="Bell"
          />
        </div>
        <div class="n-sentinel">
          <el-text v-if="loading" type="info">加载中…</el-text>
          <el-text v-else-if="!hasMore && list.length" type="info">没有更多了</el-text>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, inject, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from '@/utils/feedback'
import dayjs from 'dayjs'
import { list as listApi, read as readApi, readAll as readAllApi } from '@/api/notify'
import { getWsClient } from '@/utils/ws'
import StateBlock from '@/components/common/StateBlock.vue'
import { Bell, Service, WarningFilled } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import { useUserStore } from '@/stores/user'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()
const ws = getWsClient()

/*
 * 清零外层角标（2026-10-21）。
 * 角标状态在 MainLayout，这里标记已读后要同步清掉 ——
 * 否则用户看完了通知，左边还挂着数字，得刷新整页才消失。
 * inject 可能拿不到（比如组件被单独挂载/测试），所以给个空函数兜底。
 */
const clearNotifyBadge = inject('clearNotifyBadge', () => {})

const list = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
/*
 * 失败态（2026-10-21 加）。
 * loadFailed —— 已放弃自动重试，模板据此渲染「没加载出来 + 重试」
 * failStreak —— 连续失败次数；2 次就停，避免 observer 无限重试
 */
const loadFailed = ref(false)
const failStreak = ref(0)
let observer = null

function fmt(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

/**
 * 拉一页通知。失败不抛：记录 loadFailed，连续失败 2 次停止自动加载，
 * 由模板渲染「没加载出来 + 重试」。（原实现无 catch，finally 重建 observer
 * 会形成无限重试，并把网络故障说成「没人通知你」。）
 */
async function load() {
  if (!hasMore.value || loading.value) return
  loading.value = true
  try {
    const data = await listApi(cursor.value, 20)
    const items = data.list.map((n) => ({ ...n, createTime: fmt(n.createTime) }))
    list.value = cursor.value ? [...list.value, ...items] : items
    cursor.value = data.hasMore ? data.nextCursor : null
    hasMore.value = data.hasMore
    loadFailed.value = false
    failStreak.value = 0
  } catch {
    failStreak.value += 1
    if (failStreak.value >= 2 || cursor.value == null) {
      hasMore.value = false
      loadFailed.value = true
    }
  } finally {
    loading.value = false
    nextTick(setupObserver)
  }
}

/** 重试：重置游标与失败态，从头拉 */
function retryLoad() {
  list.value = []
  cursor.value = null
  hasMore.value = true
  loadFailed.value = false
  failStreak.value = 0
  load()
}

function setupObserver() {
  observer?.disconnect()
  const el = document.querySelector('.n-sentinel')
  if (!el) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && !loading.value && hasMore.value) load()
    },
    { threshold: 0.1, rootMargin: '200px' }
  )
  observer.observe(el)
}

async function openAndRead(n) {
  // 跳转:点赞/评论/被关注 → 关联对象。
  // 跳转兜底:目标内容已被删除/不存在时,导航失败给出友好提示而非静默无反应
  const go = (to) => router.push(to).catch(() => ElMessage.warning('内容已删除或不存在'))

  const jump = () => {
    // 点赞/评论:优先用 postId(被指向的帖子 id);
    // 旧数据无 postId 时回退 targetId(点赞的 targetId 本就是帖子 id,评论的是评论 id,跳不了就退回首页)
    if (n.type === 2 || n.type === 3) {
      const pid = n.postId ?? (n.type === 2 ? n.targetId : null)
      if (pid) {
        // 评论(type=3)定位到那条评论(后端已把评论 id 存入 targetId),点赞跳帖子顶部
        const query = n.type === 3 ? { comment: n.targetId } : {}
        go({ path: `/post/${pid}`, query })
      } else {
        go('/')
      }
    } else if (n.type === 1) {
      // 被关注 → 关注你的人
      // actorId 可能为空(后端 NotifyVO.actorId 是 Long 包装类型,
      // 系统类通知没有「谁触发的」这个概念),拼出 /user/undefined 这种坏 URL
      // 会让 vue-router 推一个不存在的路由。TopBar.onUserCommand 已修过同一类,
      // 这里是第二个入口。拿不到 id 就落到首页,不给用户点不动的入口。
      if (n.actorId != null) go(`/user/${n.actorId}`)
      else go('/')
    } else if (n.type === 4) {
      go(`/chat`)
    } else if (n.type === 6 || n.type === 7 || n.type === 8) {
      // 内容审核结果通知:跳被审核的帖子详情
      const pid = n.postId ?? n.targetId
      if (pid) go(`/post/${pid}`)
    } else if (n.type === 9) {
      // 被 @:targetId 是评论 id,跳帖子并定位到那条评论
      const pid = n.postId ?? null
      if (pid) go({ path: `/post/${pid}`, query: { comment: n.targetId } })
    } else if (n.type === 5 || n.type === 10 || n.type === 12) {
      // 活动报名/活动取消 / 集市留言:targetId 即对应详情页 id
      go(n.type === 10 ? `/mkt/${n.targetId}` : `/activity/${n.targetId}`)
    } else if (n.type === 11) {
      // 反馈回复:管理员回复原文已在卡片内展示,点击仅标已读,不跳转任何页面
      // (显式分支:防止后续有人给 jump 加默认回首页逻辑时误伤)
    } else if (n.type === 13) {
      // 文件过审:跳到文件详情
      if (n.targetId) go(`/files/${n.targetId}`)
    } else if (n.type === 14 || n.type === 15) {
      // 文件驳回/下架:去「我的文件」看理由
      go('/files?tab=mine')
    } else if (n.type === 16 || n.type === 17) {
      // 认证结果:去编辑资料页看当前状态与驳回理由
      go('/profile')
    } else if (n.type === 20) {
      // 内容下架:帖子还留着「已下架」占位页可看;评论/私信/集市没有统一落点
      if (n.postId) go(`/post/${n.postId}`)
    } else if (n.type === 21) {
      // 解封:回到自己的主页,让「账号回来了」这件事有落点
      go('/profile')
    } else if (n.type === 22) {
      // 群聊邀请：路由段必须是 group，写 g 会掉进私聊分支（E2E 实测）
      if (n.targetId) go(`/chat/t/group/${n.targetId}`)
    } else if (n.type === 23) {
      if (n.targetId) go(`/circles/${n.targetId}`)
    } else if (n.type >= 18) {
      // 18 举报结果 / 19 封禁：结论已完整写在正文里，点击只标已读
    }
  }
  if (!n.isRead) {
    await readApi(n.id)
  }
  jump()
  markRead(n.id)
}

// 单条标已读:重赋值整条对象保证响应式,避免「新」残留
function markRead(id) {
  list.value = list.value.map((n) => (n.id === id ? { ...n, isRead: true } : n))
  // 同步清掉外层角标：用户正在这条通知里，数字没有意义了
  if (list.value.every((n) => n.isRead)) clearNotifyBadge()
}

async function readAll() {
  await readAllApi()
  list.value = list.value.map((n) => ({ ...n, isRead: true }))
  clearNotifyBadge()
  ElMessage.success('已全部标为已读')
}

// WS 实时通知
function onNotify(payload) {
  // 刷新未读计数,拉新通知
  load()
}

onMounted(async () => {
  /*
   * 进页面立刻清角标（2026-10-21）。
   * 用户的诉求：点进来看了，左边的数字就该消失 —— 不需要先逐条点一遍。
   * 角标的语义是「有你看不到的新东西」，人已经在列表里了，它就没有意义。
   * 具体的已读状态仍由下面的 load() 和用户点击正常推进，不影响数据。
   */
  clearNotifyBadge()
  ws.on('notify', onNotify)
  ws.connect('/ws/notify')
  await load()
})

onBeforeUnmount(() => {
  observer?.disconnect()
  ws.off('notify', onNotify)
})
</script>

<style scoped>
/*
 * 变量纪律:本文件原写法混用 --el-* 与 --card-radius。
 * --el-* 虽经 Bridge 最终指向 --ys-*,但业务代码直接写 --el-* 违反
 * design-tokens.css 头部硬规则 2(要改 EP 表现改 Bridge,不改业务侧),
 * 且下一任改样式的人会不知道该追哪一层。统一收回 --ys-*。
 */
.notify-inner {
  max-width: 680px;
  margin: 0 auto;
  padding: 20px;
}
.notify-inner .el-card {
  border-radius: var(--ys-radius-xl);
}
.n-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
/*
 * 「全部已读」用 EP 的 link button,而 link 态在 EP 里没有内边距 ——
 * 375px 实测只有 62x20,AC-15 要求 44x44。给它补最小热区。
 * 用 min-height/min-width 而不是 padding:改 padding 会把文字与标题挤开,
 * 视觉上「全部已读」四个字离左边框的距离会变。
 */
.n-head :deep(.el-button.is-link) {
  min-width: var(--ys-touch-min);
  min-height: var(--ys-touch-min);
}
.n-list {
  display: flex;
  flex-direction: column;
}
.n-skeleton {
  padding: 12px 4px;
  border-bottom: 1px solid var(--ys-border-soft);
}
.n-item {
  display: flex;
  gap: 12px;
  padding: 14px 12px;
  margin: 0 -12px;
  border-bottom: 1px solid var(--ys-border-soft);
  cursor: pointer;
  border-radius: var(--ys-radius-lg);
}
/* 未读:青浅底 + 左侧 3px 青条。不用灰底 —— 未读是全页最重要的状态 */
.n-item.unread {
  background: var(--ys-accent-soft);
  position: relative;
}
.n-item.unread::before {
  content: '';
  position: absolute;
  left: 0;
  top: 14px;
  bottom: 14px;
  width: 3px;
  border-radius: 2px;
  background: var(--ys-accent);
}
.n-item:hover {
  opacity: 0.85;
}
.n-avatar {
  flex-shrink: 0;
}
/*
 * 反馈回复匿名头像:暖青浅底 + 客服图标,不暴露管理员真实头像。
 * el-avatar 无图时默认白字,这里显式给底色与图标色,免得深色模式下糊成一片。
 */
.n-avatar-anon {
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}
.n-avatar-anon :deep(.el-icon) {
  font-size: 20px;
}
.n-body {
  flex: 1;
}
.n-text {
  font-size: 14px;
}
.n-time {
  font-size: 12px;
  color: var(--ys-meta);
  margin-top: 4px;
}
/* 反馈回复原文:浅底引用块,与「回复了你的反馈」摘要做视觉区分 */
.n-reply {
  margin-top: 6px;
  padding: 8px 10px;
  background: var(--ys-surface-2);
  border-left: 3px solid var(--ys-accent);
  border-radius: var(--ys-radius-md);
  font-size: 13px;
  color: var(--ys-text);
  white-space: pre-wrap;
  word-break: break-word;
}
.n-badge {
  flex-shrink: 0;
}
.n-sentinel {
  text-align: center;
  padding: 12px;
}
</style>
