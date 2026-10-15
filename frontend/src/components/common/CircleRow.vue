<template>
  <!--
    单个圈子行(设计 §1.1)。
    抽成组件的原因:同一份行结构在「我加入的」与「全部圈子」两个分组里各用一次,
    且移动端行式 / 桌面端卡片式两套形态的差异全部收在这个组件内,
    页面不需要为形态差异写分支。

    整行是<button> 而不是 div @click(设计 §7):
    el-card 渲染成 div,键盘用户 Tab 不到,读屏器也不知道它可点。
  -->
  <div class="crow">
    <!-- 整行可点区。加入按钮在下面用 @click.stop 截断冒泡 -->
    <button type="button" class="crow__open" @click="$emit('open', circle.id)">
      <img
        v-if="circle.avatarUrl"
        class="crow__avatar"
        :src="resolveUrl(circle.avatarUrl)"
        :alt="`${circle.name}的图标`"
      />
      <span v-else class="crow__avatar crow__avatar--blank" aria-hidden="true">
        <ChatDotRound />
      </span>

      <span class="crow__info">
        <span class="crow__name">{{ circle.name }}</span>
        <span class="crow__sub">{{ memberText }}</span>
      </span>
    </button>

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
      已加入:直接给「退出」按钮(仅普通成员 myRole=3)。圈主/圈管理(1/2)
      退圈后端必 422,显示灰色「已加入」不可点。
      @click.stop 截断冒泡,不触发整行的 open 跳详情。
    -->
    <button
      v-else-if="circle.myRole === 3"
      type="button"
      class="crow__leave"
      :aria-label="`退出 ${circle.name}`"
      :disabled="leaving"
      @click.stop="$emit('leave', circle)"
    >
      {{ leaving ? '退出中' : '退出' }}
    </button>
  
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CircleCheck, ChatDotRound } from '@element-plus/icons-vue'
import { resolveUrl } from '@/utils/resolve'

const props = defineProps({
  circle: { type: Object, required: true },
  /** 该圈退圈请求进行中:「退圈」按钮进入「退出中」态并禁点 */
  leaving: { type: Boolean, default: false },
})

defineEmits(['open', 'join', 'leave'])

/**
 * 成员数 999+ 封顶(设计 §3 内容超长)。
 * 不封顶的话一个万人圈子会把行撑宽,且「12345」没有信息量。
 */
const memberText = computed(() => {
  const n = Number(props.circle.memberCount || 0)
  const shown = n > 999 ? '999+' : String(n)
  const owner = props.circle.owner?.nickname
  return owner ? `${shown} 成员 · 圈主：${owner}` : `${shown} 成员`
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
  /* 桌面端进 grid(Circles.css .circle-rows)时,列宽由 grid 控制;
     不设 min-width:auto 会撑到内容最宽,三行卡片挤成一排互相重叠 */
  min-width: 0;
}

.crow__open {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  flex: 1;
  /* flex 子项默认 min-width:auto,会撑到内容最宽;卡片收窄时文本列必须能截断,
     否则名字列把「加入/退出」按钮顶出卡片右缘(桌面端卡片重叠,见 Bug4) */
  min-width: 0;
  /* 视觉高 72px 由 .crow 给,这里撑满让整行都是热区 */
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

.crow__avatar {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border-radius: var(--ys-radius-lg);
  object-fit: cover;
}

.crow__avatar--blank {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-size: var(--ys-text-xl);
}

.crow__info {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-1);
  flex: 1;
  min-width: 0;
}

.crow__name {
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
  /* 圈子名 1 行截断(设计 §3) */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.crow__sub {
  font-size: var(--ys-text-sm);
  color: var(--ys-meta);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 「加入」胶囊按钮:44px 高、最小宽 72px(设计 §1.1) */
.crow__join {
  flex-shrink: 0;
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
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    border-color var(--ys-motion-fast) var(--ys-ease-out);
}

.crow__join:hover {
  background: var(--ys-accent-soft);
}

.crow__join:active {
  opacity: 0.7;
}

.crow__join:disabled {
  border-color: var(--ys-border-control);
  color: var(--ys-meta);
  cursor: not-allowed;
}

.crow__joined {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  flex-shrink: 0;
  min-width: 72px;
  min-height: var(--ys-touch-min);
  justify-content: center;
  padding: 0 var(--ys-space-3);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
}

.crow__joined-icon {
  width: 16px;
  height: 16px;
}

/*
 * 「退出」默认安静态:灰字 + 软描边,不抢注意力;hover 才给危险色。
 * 理由:退出是低频破坏性操作。一排红按钮会把「加入」的青色稀释掉,
 * 也让「我加了哪些圈」的浏览变吵。红色留给真的要退的那一刻,
 * 且 hover 同时给文字/描边/底色三个信号,不是只变一个字色。
 */
.crow__leave {
  flex-shrink: 0;
  min-width: 72px;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-full);
  background: transparent;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  color: var(--ys-muted);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    border-color var(--ys-motion-fast) var(--ys-ease-out),
    color var(--ys-motion-fast) var(--ys-ease-out);
}

.crow__leave:hover:not(:disabled) {
  border-color: var(--ys-danger);
  background: var(--ys-danger-soft);
  color: var(--ys-danger);
}

.crow__leave:active:not(:disabled) {
  opacity: 0.7;
}

.crow__leave:disabled {
  border-color: var(--ys-border-control);
  color: var(--ys-meta);
  cursor: not-allowed;
}

/*
 * 桌面端(>=1024px):行变卡片。
 * 移动端是「发丝线分隔的全宽行」(信息密度优先,设计 §1.1);
 * 桌面端进了 4 列网格后,行式分隔线会变成每张卡顶部一条突兀的横线,
 * 所以这里恢复卡片形态:白底 + 暖描边 + 轻影 + 16px 圆角 ——
 * 与 PostCard 同一套卡片语言(A 方向),hover 只升影不改色。
 */
@media (min-width: 1024px) {
  .crow {
    /* 垂直节奏:信息块贴顶、操作块贴底 —— 宽卡(2×2 时 480 宽)不再因
       flex 居中把图标+文字糊在中部、上下留一大块空洞(圈子页排版问题)。 */
    flex-direction: column;
    align-items: stretch;
    justify-content: space-between;
    min-height: 96px;
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
  /* 信息块(图标+名字+成员数)贴顶,横向排列 */
  .crow__open {
    align-items: flex-start;
  }
  /* 操作块(加入/退出)贴底右 */
  .crow__join,
  .crow__leave {
    align-self: flex-end;
    min-width: 88px;
  }
  /* 文本列允许截断:否则卡片收窄时名字列把按钮顶出右缘(桌面重叠 Bug4) */
  .crow__info {
    min-width: 0;
  }
}
</style>