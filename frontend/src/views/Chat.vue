<template>
  <div class="chat">
    <el-container class="chat-shell">
      <!-- 会话列表 -->
      <el-aside width="280px" class="chat-conv">
        <div class="conv-title">会话</div>

        <!-- 群组分区 -->
        <div class="conv-group">
          <div class="conv-sec-label conv-sec">
            <span>群组</span>
            <el-button link type="primary" size="small" @click="openCreateGroup">+ 发起群聊</el-button>
          </div>
          <div v-for="g in groups" :key="g.id" class="conv-item" :class="{ active: currentKind === 'group' && currentGroup?.id === g.id }" @click="openGroup(g)">
            <el-badge :value="g.myUnread || 0" :hidden="!g.myUnread" :max="99">
              <el-avatar :size="44" :src="g.avatarUrl ? resolveUrl(g.avatarUrl) : ''" class="conv-avatar g-avatar">
                <span v-if="!g.avatarUrl" class="g-avatar-text">{{ g.name?.slice(0, 1) || '群' }}</span>
              </el-avatar>
            </el-badge>
            <div class="conv-info">
              <div class="conv-name">{{ g.name }}</div>
              <div class="conv-preview">{{ g.lastPreview || '暂无消息' }}</div>
            </div>
          </div>
          <div v-if="!groups.length" class="conv-empty">暂无群聊</div>
        </div>

        <!-- 单聊分区 -->
        <div class="conv-group">
          <div class="conv-sec-label">私信</div>
          <div
            v-for="c in convs"
            :key="c.convId"
            class="conv-item"
            :class="{ active: currentKind === 'dm' && currentConvId === c.convId }"
            @click="openConv(c)"
          >
            <el-badge :value="c.unread || 0" :hidden="!c.unread" :max="99">
              <el-avatar
                :size="44"
                :src="resolveUrl(c.peer?.avatarUrl)"
                class="conv-avatar"
                @click.stop="goPeer(c.peer)"
              />
            </el-badge>
            <div class="conv-info">
              <div class="conv-name">{{ displayName(c.peer) }}</div>
              <div class="conv-preview">{{ c.lastPreview || '暂无消息' }}</div>
            </div>
          </div>
          <div v-if="!convs.length" class="conv-empty">暂无会话</div>
        </div>

        <!-- 我关注的:默认收起,展开后按最近会话时间排,点谁直接开聊 -->
        <div class="follow-sec">
          <div class="follow-head" @click="followExpanded = !followExpanded">
            <span class="follow-label">我关注的</span>
            <el-icon :class="{ 'is-rot': followExpanded }"><CaretBottom /></el-icon>
          </div>
          <div v-if="followExpanded" class="follow-list">
            <div v-if="!followList.length" class="follow-empty">还没有关注的人</div>
            <div
              v-for="u in followList"
              :key="u.id"
              class="follow-item"
              @click="openUserConv(u)"
            >
              <el-avatar :size="36" :src="resolveUrl(u.avatarUrl)" />
              <div class="follow-info">
                <div class="f-name">{{ displayName(u) }}</div>
                <div class="f-sub">{{ maskUsername(u?.username) }}</div>
              </div>
            </div>
            <div
              v-if="followList.length > 10"
              class="follow-more"
              @click="followMore()"
            >
              {{ followLoadingMore ? '加载中…' : '查看更多' }}
            </div>
          </div>
        </div>

        <!-- 发起群聊弹窗 -->
        <el-dialog v-model="createGroupOpen" title="发起群聊" width="380px" append-to-body>
          <div class="cg-body">
            <div class="cg-row">
              <label>群名称</label>
              <el-input v-model="cgName" placeholder="给群起个名字" maxlength="32" show-word-limit />
            </div>
            <div class="cg-row">
              <label>拉入成员</label>
              <div class="cg-members">
                <div v-if="!followList.length" class="cg-empty">暂无关注的人,先去关注几位的吧</div>
                <el-checkbox
                  v-for="u in followList"
                  :key="u.id"
                  :value="u.id"
                  v-model="cgMembers"
                  class="cg-member"
                >
                  <el-avatar :size="24" :src="resolveUrl(u.avatarUrl)" class="cg-m-avatar" />
                  <span class="cg-m-name">{{ displayName(u) }}</span>
                </el-checkbox>
              </div>
            </div>
          </div>
          <template #footer>
            <el-button @click="createGroupOpen = false">取消</el-button>
            <el-button type="primary" :loading="cgBusy" :disabled="!cgName.trim()" @click="submitCreateGroup">
              创建群聊
            </el-button>
          </template>
        </el-dialog>
      </el-aside>

      <!-- 消息窗口 -->
      <el-main class="chat-main">
        <template v-if="currentKind === 'group' ? currentGroup : currentConv">
          <div class="msg-head">
            <span class="msg-peer">{{ headTitle }}</span>
            <span v-if="headSub" class="msg-sub">{{ headSub }}</span>
          </div>
          <div ref="msgListEl" class="msg-list">
            <div v-for="m in messages" :key="m.id" class="msg-row" :class="{ me: m.fromMe }">
              <!-- 群消息:显示发送者昵称(单聊不用) -->
              <div v-if="currentKind === 'group' && !m.fromMe && !m.recalled" class="msg-sender">{{ m.senderNickname || '群成员' }}</div>
              <div class="msg-bubble" :class="{ recalled: m.recalled }">
                <div v-if="m.recalled" class="msg-recalled-tip">这条消息已撤回</div>
                <template v-else>
                  <div v-if="m.replyToContent" class="msg-reply-quote">
                    <div class="msg-reply-from">{{ m.replyToNickname || (m.fromMe ? '我' : (currentGroup ? '成员' : (currentConv?.peer?.nickname || '对方'))) }}:</div>
                    <div class="msg-reply-text">{{ m.replyToContent }}</div>
                  </div>
                  <div class="msg-text">{{ m.content }}</div>
                </template>
                <div class="msg-foot">
                  <span class="msg-time">{{ m.createTime }}</span>
                  <!-- 我发的:显示对方读没读(群=至少一人读到) -->
                  <span v-if="m.fromMe && !m.recalled" class="msg-read" :class="{ read: m.status === 1 }">
                    {{ m.status === 1 ? '已读' : '未读' }}
                  </span>
                  <el-tooltip v-if="m.fromMe && !m.recalled && canRecall(m)" content="撤回" placement="top">
                    <span class="msg-recall-btn" @click="doRecall(m)">↩</span>
                  </el-tooltip>
                </div>
              </div>
              <!-- 引用回复:对方消息可点引用 -->
              <el-tooltip v-if="!m.recalled && !m.fromMe" content="引用此条" placement="top">
                <span class="msg-reply-btn" @click="setReply(m)">📎</span>
              </el-tooltip>
            </div>
          </div>
          <div class="msg-input">
            <div v-if="replyToMsg" class="reply-bar">
              <span class="reply-label">引用:</span>
              <span class="reply-preview">{{ replyToMsg.content || '(已撤回)' }}</span>
              <el-button link type="primary" @click="cancelReply">取消</el-button>
            </div>
            <el-input
              v-model="draft"
              :rows="2"
              type="textarea"
              placeholder="按 Enter 发送,Shift+Enter 换行"
              @keydown.enter.exact.prevent="doSend"
            />
            <el-button type="primary" :disabled="!draft.trim()" @click="doSend">发送</el-button>
          </div>
        </template>
        <div v-else class="chat-empty">
          选择一个会话开始聊天
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CaretBottom } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import {
  conversations as convApi,
  history as historyApi,
  send as sendApi,
  recall as recallApi,
  markRead,
} from '@/api/chat'
import {
  myGroups as groupsApi,
  groupMessages as groupMsgApi,
  sendGroupMessage as groupSendApi,
  recallGroupMessage as groupRecallApi,
  markGroupRead as groupMarkReadApi,
  createGroup as createGroupApi,
} from '@/api/group'
import { following as followingApi } from '@/api/relation'
import { userCard } from '@/api/auth'
import { getWsClient } from '@/utils/ws'
import { maskUsername, displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const convs = ref([])
const messages = ref([])
const currentConvId = ref(null)
const currentConv = ref(null)
const draft = ref('')
const msgListEl = ref(null)
const replyToMsg = ref(null)   // 引用回复:被引消息(发完自动清)
const nowTs = ref(Date.now())

// 群组
const groups = ref([])

// 发起群聊弹窗
const createGroupOpen = ref(false)
const cgName = ref('')
const cgMembers = ref([])
const cgBusy = ref(false)

function openCreateGroup() {
  cgName.value = ''
  cgMembers.value = []
  createGroupOpen.value = true
}

async function submitCreateGroup() {
  cgBusy.value = true
  try {
    const res = await createGroupApi(cgName.value.trim(), '', cgMembers.value)
    await loadGroups()
    createGroupOpen.value = false
    if (res?.groupId) {
      const g = groups.value.find((x) => x.id === res.groupId)
      if (g) await openGroup(g)
    }
    ElMessage.success('群已创建')
  } catch {
    // 后端报错已弹 toast
  } finally {
    cgBusy.value = false
  }
}

// 「我关注的」区块
const followExpanded = ref(false)
const followList = ref([])
const followCursor = ref(null)
const followLoadingMore = ref(false)

const ws = getWsClient()

function fmt(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

async function loadConvs() {
  const data = await convApi(null, 50)
  convs.value = data.list.map((c) => ({
    ...c,
    lastMsgTime: fmt(c.lastMsgTime),
    lastMsgTimeRaw: c.lastMsgTime,
  }))
  // 「我关注的」按最近会话时间重排:有会话的排前面,按最后消息时间倒序,没会话的垫后
  if (followList.value.length) {
    const lastByPeer = new Map()
    for (const c of convs.value) {
      if (c.peer?.id && c.lastMsgTimeRaw) lastByPeer.set(c.peer.id, c.lastMsgTimeRaw)
    }
    followList.value.sort((a, b) => {
      const ta = lastByPeer.get(a.id) ? new Date(lastByPeer.get(a.id)).getTime() : 0
      const tb = lastByPeer.get(b.id) ? new Date(lastByPeer.get(b.id)).getTime() : 0
      return tb - ta
    })
  }
}

async function openConv(c) {
  currentConv.value = c
  currentConvId.value = c.convId
  currentKind.value = 'dm' // 单聊
  replyToMsg.value = null // 切会话时清掉引用条(防 A→B 切回 C→D 残留旧引用)
  await markRead(c.peer?.id)
  convs.value = convs.value.map((x) => (x.convId === c.convId ? { ...x, unread: 0 } : x))
  await loadHistory()
  // 切到该会话,WS 推来时仅当是当前会话才刷新
}

// 当前打开的是哪种会话: 'dm'(单聊) | 'group'(群聊)
const currentKind = ref('dm')
const currentGroup = ref(null)   // 群卡片(GroupVO)

async function loadGroups() {
  const data = await groupsApi(null, 50)
  groups.value = data.list.map((g) => ({ ...g, lastMsgTime: fmt(g.lastMsgTime) }))
}

async function openGroup(g) {
  currentGroup.value = g
  currentConv.value = null
  currentConvId.value = 0
  currentKind.value = 'group'
  replyToMsg.value = null
  await groupMarkReadApi(g.id)
  groups.value = groups.value.map((x) => (x.id === g.id ? { ...x, myUnread: 0 } : x))
  await loadGroupHistory()
}

async function loadGroupHistory() {
  if (!currentGroup.value) return
  const data = await groupMsgApi(currentGroup.value.id, null, 50)
  // 群消息要带发送者昵称:批量查(前端聚合 sender_id,复用 userCard)
  const sids = [...new Set(data.list.map((m) => m.senderId).filter(Boolean))]
  const nickMap = await batchNicknames(sids)
  messages.value = data.list.map((m) => {
    const sendTs = m.createTime ? new Date(m.createTime).getTime() : 0
    return { ...m, createTime: fmt(m.createTime), sendTs, senderNickname: nickMap.get(m.senderId) }
  })
  scrollBottom()
}

/** 批量查昵称(缓存:已查过的 sender 直接复用) */
const nickCache = new Map()
async function batchNicknames(ids) {
  const out = new Map()
  const todo = ids.filter((id) => !nickCache.has(id))
  for (const id of todo) {
    try {
      const u = await userCard(id)
      nickCache.set(id, u.nickname || u.username)
    } catch {
      nickCache.set(id, '用户' + id)
    }
  }
  ids.forEach((id) => out.set(id, nickCache.get(id)))
  return out
}

/** 按 currentKind 统一发消息(单聊→send,群聊→sendGroup) */
async function doSend() {
  if (!draft.value.trim() || !currentGroup.value && !currentConv.value) return
  const content = draft.value.trim()
  const replyId = replyToMsg.value?.id || null
  const replyContent = replyToMsg.value?.content || null
  draft.value = ''
  try {
    let msgId
    if (currentKind.value === 'group') {
      const res = await groupSendApi(currentGroup.value.id, content, replyId)
      msgId = res?.msgId || Date.now()
      const replyTo = replyId ? { replyToId: replyId, replyToContent: replyContent || '(已撤回)', replyToNickname: replyToMsg.value?.fromMe ? '我' : null } : {}
      messages.value.push({
        id: msgId,
        content,
        fromMe: true,
        senderId: userStore.userInfo?.id,
        createTime: fmt(new Date().toISOString()),
        sendTs: Date.now(),
        ...replyTo,
      })
    } else {
      const res = await sendApi(currentConv.value.peer?.id, content, replyId)
      msgId = res?.msgId || Date.now()
      messages.value.push({
        id: msgId,
        content,
        fromMe: true,
        createTime: fmt(new Date().toISOString()),
        status: 0,
        sendTs: Date.now(),
        replyToId: replyId,
        replyToContent: replyId ? (replyContent || '(已撤回)') : null,
      })
    }
    replyToMsg.value = null
    scrollBottom()
    // 刷新会话/群列表
    if (currentKind.value === 'group') loadGroups()
    else await loadConvs()
  } catch {
    draft.value = content
  }
}

/** 按 currentKind 撤回(群撤回需 groupId 定位) */
async function doRecall(m) {
  if (m._recallBusy) return
  m._recallBusy = true
  const before = { ...m }
  try {
    if (currentKind.value === 'group') await groupRecallApi(m.id)
    else await recallApi(m.id)
    if (currentKind.value === 'group') await loadGroupHistory()
    else await loadHistory()
    ElMessage.success('已撤回')
  } catch {
    Object.assign(m, before)
  } finally {
    setTimeout(() => { m._recallBusy = false }, 1000)
  }
}

/** 按 currentKind 标记已读 */
async function doMarkRead() {
  if (currentKind.value === 'group') {
    await groupMarkReadApi(currentGroup.value.id)
    groups.value = groups.value.map((x) => (x.id === currentGroup.value.id ? { ...x, myUnread: 0 } : x))
  } else if (currentConv.value?.peer?.id) {
    await markRead(currentConv.value.peer.id)
  }
}

// 会话头显示名:单聊=对方昵称,群聊=群名
const headTitle = computed(() => {
  if (currentKind.value === 'group') return currentGroup.value?.name || '群聊'
  return currentConv.value?.peer?.nickname || currentConv.value?.peer?.username || '会话'
})
const headSub = computed(() => {
  if (currentKind.value === 'group') return `${currentGroup.value?.memberCount || 0} 人`
  return ''
})

async function loadHistory() {
  if (!currentConv.value) return
  const data = await historyApi(currentConv.value.peer?.id, null, 50)
  // 给历史消息补 sendTs(用 createTime 换算),让刷新后"撤回按钮"也能正确按 8s 窗口显示
  messages.value = data.list.map((m) => {
    const sendTs = m.createTime ? new Date(m.createTime).getTime() : 0
    return { ...m, createTime: fmt(m.createTime), sendTs }
  })
  scrollBottom()
}

async function sendMsg() {
  if (!draft.value.trim() || !currentConv.value) return
  const content = draft.value.trim()
  const replyId = replyToMsg.value?.id || null
  draft.value = ''
  try {
    const res = await sendApi(currentConv.value.peer?.id, content, replyId)
    // 本地追加:我刚发的,对方尚未读 → status=0(未读)
    // WS 也会推给我(同端去重),但先本地补一条让界面即时响应
    // 用后端真实 msgId(撤回按钮要拿它调 DELETE /chat/message/{id})
    const msgId = res?.msgId || Date.now()
    messages.value.push({
      id: msgId,
      content,
      fromMe: true,
      createTime: fmt(new Date().toISOString()),
      status: 0,
      sendTs: Date.now(),
      replyToId: replyId,
      replyToContent: replyId ? (replyToMsg.value?.content || '(已撤回)') : null,
    })
    replyToMsg.value = null
    scrollBottom()
    // 刷新会话列表(我这条不给自己加未读角标,只是对方那边 +1)
    await loadConvs()
  } catch {
    draft.value = content
  }
}

/** 8s 内、非撤回、我发的消息才可撤(服务端窗口也判,防客户端绕过) */
function canRecall(m) {
  return m.fromMe && !m.recalled && m.sendTs && (Date.now() - m.sendTs < 8_000)
}

async function recallMsg(m) {
  if (m._recallBusy) return // 防连点:第一次请求在途时第二次直接忽略
  m._recallBusy = true
  // 撤回前快照:失败则回滚,不假装已撤
  const before = { ...m }
  try {
    await recallApi(m.id)
    // DB 为真相源:重拉一次,后端已把撤回消息 content 置空、recalled=true
    await loadHistory()
    ElMessage.success('已撤回')
  } catch {
    Object.assign(m, before)
  } finally {
    // 窗口 8s:1s 后解锁,防"点一次没反应再点"的误操作
    setTimeout(() => { m._recallBusy = false }, 1000)
  }
}

function setReply(m) {
  replyToMsg.value = m
}

function cancelReply() {
  replyToMsg.value = null
}

// WS:对方撤回 → 本地把那条气泡置灰
function onChatRecalled(payload) {
  // payload: { convId, msgId, senderId }
  if (!currentConv.value || currentConvId.value !== payload.convId) return
  messages.value = messages.value.map((m) =>
    m.id === payload.msgId ? { ...m, recalled: true, content: '', replyToContent: null } : m
  )
}

// 拉「我关注的」(按 uid=我),首次展开时加载
async function loadFollowing() {
  if (followList.value.length || followLoadingMore.value) return
  followLoadingMore.value = true
  try {
    const me = userStore.userInfo?.id
    if (!me) return
    const data = await followingApi(me, null, 10)
    followList.value = (data?.list || []).filter(Boolean)
    followCursor.value = data?.hasMore ? data?.nextCursor : null
  } finally {
    followLoadingMore.value = false
  }
}

// 「查看更多」:接着 cursor 拉
async function followMore() {
  if (!followCursor.value || followLoadingMore.value) return
  followLoadingMore.value = true
  try {
    const me = userStore.userInfo?.id
    const data = await followingApi(me, followCursor.value, 10)
    followList.value = [...followList.value, ...(data?.list || [])]
    followCursor.value = data?.hasMore ? data?.nextCursor : null
  } finally {
    followLoadingMore.value = false
  }
}

async function openUserConv(u) {
  showNew.value = false
  currentKind.value = 'dm'
  currentGroup.value = null
  // 找已有会话或创建
  const exist = convs.value.find((c) => c.peer?.id === u.id)
  if (exist) {
    await openConv(exist)
    return
  }
  // 新会话:用 peerId 占位,send 时后端 upsert conv
  currentConv.value = { convId: 0, peer: u, unread: 0 }
  currentConvId.value = 0
  messages.value = []
  // 发第一条会建立 conv;WS 推来后 loadConvs 刷新
}

function goPeer(peer) {
  // 会话头像 → 他人主页(@click.stop 避免误触发展开会话)
  if (peer?.id) router.push(`/user/${peer.id}`)
}

// 从 URL ?to={uid} 直接定位到与该用户的会话(他人主页「发私信」入口用)
async function openByPeerId(peerId) {
  currentKind.value = 'dm'
  currentGroup.value = null
  const exist = convs.value.find((c) => c.peer?.id === peerId)
  if (exist) {
    await openConv(exist)
    return
  }
  // 没有现成会话:拉对方资料占位,发第一条时后端 upsert 建 conv
  try {
    const peer = await userCard(peerId)
    currentConv.value = { convId: 0, peer, unread: 0 }
    currentConvId.value = 0
    messages.value = []
  } catch {
    ElMessage.error('打开会话失败')
  }
}

function scrollBottom() {
  nextTick(() => {
    if (msgListEl.value) {
      msgListEl.value.scrollTop = msgListEl.value.scrollHeight
    }
  })
}

// WS 实时消息(别人发给我的)
function onChat(payload, msg) {
  const isCurrent = currentConv.value && payload.convId && currentConvId.value === payload.convId
  if (isCurrent) {
    messages.value.push({
      id: payload.msgId,
      content: payload.content,
      fromMe: false,
      senderId: payload.senderId,
      createTime: fmt(new Date().toISOString()),
      status: 1, // 我正开着这个会话,来消息即视为已读
    })
    scrollBottom()
    // 我当前正在看 → 这条对我是"已读",清掉我侧的未读角标
    clearLocalUnread(payload.convId)
    if (currentConv.value?.peer?.id) markRead(currentConv.value.peer.id)
  }
  // 无论如何刷会话列表(非当前会话的要冒红点)
  loadConvs()
}

// 本地清掉某个会话的未读角标(我已读到)
function clearLocalUnread(convId) {
  convs.value = convs.value.map((x) => (x.convId === convId ? { ...x, unread: 0 } : x))
}

// 已读回执:对方读了我发的消息 → 我这边把这些气泡翻成"已读"
function onChatRead(payload) {
  // payload: { convId, readerId }
  if (payload.readerId !== currentConv.value?.peer?.id) return
  messages.value = messages.value.map((m) =>
    m.fromMe ? { ...m, status: 1 } : m
  )
}

// ------------------------------------------------ 群聊 WS handlers ------------------------------------------------

// 群消息推来(payload: { groupId, msgId, content, senderId })
async function onGroupMsg(payload) {
  const isCurrent = currentKind.value === 'group' && currentGroup.value?.id === payload.groupId
  if (isCurrent) {
    const nick = await batchNicknames([payload.senderId]).then((m) => m.get(payload.senderId))
    messages.value.push({
      id: payload.msgId,
      content: payload.content,
      fromMe: false,
      senderId: payload.senderId,
      senderNickname: nick,
      createTime: fmt(new Date().toISOString()),
      sendTs: Date.now(),
    })
    scrollBottom()
    groups.value = groups.value.map((x) => (x.id === payload.groupId ? { ...x, myUnread: 0 } : x))
    doMarkRead()
  }
  // 非当前群:冒红点
  loadGroups()
}

// 群撤回(payload: { groupId, msgId, senderId })
function onGroupRecalled(payload) {
  if (currentKind.value === 'group' && currentGroup.value?.id === payload.groupId) {
    messages.value = messages.value.map((m) =>
      m.id === payload.msgId ? { ...m, recalled: true, content: '', replyToContent: null } : m
    )
  }
}

// 群已读回执(payload: { groupId, readerId, msgId }):reader 读了我发的 → 翻成已读
function onGroupRead(payload) {
  if (currentKind.value !== 'group' || currentGroup.value?.id !== payload.groupId) return
  messages.value = messages.value.map((m) =>
    m.fromMe ? { ...m, status: 1 } : m
  )
}

onMounted(async () => {
  await loadConvs()
  loadGroups() // 拉群组
  loadFollowing() // 预拉「我关注的」,展开时可直接显示
  ws.on('chat', onChat)
  ws.on('chat_read', onChatRead)
  ws.on('chat_recalled', onChatRecalled)
  ws.on('group_msg', onGroupMsg)
  ws.on('group_recalled', onGroupRecalled)
  ws.on('group_read', onGroupRead)
  ws.connect('/ws/chat')
  // 从他人主页「发私信」进来:定位到与该用户的会话(?to={uid})
  const toId = route.query.to
  if (toId) await openByPeerId(Number(toId))
})

onBeforeUnmount(() => {
  ws.off('chat', onChat)
  ws.off('chat_read', onChatRead)
  ws.off('chat_recalled', onChatRecalled)
  ws.off('group_msg', onGroupMsg)
  ws.off('group_recalled', onGroupRecalled)
  ws.off('group_read', onGroupRead)
  // 保留连接,其他页面可能复用;真要关用 ws.close()
})
</script>

<style scoped>
.chat-shell {
  height: calc(100vh - 60px);
}
.chat-conv {
  border-right: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
}
.conv-title {
  padding: 16px;
  font-weight: 700;
  font-size: 16px;
}
.conv-list {
  flex: 1;
  overflow-y: auto;
}
/* 群组/私信分区 */
.conv-group {
  flex: 1;
  overflow-y: auto;
  min-height: 0;
}
.conv-sec-label {
  padding: 10px 16px 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 600;
  letter-spacing: 0.5px;
}
.conv-sec {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
/* 发起群聊弹窗 */
.cg-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.cg-row label {
  font-size: 13px;
  color: var(--el-text-color-regular);
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
}
.cg-members {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 240px;
  overflow-y: auto;
}
.cg-member {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px;
  border-radius: 6px;
}
.cg-member:hover {
  background: var(--el-fill-color-light);
}
.cg-m-avatar {
  flex-shrink: 0;
}
.cg-m-name {
  font-size: 13px;
}
.cg-empty {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding: 8px;
}
.conv-empty {
  padding: 12px 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.g-avatar {
  background: var(--el-color-primary-light-8);
  display: flex;
  align-items: center;
  justify-content: center;
}
.g-avatar-text {
  font-weight: 700;
  color: var(--el-color-primary);
  font-size: 18px;
}
.conv-item {
  display: flex;
  gap: 10px;
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.conv-item.active {
  background: var(--el-color-primary-light-9);
}
.conv-item.active .conv-name {
  color: var(--el-color-primary);
}
.conv-avatar {
  cursor: pointer;
}
.conv-avatar:hover {
  opacity: 0.85;
}
.conv-info {
  flex: 1;
  overflow: hidden;
}
.conv-name {
  font-weight: 500;
}
.conv-preview {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-top: 4px;
}
.conv-new {
  margin: 12px 16px;
}
/* 「我关注的」区块 */
.follow-sec {
  border-top: 1px solid var(--el-border-color-lighter);
}
.follow-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  cursor: pointer;
  font-weight: 600;
  font-size: 14px;
}
.follow-head:hover {
  background: var(--el-fill-color-light);
}
.follow-head .el-icon {
  transition: transform 0.15s;
}
.follow-head .el-icon.is-rot {
  transform: rotate(180deg);
}
.follow-list {
  max-height: 240px;
  overflow-y: auto;
}
.follow-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  cursor: pointer;
}
.follow-item:hover {
  background: var(--el-fill-color);
}
.follow-info { flex: 1; overflow: hidden; }
.f-name {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.f-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.follow-empty {
  padding: 12px 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.follow-more {
  text-align: center;
  padding: 10px;
  color: var(--el-color-primary);
  font-size: 13px;
  cursor: pointer;
}
.chat-main {
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color-page);
}
.msg-head {
  padding: 14px 20px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-weight: 600;
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.msg-sub {
  font-weight: 400;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 群消息发送者昵称(靠左消息上方) */
.msg-sender {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 2px;
  padding-left: 4px;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.msg-row {
  display: flex;
  justify-content: flex-start;   /* 对方消息靠左 */
}
.msg-row.me {
  justify-content: flex-end;     /* 我方消息靠右 */
}
.msg-bubble {
  max-width: 60%;
  background: var(--el-color-primary);
  color: #fff;
  padding: 10px 14px;
  border-radius: 12px;
}
.msg-row:not(.me) .msg-bubble {
  background: var(--el-fill-color);
  color: var(--el-text-color-primary);
}
.msg-text {
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-recalled-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-style: italic;
}
.msg-reply-quote {
  border-left: 3px solid var(--el-color-primary);
  background: var(--el-fill-color-light);
  padding: 4px 8px;
  margin-bottom: 6px;
  border-radius: 4px;
  font-size: 13px;
  max-width: 320px;
}
.msg-row:not(.me) .msg-reply-quote {
  border-left-color: var(--el-color-primary-light-3);
}
.msg-reply-from {
  font-weight: 600;
  color: var(--el-color-primary);
  margin-bottom: 2px;
}
.msg-reply-text {
  color: var(--el-text-color-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.msg-recall-btn {
  cursor: pointer;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.7);
  transition: color 0.15s;
}
.msg-recall-btn:hover {
  color: #fff;
}
.msg-reply-btn {
  cursor: pointer;
  font-size: 14px;
  margin: 0 6px;
  opacity: 0;
  transition: opacity 0.15s;
}
.msg-row:hover .msg-reply-btn {
  opacity: 1;
}
.reply-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--el-fill-color-light);
  border-left: 3px solid var(--el-color-primary);
  border-radius: 4px;
  padding: 6px 10px;
  font-size: 13px;
}
.reply-label {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.reply-preview {
  color: var(--el-text-color-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}
.msg-time {
  font-size: 11px;
  opacity: 0.7;
  margin-top: 4px;
}
.msg-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  margin-top: 4px;
}
.msg-foot .msg-time {
  margin-top: 0;
}
.msg-read {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.6);
}
.msg-read.read {
  color: #b3f0c8;   /* 已读:亮绿,在白底对方气泡上是深色 */
}
.msg-row:not(.me) .msg-read {
  color: var(--el-text-color-secondary);
}
.msg-row:not(.me) .msg-read.read {
  color: var(--el-color-success);
}
.msg-input {
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--el-bg-color);
}
.msg-input .el-button {
  align-self: flex-end;
}
.chat-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-secondary);
}
.search-list {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.search-item {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
}
.search-item:hover {
  background: var(--el-fill-color);
}
.s-name { font-weight: 500; }
.s-sub { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
