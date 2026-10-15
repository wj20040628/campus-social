<template>
  <div class="cl">
    <!--
      破冰额度条(Chat.vue.md §1.1 行 2)。
      quota 为 null 时**整条不渲染** —— 拉取失败时给「今天还能打招呼 4 次」
      是撒谎,用户按 4 行事结果第 5 次被拒,会认定是 bug。
    -->
    <!--
      方案 A:平时完全不显示,只剩 ≤2 次时才出现黄色提示条(见 Chat.vue.md §1.1)。
      这样平时列表干净,快用完时给预期,不至于发到一半突然被拒以为坏了。
      quota 为 null(拉取失败)时整条不渲染——撒谎说"还能 4 次"比不显示更糟。
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
        陌生人消息折叠分组。**默认收起**。
        v-if 而不是 v-show:整组为空时整个分组头也不该出现 ——
        「陌生人消息 0」是个没有信息量的标题。
      -->
      <section v-if="strangerList.length" class="grp">
        <button
          type="button"
          class="grp__head grp__head--btn"
          :aria-expanded="strangerOpen"
          @click="strangerOpen = !strangerOpen"
        >
          <el-icon :size="20"><ChatDotRound /></el-icon>
          <span class="grp__name">陌生人消息</span>
          <span class="grp__count ys-num">{{ strangerList.length }}</span>
          <el-icon :size="20" class="grp__caret" :class="{ 'is-open': strangerOpen }">
            <CaretBottom />
          </el-icon>
        </button>

        <!--
          展开动画动 max-height 而不是同时旋转图标:
          图标旋转 + 高度跳变同时出现会让分组「抖一下」(§4 明确禁止)。
          这里只动 max-height,图标旋转放在同一 transition 里但幅度小。
        -->
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
          <span class="grp__count ys-num">{{ convs.length }}</span>
        </h2>
        <ul>
          <li
            v-for="c in convs"
            :key="c.convId"
            class="crow"
            :class="{ 'is-active': activeId === c.convId }"
          >
            <button type="button" class="crow__btn" @click="$emit('open', c)">
              <button
                type="button"
                class="crow__avatar crow__avatar--link"
                :aria-label="`查看 ${peerName(c)} 的主页`"
                @click.stop="$emit('peer', c.peer?.id)"
              >
                {{ c.peer?.nickname?.slice(0, 1) || '?' }}
                <UnreadDot v-if="c.unread" :count="c.unread" noun="条未读" />
              </button>
              <span class="crow__info">
                <button
                  type="button"
                  class="crow__name crow__name--link"
                  :aria-label="`查看 ${peerName(c)} 的主页`"
                  @click.stop="$emit('peer', c.peer?.id)"
                >
                  {{ peerName(c) }}
                </button>
                <span class="crow__preview">{{ c.lastPreview || '还没有消息' }}</span>
              </span>
              <span class="crow__time ys-num">{{ c.timeText }}</span>
            </button>
          </li>
        </ul>
      </section>

      <!--
        群组分组:头像改方形(--ys-radius-lg),与私信的圆形区分。
        **整段始终渲染** —— 新建群聊入口要靠它触达;空群时不放空列表,
        只显示标题 + 「＋」按钮 + 一句引导,否则没人告诉用户「群从哪来」。
      -->
      <section class="grp">
        <h2 class="grp__head">
          <span class="grp__name">群组</span>
          <span class="grp__count ys-num">{{ groups.length }}</span>
          <button
            type="button"
            class="grp__add"
            aria-label="新建群聊"
            @click="$emit('create-group')"
          >＋</button>
        </h2>
        <!-- 群搜索:纯本地过滤我加入的群名,不发网络请求 -->
        <input
          v-if="groups.length"
          v-model="groupKw"
          type="search"
          class="grp__search"
          placeholder="搜索群聊"
          aria-label="搜索群聊"
        />
        <ul v-if="filteredGroups.length">
          <li
            v-for="g in filteredGroups"
            :key="g.id"
            class="crow"
            :class="{ 'is-active': activeKind === 'group' && activeId === g.id }"
          >
            <button type="button" class="crow__btn" @click="$emit('open-group', g)">
              <span class="crow__avatar crow__avatar--square">
                {{ g.name?.slice(0, 1) || '群' }}
                <UnreadDot v-if="g.myUnread" :count="g.myUnread" noun="条未读" />
              </span>
              <span class="crow__info">
                <span class="crow__name">{{ g.name }}</span>
                <span class="crow__preview">{{ g.lastPreview || '还没有消息' }}</span>
              </span>
              <span class="crow__time ys-num">{{ g.timeText }}</span>
            </button>
          </li>
        </ul>
        <p v-else-if="groups.length" class="grp__empty">没有叫「{{ groupKw }}」的群</p>
        <p v-else class="grp__empty">还没有群，点右上角＋建一个</p>
      </section>
    </template>
  </div>
</template>

<script setup>
/**
 * 会话列表(Chat.vue.md §1.1)
 *
 * 「我关注的」区块在**移动端已删除**(§1.1):375px 下要嵌两层折叠,
 * 认知负荷超过 4 项上限。找人改走 /search。
 * 桌面端(≥1024px)侧栏有空间,由 Chat.vue 在双栏分支里另行渲染。
 */
import { ref, computed } from 'vue'
import { ChatDotRound, CaretBottom } from '@element-plus/icons-vue'
import UnreadDot from '@/components/mobile/UnreadDot.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

const props = defineProps({
  convs: { type: Array, default: () => [] },
  groups: { type: Array, default: () => [] },
  strangerList: { type: Array, default: () => [] },
  quota: { type: Number, default: null },
  loading: { type: Boolean, default: false },
  activeId: { type: [Number, String], default: null },
  activeKind: { type: String, default: 'dm' },
})

defineEmits(['open', 'open-group', 'peer', 'browse', 'ignore-stranger', 'ignore-all-stranger', 'create-group'])

const strangerOpen = ref(false)
const groupKw = ref('')

/** 群搜索:本地过滤我加入的群名(大小写不敏感) */
const filteredGroups = computed(() => {
  const kw = groupKw.value.trim().toLowerCase()
  if (!kw) return props.groups
  return props.groups.filter((g) => (g.name || '').toLowerCase().includes(kw))
})

const allEmpty = computed(
  () =>
    !props.loading &&
    props.convs.length === 0 &&
    props.groups.length === 0 &&
    props.strangerList.length === 0,
)

/**
 * 昵称降级链:昵称 -> 用户名 -> 「同学」。
 * 直接用 nickname 会让部分条目渲染成空白 —— 那正是「看起来像坏了」的形态。
 */
function peerName(c) {
  return c.peer?.nickname || c.peer?.username || '同学'
}
</script>

<style scoped src="./ChatConvList.css"></style>
