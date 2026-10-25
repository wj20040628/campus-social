<template>
  <div class="cl">
    <!--
      搭话规则 ⓘ(2026-10-25):原先是页顶常驻横幅,被用户评「难看」。
      收进气泡挂列表容器右上角——空态(还没有会话,最需要知道规则的人)
      与有会话时都常驻,想看的人悬停即得,不想看的人零打扰。
      文案与 campus.daily-new-peer-limit / per-peer-daily-limit 对应。
    -->
    <el-tooltip placement="bottom-end" :show-after="150" content="不用加好友就能搭话：每天最多向 8 位新同学发起会话，给同一位同学每天最多发 15 条">
      <button type="button" class="cl__rulebtn" aria-label="搭话规则说明">
        <el-icon :size="14"><QuestionFilled /></el-icon>
      </button>
    </el-tooltip>

    <!--
      破冰额度条(方案 A:平时不显示,只剩 ≤2 次才出现)。
      quota 为 null(拉取失败)时整条不渲染 —— 撒谎说「还能 4 次」比不显示更糟。
    -->
    <div v-if="quota !== null && quota <= 2" class="quota">
      <span class="quota__text">
        今天还能主动打招呼
        <span class="quota__num ys-num">{{ quota }}</span>
        次
      </span>
      <el-tooltip content="每天可向有限个新同学主动搭话，对同一陌生人也有限制" placement="top">
        <button type="button" class="quota__why" aria-label="额度规则说明">规则</button>
      </el-tooltip>
    </div>

    <SkeletonList v-if="loading" :count="6" variant="row" />

    <StateBlock
      v-else-if="allEmpty"
      variant="empty"
      title="还没有人找你说话"
      description="在圈子里发一条,或者去看看大家都在找什么"
      action-label="去逛逛"
      :icon="ChatDotRound"
      @action="$emit('browse')"
    />

    <template v-else>
      <!--
        搜索:一份管私信和群聊(原先是塞在群组分组里、只搜群名)。
        放在列表顶部而不是顶栏:搜索的对象就在下面这个列表里,
        手不用先去顶栏再回来。
      -->
      <div class="cl__search">
        <svg class="cl__search-ic" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2" />
          <path d="M20 20l-3.5-3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
        </svg>
        <input
          v-model="kw"
          type="search"
          class="cl__search-inp"
          placeholder="搜索私信、群聊"
          aria-label="搜索私信和群聊"
        />
        <button v-if="kw" type="button" class="cl__search-clear" aria-label="清空搜索" @click="kw = ''">×</button>
      </div>

      <!-- 陌生人消息:默认收起,整组为空时连标题都不出现 -->
      <section v-if="strangerList.length" class="grp">
        <button
          type="button"
          class="grp__head grp__head--btn"
          :aria-expanded="strangerOpen"
          @click="strangerOpen = !strangerOpen"
        >
          <el-icon :size="18"><ChatDotRound /></el-icon>
          <span class="grp__name">陌生人消息</span>
          <span class="grp__count ys-num">{{ strangerList.length }}</span>
          <el-icon :size="18" class="grp__caret" :class="{ 'is-open': strangerOpen }">
            <CaretBottom />
          </el-icon>
        </button>

        <div class="grp__body" :class="{ 'is-open': strangerOpen }">
          <ul>
            <li v-for="s in strangerList" :key="s.id" class="srow">
              <span class="srow__avatar" aria-hidden="true">{{ s.nickname?.slice(0, 1) || '?' }}</span>
              <span class="srow__info">
                <span class="srow__name">{{ s.nickname }}</span>
                <span class="srow__preview">{{ s.content }}</span>
              </span>
              <button
                type="button"
                class="srow__act"
                :aria-label="`忽略 ${s.nickname} 的消息`"
                @click="$emit('ignore-stranger', s.id)"
              >
                忽略
              </button>
            </li>
          </ul>
          <button type="button" class="grp__ignore-all" @click="$emit('ignore-all-stranger')">
            全部忽略
          </button>
        </div>
      </section>

      <!-- 私信分组 -->
      <section class="grp">
        <h2 class="grp__head">
          <span class="grp__name">私信</span>
          <span class="grp__count ys-num">{{ filteredConvs.length }}</span>
        </h2>
        <ul>
          <li v-for="c in filteredConvs" :key="'dm-' + c.convId">
            <ChatRowSwipe
              :open="openKey === 'dm-' + c.convId"
              :pinned="!!c.pinned"
              :muted="!!c.muted"
              @update:open="(v) => setOpen('dm-' + c.convId, v)"
              @action="(a) => onAction(c, 'dm', a)"
            >
              <div
                class="crow"
                :class="{ 'is-active': activeKind === 'dm' && activeId === c.convId, 'is-pinned': !!c.pinned }"
                role="button"
                tabindex="0"
                :aria-label="`打开与 ${peerName(c)} 的会话`"
                @click="$emit('open', c)"
                @keydown.enter.prevent="$emit('open', c)"
                @keydown.space.prevent="$emit('open', c)"
                @contextmenu="onRowContext($event, c, 'dm')"
              >
                <button
                  type="button"
                  class="crow__av"
                  tabindex="-1"
                  :aria-label="`查看 ${peerName(c)} 的主页`"
                  @click.stop="$emit('peer', c.peer?.id)"
                >{{ c.peer?.nickname?.slice(0, 1) || '?' }}</button>
                <span class="crow__info">
                  <span class="crow__name" :class="{ 'is-unread': c.unread > 0 }">{{ peerName(c) }}</span>
                  <span class="crow__pre" :class="{ 'is-unread': c.unread > 0 }">
                    {{ c.lastPreview || '还没有消息' }}
                  </span>
                </span>
                <span class="crow__side">
                  <span class="crow__time" :class="{ 'is-unread': c.unread > 0 }">{{ c.timeText }}</span>
                  <span v-if="c.unread > 0" class="crow__badge ys-num">{{ c.unread > 99 ? '99+' : c.unread }}</span>
                  <svg v-else-if="c.muted" class="crow__mute" viewBox="0 0 24 24" fill="none" aria-label="已免打扰">
                    <path d="M18 16H6l1.6-2.2V10a4.4 4.4 0 0 1 8.8 0v3.8z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                    <path d="M4 4l16 16" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                  </svg>
                </span>
              </div>
            </ChatRowSwipe>
          </li>
        </ul>
        <p v-if="!filteredConvs.length && kw" class="grp__empty">没有匹配的私信</p>
      </section>

      <!--
        群组分组:始终渲染 —— 建群入口要靠它触达。
        空群时只放标题 + 「＋」+ 一句引导,否则没人告诉用户「群从哪来」。
      -->
      <section class="grp">
        <h2 class="grp__head">
          <span class="grp__name">群组</span>
          <span class="grp__count ys-num">{{ filteredGroups.length }}</span>
          <span class="grp__spacer"></span>
          <button
            type="button"
            class="grp__add"
            aria-label="新建群聊"
            @click="$emit('create-group')"
          >
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </button>
        </h2>
        <ul v-if="filteredGroups.length">
          <li v-for="g in filteredGroups" :key="'grp-' + g.id">
            <ChatRowSwipe
              :open="openKey === 'grp-' + g.id"
              :pinned="!!g.pinned"
              :muted="!!g.muted"
              @update:open="(v) => setOpen('grp-' + g.id, v)"
              @action="(a) => onAction(g, 'group', a)"
            >
              <div
                class="crow"
                :class="{ 'is-active': activeKind === 'group' && activeId === g.id, 'is-pinned': !!g.pinned }"
                role="button"
                tabindex="0"
                :aria-label="`打开群 ${g.name || '群聊'}`"
                @click="$emit('open-group', g)"
                @keydown.enter.prevent="$emit('open-group', g)"
                @keydown.space.prevent="$emit('open-group', g)"
                @contextmenu="onRowContext($event, g, 'group')"
              >
                <span class="crow__av crow__av--sq">{{ g.name?.slice(0, 1) || '群' }}</span>
                <span class="crow__info">
                  <span class="crow__name" :class="{ 'is-unread': g.myUnread > 0 }">{{ g.name }}</span>
                  <span class="crow__pre" :class="{ 'is-unread': g.myUnread > 0 }">
                    {{ g.lastPreview || '还没有消息' }}
                  </span>
                </span>
                <span class="crow__side">
                  <span class="crow__time" :class="{ 'is-unread': g.myUnread > 0 }">{{ g.timeText }}</span>
                  <span v-if="g.myUnread > 0" class="crow__badge ys-num">{{ g.myUnread > 99 ? '99+' : g.myUnread }}</span>
                  <svg v-else-if="g.muted" class="crow__mute" viewBox="0 0 24 24" fill="none" aria-label="已免打扰">
                    <path d="M18 16H6l1.6-2.2V10a4.4 4.4 0 0 1 8.8 0v3.8z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                    <path d="M4 4l16 16" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                  </svg>
                </span>
              </div>
            </ChatRowSwipe>
          </li>
        </ul>
        <p v-else class="grp__empty">
          {{ groups.length && kw ? `没有叫「${kw}」的群` : '还没有群，点右上角＋建一个' }}
        </p>
      </section>
    </template>

    <!-- 桌面端右键菜单(仅 hover 设备会打开,见 onRowContext) -->
    <ChatRowMenu
      v-model="menuOpen"
      :pos="menuPos"
      :pinned="!!menuRow?.pinned"
      :muted="!!menuRow?.muted"
      @mute="runMenu('mute')"
      @pin="runMenu('pin')"
      @delete="runMenu('delete')"
    />
  </div>
