<template>
  <!--
    单个圈子行(设计稿场景 3,C6-C10)。
    手机是全宽行、桌面是卡片,形态差异全部在媒体查询里,模板只有一套。

    整行是<button>而不是 div @click:键盘用户 Tab 得到,读屏器知道它可点。
    右侧操作只有两个态(C8):
      未加入 -> 「加入」(可点,按钮)
      已加入 -> 「已加入」(灰底状态,点整行进详情看/退/管)
    退出/解散不放卡片上 —— 扫列表场景不该给破坏性动作,收在详情页(用户拍板)。
  -->
  <div class="crow">
    <button type="button" class="crow__open" @click="$emit('open', circle.id)">
      <!--
        F2 修法走「首字彩色圆」(C6):CircleVO 没有 avatarUrl,circle 表也没这列,
        v-if="avatarUrl" 是永远为假的分支。InitialAvatar 同时兜住了
        「将来后端补了头像」的 case(src 传进来就用图,图裂了回落首字)。
        seed 传圈 id:同一个圈的颜色永远稳定,名字改了色也不跳。
      -->
      <InitialAvatar
        class="crow__avatar"
        :src="circle.avatarUrl ? resolveThumb(circle.avatarUrl) : ''"
        :seed="circle.id"
        :name="circle.name"
        :size="48"
      />

      <span class="crow__info">
        <span class="crow__name">
          {{ circle.name }}
          <!-- C8:身份标签。只在已加入时出现,说明的是「我」在圈里的角色 -->
          <span v-if="roleTag" class="crow__tag" :class="`crow__tag--${roleTag.kind}`">
            {{ roleTag.text }}
          </span>
        </span>
        <span class="crow__sub">{{ subText }}</span>
      </span>
    </button>

    <div class="crow__act">
      <button
        v-if="!circle.joined"
        type="button"
        class="crow__join"
        :aria-label="`加入 ${circle.name}`"
        @click.stop="$emit('join', circle)"
      >
        加入
      </button>
      <!--
        已加入的圈**不再渲染右侧标签**（2026-10-21）。
        改版前这里是一个 min-width:72px 的灰胶囊「已加入」，把卡片右侧
        切掉约 96px，副行拿到「2 成员 · 10 月 10 日有新帖」这种句子就被
        ellipsis 截成「10 月 10 日...」，最关键的「有没有新帖」正好被吃掉。
        而「已加入」是状态不是操作：卡片本身就只出现在「我的」分组里
        （group 已经表达了「我加入过」），再加上角色标签（圈主/管理/成员）
        也已经在标题行给出，右侧那个灰胶囊提供不了额外信息。
        去掉后副行拿到整行宽度，长文案不再被截。
      -->
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { resolveThumb } from '@/utils/resolve'
import { relTime } from '@/utils/relTime'

const props = defineProps({
  circle: { type: Object, required: true },
})

defineEmits(['open', 'join'])

/**
 * 副行(C7/C10):人数 + 最近动态,不再写「圈主: 3420232712」。
 * 谁建的圈不影响「进不进」,活不活才影响 —— 用最新一帖的时间回答后者。
 * 数据来自列表接口批量查回的 lastPostAt,不是 N+1。
 */
const subText = computed(() => {
  const n = Number(props.circle.memberCount || 0)
  const shown = n > 999 ? '999+' : String(n)
  const rel = relTime(props.circle.lastPostAt)
  return rel ? `${shown} 成员 · ${rel}有新帖` : `${shown} 成员 · 还没有帖子`
})

/** 我的身份小标签(myRole:1 圈主 / 2 管理 / 3 普通成员) */
const roleTag = computed(() => {
  const m = { 1: { text: '圈主', kind: 'owner' }, 2: { text: '管理', kind: 'mgr' }, 3: { text: '成员', kind: 'member' } }
  return props.circle.joined ? m[props.circle.myRole] || null : null
})
</script>

<style scoped>
.crow {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  min-height: 72px;
  padding: var(--ys-space-3) var(--ys-gutter-phone);
  border-top: 1px solid var(--ys-border-soft);
  /* 进 grid 列时不被内容撑宽,否则卡片互相挤出重叠 */
  min-width: 0;
}

.crow__open {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  flex: 1;
  min-width: 0;
  align-self: stretch;
  padding: 0;
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  text-align: left;
  cursor: pointer;
}

.crow__open:active {
  opacity: 0.7;
}

.crow__info {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-1);
  flex: 1;
  min-width: 0;
}

.crow__name {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
  overflow: hidden;
  white-space: nowrap;
}

.crow__tag {
  flex: none;
  padding: 1px var(--ys-space-2);
  border-radius: var(--ys-radius-full);
  font-size: var(--ys-text-xs);
  font-weight: var(--ys-weight-medium);
  line-height: var(--ys-lh-xs);
}

.crow__tag--owner {
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}

.crow__tag--mgr {
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
}

.crow__tag--member {
  background: transparent;
  color: var(--ys-muted);
  padding-left: 0;
}

.crow__sub {
  font-size: var(--ys-text-sm);
  color: var(--ys-meta);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.crow__act {
  flex: none;
}

/* 「加入」:44px 热区、最小宽 72 的胶囊 */
.crow__join {
  min-width: 72px;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
  background: transparent;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  color: var(--ys-accent);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.crow__join:hover {
  background: var(--ys-accent-soft);
}

.crow__join:active {
  opacity: 0.7;
}


/*
  桌面(>=1024px):行变卡片。
  C9:不再定高 118 —— 那是给不存在的信息预留的高度。
  三段(头像+名 / 副行 / 操作)由内容撑开,同排高度由 grid 拉齐底边。
  头像+名字一行、副行整行:用 display:contents 把 info 拆进网格,
  一套 DOM 两个排法,模板不分叉。
*/
@media (min-width: 1024px) {
  .crow {
    flex-direction: column;
    align-items: stretch;
    gap: var(--ys-space-2);
    min-height: 0;
    padding: var(--ys-space-4);
    border: 1px solid var(--ys-border);
    border-radius: var(--ys-radius-xl);
    background: var(--ys-surface);
    box-shadow: var(--ys-shadow-1);
    transition:
      box-shadow var(--ys-motion-fast) var(--ys-ease-out),
      border-color var(--ys-motion-fast) var(--ys-ease-out);
  }

  .crow:hover {
    border-color: var(--ys-border-control);
    box-shadow: var(--ys-shadow-2);
  }

  .crow__open {
    display: grid;
    grid-template-columns: auto 1fr;
    grid-template-rows: auto auto;
    column-gap: var(--ys-space-3);
    row-gap: var(--ys-space-2);
    align-items: center;
  }

  .crow__info {
    display: contents;
  }

  .crow__avatar {
    grid-column: 1;
    grid-row: 1;
  }

  .crow__name {
    grid-column: 2;
    grid-row: 1;
  }

  .crow__sub {
    grid-column: 1 / -1;
    grid-row: 2;
    white-space: normal;
  }

  .crow__act {
    align-self: end;
    display: flex;
    justify-content: flex-end;
  }

  .crow__join {
    min-width: 60px;
    padding: 0 var(--ys-space-2);
    font-size: var(--ys-text-sm);
  }
}
</style>
