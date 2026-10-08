<template>
  <!--
    引导第 3 步:关注推荐的同学与圈子(Onboarding.vue.md §1.3)
    两个分组各只显示 5 条 —— 引导页的选项是「给个起点」,不是「让你挑完」。
  -->
  <div class="step3">
    <header class="step3__head">
      <h1 class="step3__title">关注几个同学和圈子</h1>
      <p class="step3__sub">关注之后首页就有内容了,随时可以取消</p>
    </header>

    <!-- 推荐加载失败:**不阻塞引导完成**(§3 错误态),两组各自降级为一行说明 -->
    <section class="step3__group">
      <h2 class="step3__group-title">推荐同学</h2>
      <p v-if="usersFailed" class="step3__note">推荐没拉出来,不影响你进友声</p>

      <!-- 真冷启动(全校还没人注册):整个内容区降级为一段有出口的文字,禁止空白页(AC-19) -->
      <div v-else-if="coldStart" class="step3__cold">
        <p class="step3__cold-title">你是前 100 个用户之一</p>
        <p class="step3__cold-sub">现在人还少,先去发一条,后面来的人就能看到你</p>
      </div>

      <SkeletonList v-else-if="loading" :count="5" variant="row" />

      <ul v-else-if="users.length" class="step3__list">
        <li v-for="u in users" :key="u.id" class="step3__row">
          <span class="step3__avatar" aria-hidden="true">{{ u.nickname.slice(0, 1) }}</span>
          <span class="step3__info">
            <span class="step3__name">
              {{ clip(u.nickname) }}
              <VerifiedShield v-if="u.verified" :size="14" class="step3__verified" />
            </span>
            <span class="step3__meta">{{ clip(u.reason) }}</span>
          </span>
          <button
            type="button"
            class="step3__btn"
            :class="{ 'is-done': pickedUserIds.includes(u.id) }"
            :aria-pressed="pickedUserIds.includes(u.id)"
            :aria-label="`关注同学 ${u.nickname}`"
            @click="$emit('pick-user', u.id)"
          >
            {{ pickedUserIds.includes(u.id) ? '已关注' : '关注' }}
          </button>
        </li>
      </ul>

      <p v-else class="step3__note">现在还没有同学可以推荐,等你关注几个同学,这里就会有人了</p>
    </section>

    <section v-if="circles.length || !usersFailed" class="step3__group">
      <h2 class="step3__group-title">推荐圈子</h2>
      <p v-if="circlesFailed" class="step3__note">圈子没拉出来,不影响你进友声</p>
      <SkeletonList v-else-if="loading" :count="5" variant="row" />
      <ul v-else-if="circles.length" class="step3__list">
        <li v-for="c in circles" :key="c.id" class="step3__row">
          <span class="step3__circle" aria-hidden="true">{{ clip(c.name, 1) }}</span>
          <span class="step3__info">
            <span class="step3__name">{{ clip(c.name) }}</span>
            <span class="step3__meta ys-num">{{ c.memberCount }} 成员</span>
          </span>
          <button
            type="button"
            class="step3__btn"
            :class="{ 'is-done': pickedCircleIds.includes(c.id) }"
            :aria-pressed="pickedCircleIds.includes(c.id)"
            :aria-label="`加入圈子 ${c.name}`"
            @click="$emit('pick-circle', c.id)"
          >
            {{ pickedCircleIds.includes(c.id) ? '已加入' : '加入' }}
          </button>
        </li>
      </ul>
      <p v-else class="step3__note">还没有开放的圈子,先发一条内容也能被人看到</p>
    </section>
  </div>
</template>

<script setup>
/**
 * 冷启动空态的判定:两个分组**同时**为空(§3 空态 1)。
 * 只有一组为空时,那一组降级为一行文字,另一组照常显示 5 条。
 */
import { computed } from 'vue'
import VerifiedShield from '@/icons/VerifiedShield.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

const props = defineProps({
  users: { type: Array, default: () => [] },
  circles: { type: Array, default: () => [] },
  pickedUserIds: { type: Array, default: () => [] },
  pickedCircleIds: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  usersFailed: { type: Boolean, default: false },
  circlesFailed: { type: Boolean, default: false },
})

defineEmits(['pick-user', 'pick-circle'])

const coldStart = computed(() => !props.usersFailed && props.users.length === 0 && props.circles.length === 0)

function clip(text, len = 12) {
  if (!text) return ''
  return text.length > len ? `${text.slice(0, len)}…` : text
}
</script>

<style scoped>
.step3 {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-6);
}

.step3__head {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.step3__title {
  margin: 0;
  font-size: var(--ys-text-2xl);
  line-height: var(--ys-lh-2xl);
  letter-spacing: var(--ys-ls-2xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.step3__sub {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
}

.step3__group-title {
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}

.step3__note {
  margin: 0;
  padding: var(--ys-space-3) 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-meta);
}

.step3__list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.step3__row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  /* 行高 64px(Onboarding.vue.md §1.3) */
  min-height: 64px;
}

.step3__row + .step3__row {
  border-top: 1px solid var(--ys-border-soft);
}

.step3__avatar {
  flex: none;
  display: grid;
  place-items: center;
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
  font-size: var(--ys-text-md);
}

.step3__circle {
  flex: none;
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
  font-size: var(--ys-text-md);
}

.step3__info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.step3__name {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.step3__verified {
  flex: none;
  color: var(--ys-accent);
}

.step3__meta {
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.step3__btn {
  flex: none;
  min-width: 72px;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border-radius: var(--ys-radius-full);
  border: 1px solid var(--ys-accent);
  background: var(--ys-surface);
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-base) var(--ys-ease-out),
    color var(--ys-motion-base) var(--ys-ease-out),
    border-color var(--ys-motion-base) var(--ys-ease-out);
}

.step3__btn.is-done {
  background: var(--ys-surface-2);
  border-color: var(--ys-border-soft);
  color: var(--ys-fg-2);
}

.step3__cold {
  padding: var(--ys-space-6) 0;
}

.step3__cold-title {
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-text-2xl);
  line-height: var(--ys-lh-2xl);
  letter-spacing: var(--ys-ls-2xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.step3__cold-sub {
  margin: 0;
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-md);
  color: var(--ys-muted);
}
</style>