</template>

<script setup>
/**
 * 会话列表(Chat.vue.md §1.1)
 *
 * ## 2026-10-20 改版三件事
 * 1) 原生 ul 样式泄漏:此前只重置了 `.grp__body ul`,私信/群组两个分组的 ul
 *    仍带 `padding-inline-start:40px` + `list-style:disc` + `margin-block:1em`。
 *    后果:每行右推 40px(左 40 右 16 不对称)、行首多一个被误认成「未读红点」的
 *    li 圆点、分组标题与首行之间多出 14px 缝。修在 CSS 的 `.cl ul`。
 * 2) 分组标题从 56px 满铺底色收成 28px 轻标题 —— 它的权重本该低于会话内容。
 * 3) 手机端每行可左滑,露出 免打扰/置顶/删除(见 ChatRowSwipe.vue);
 *    桌面端右键同一行也出这三个动作(见 ChatRowMenu.vue)。
 *
 * 「我关注的」区块在移动端已删除(§1.1):375px 下两层折叠超过认知负荷上限,找人走 /search。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { ChatDotRound, CaretBottom, QuestionFilled } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import ChatRowSwipe from './ChatRowSwipe.vue'
import ChatRowMenu from './ChatRowMenu.vue'

const props = defineProps({
  convs: { type: Array, default: () => [] },
  groups: { type: Array, default: () => [] },
  strangerList: { type: Array, default: () => [] },
  quota: { type: Number, default: null },
  loading: { type: Boolean, default: false },
  activeId: { type: [Number, String], default: null },
  activeKind: { type: String, default: 'dm' },
})

const emit = defineEmits([
  'open', 'open-group', 'peer', 'browse',
  'ignore-stranger', 'ignore-all-stranger', 'create-group',
  'pin', 'mute', 'delete',
])

const strangerOpen = ref(false)
const kw = ref('')
/** 当前展开左滑操作的行 key;同一时刻只允许一行展开 */
const openKey = ref(null)

/* ---------------- 桌面右键菜单 ---------------- */
const menuOpen = ref(false)
const menuPos = ref({ x: 0, y: 0 })
const menuRow = ref(null)
const menuKind = ref('dm')

/**
 * 右键只在真有鼠标悬停能力的设备上接管。
 * 触屏长按也会派发 contextmenu,那一路应该留给浏览器默认行为
 * (我们自己的长按菜单在气泡上,不在会话行上)。
 */
const isHoverDevice = () =>
  typeof window !== 'undefined' &&
  window.matchMedia &&
  window.matchMedia('(hover:hover)').matches

function onRowContext(e, row, kind) {
  if (!isHoverDevice()) return
  e.preventDefault()
  // 右键时先把左滑收起来:两套入口同屏会让人搞不清当前是哪一行被选中
  openKey.value = null
  menuRow.value = row
  menuKind.value = kind
  menuPos.value = { x: e.clientX, y: e.clientY }
  menuOpen.value = true
}

function runMenu(action) {
  const row = menuRow.value
  if (row) onAction(row, menuKind.value, action)
}

/** 点菜单外的任何地方都关;判断用 closest('.rowmenu'),否则菜单项自己的点击会被吞掉 */
function onDocPointerDown(e) {
  if (!menuOpen.value) return
  if (e.target?.closest?.('.rowmenu')) return
  menuOpen.value = false
}
function onDocKey(e) {
  if (e.key === 'Escape') menuOpen.value = false
}

const hit = (s) => (s || '').toLowerCase().includes(kw.value.trim().toLowerCase())

const filteredConvs = computed(() => {
  if (!kw.value.trim()) return props.convs
  return props.convs.filter((c) => hit(peerName(c)))
})

const filteredGroups = computed(() => {
  if (!kw.value.trim()) return props.groups
  return props.groups.filter((g) => hit(g.name))
})

const allEmpty = computed(
  () =>
    !props.loading &&
    props.convs.length === 0 &&
    props.groups.length === 0 &&
    props.strangerList.length === 0,
)

/** 昵称降级链:昵称 -> 用户名 -> 「同学」。直接渲染 nickname 会出现空白条目 */
function peerName(c) {
  return c.peer?.nickname || c.peer?.username || '同学'
}

function setOpen(key, v) {
  openKey.value = v ? key : openKey.value === key ? null : openKey.value
}

/**
 * 左滑 / 右键动作。置顶/免打扰是开关(再点一次取消),交给父级乐观更新;
 * 删除要说清它是「从我的列表拿掉」—— 对方再发消息会自己回来,
 * 不提一句用户会以为聊天记录被删没了。
 */
function onAction(row, kind, action) {
  openKey.value = null
  if (action === 'pin') emit('pin', row, kind, !row.pinned)
  else if (action === 'mute') emit('mute', row, kind, !row.muted)
  else if (action === 'delete') {
    emit('delete', row, kind)
    ElMessage.success('已从列表删除，对方再发消息会重新出现')
  }
}

/** 一滚动就收起操作 —— 手指在滚、行还半开着很别扭;右键菜单同理 */
const closeOnScroll = () => {
  if (openKey.value) openKey.value = null
  if (menuOpen.value) menuOpen.value = false
}
onMounted(() => {
  window.addEventListener('scroll', closeOnScroll, { passive: true, capture: true })
  document.addEventListener('pointerdown', onDocPointerDown, true)
  document.addEventListener('keydown', onDocKey)
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', closeOnScroll, true)
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onDocKey)
})
</script>

<style scoped src="./ChatConvList.css"></style>
