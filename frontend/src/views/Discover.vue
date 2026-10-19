<template>
  <div class="discover">
    <div class="discover-inner">
      <header class="disc__head">
        <h1 class="disc__title">发现</h1>
        <p class="disc__sub">按你选的兴趣，找到同好和正在聊的圈子</p>
      </header>

      <el-tabs v-model="tab" class="disc__tabs">
        <el-tab-pane label="同好的人" name="users" />
        <el-tab-pane label="热门圈子" name="circles" />
      </el-tabs>

      <!-- 同好的人:按与当前用户标签重合度召回,理由由后端下发 -->
      <section v-if="tab === 'users'" class="disc__list">
        <div v-if="usersLoading" class="res-list">
          <el-card v-for="i in 4" :key="'sk' + i"><el-skeleton :rows="2" animated /></el-card>
        </div>
        <template v-else>
          <div
            v-for="u in users"
            :key="u.id"
            class="disc-row"
            @click="router.push(`/user/${u.id}`)"
          >
            <InitialAvatar class="disc-row__avatar" :size="48" :src="u.avatarUrl ? resolveThumb(u.avatarUrl) : ''" :seed="u.id" :name="displayName(u)" />
            <div class="disc-row__info">
              <div class="disc-row__name">
                {{ displayName(u) }}
                <el-icon v-if="u.verified" class="disc-row__badge"><Select /></el-icon>
              </div>
              <div class="disc-row__sub">{{ [u.dept, u.grade].filter(Boolean).join(' · ') || '同校同学' }}</div>
              <div v-if="u.reason" class="disc-row__reason">{{ u.reason }}</div>
            </div>
            <button
              type="button"
              class="disc-row__btn"
              :class="{ 'is-on': u.followed }"
              @click.stop="toggleFollow(u)"
            >{{ u.followed ? '已关注' : '关注' }}</button>
          </div>
          <StateBlock
            v-if="!usersLoading && !users.length"
            variant="empty"
            :title="usersEmptyHint || '还没有同好推荐'"
            description="多挑几个兴趣，推荐会更准"
            action-label="去挑兴趣"
            :icon="Compass"
            @action="router.push('/profile')"
          />
        </template>
      </section>

      <!-- 热门圈子:暂无圈子标签,按成员数降级(计划 §6 场景2 待补 circle_tag) -->
      <section v-else class="disc__list">
        <div v-if="circlesLoading" class="res-list">
          <el-card v-for="i in 4" :key="'skc' + i"><el-skeleton :rows="2" animated /></el-card>
        </div>
        <template v-else>
          <div
            v-for="c in circles"
            :key="c.id"
            class="disc-row"
            @click="router.push(`/circles/${c.id}`)"
          >
            <InitialAvatar class="disc-row__avatar" :size="48" :src="c.avatarUrl ? resolveThumb(c.avatarUrl) : ''" :seed="c.id" :name="c.name || ''" />
            <div class="disc-row__info">
              <div class="disc-row__name">{{ c.name }}</div>
              <div class="disc-row__sub">{{ c.memberCount || 0 }} 位成员</div>
            </div>
            <button
              type="button"
              class="disc-row__btn"
              @click.stop="router.push(`/circles/${c.id}`)"
            >进入</button>
          </div>
          <StateBlock
            v-if="!circlesLoading && !circles.length"
            variant="empty"
            :title="circlesEmptyHint || '还没有圈子推荐'"
            description="去圈子广场看看大家在聊什么"
            action-label="去圈子广场"
            :icon="Collection"
            @action="router.push('/circles')"
          />
        </template>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Select, Compass, Collection } from '@element-plus/icons-vue'
import { discoverUsers, discoverCircles } from '@/api/discover'
import { follow, unfollow } from '@/api/relation'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import { ElMessage } from '@/utils/feedback'

const router = useRouter()
const tab = ref('users')

const users = ref([])
const usersLoading = ref(true)
const usersEmptyHint = ref('')
const circles = ref([])
const circlesLoading = ref(true)
const circlesEmptyHint = ref('')

async function loadUsers() {
  usersLoading.value = true
  try {
    const data = await discoverUsers(12)
    users.value = (data?.users || []).map((u) => ({ ...u, followed: false }))
    usersEmptyHint.value = data?.emptyHint || ''
  } catch {
    users.value = []
  } finally {
    usersLoading.value = false
  }
}

async function loadCircles() {
  circlesLoading.value = true
  try {
    const data = await discoverCircles(8)
    circles.value = data?.circles || []
    circlesEmptyHint.value = data?.emptyHint || ''
  } catch {
    circles.value = []
  } finally {
    circlesLoading.value = false
  }
}

/** 关注/取关:乐观更新,失败回滚并提示 */
async function toggleFollow(u) {
  const next = !u.followed
  u.followed = next
  try {
    if (next) await follow(u.id)
    else await unfollow(u.id)
  } catch {
    u.followed = !next
    ElMessage.error('操作没成功，再试一次')
  }
}

onMounted(() => {
  loadUsers()
  loadCircles()
})
</script>

<style scoped>
.discover-inner {
  max-width: 720px;
  margin: 0 auto;
  padding: var(--ys-space-4);
}
.disc__head {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-1);
}
.disc__title {
  margin: 0;
  font-size: var(--ys-text-2xl);
  line-height: var(--ys-lh-2xl);
  letter-spacing: var(--ys-ls-2xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}
.disc__sub {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
}
.disc__tabs {
  margin-top: var(--ys-space-3);
}
.disc__list {
  margin-top: var(--ys-space-2);
}
.disc-row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) 0;
  border-bottom: 1px solid var(--ys-border-soft);
  cursor: pointer;
}
.disc-row:last-child {
  border-bottom: none;
}
.disc-row__avatar {
  flex: 0 0 auto;
}
.disc-row__info {
  flex: 1 1 auto;
  min-width: 0;
}
.disc-row__name {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
}
.disc-row__badge {
  color: var(--ys-accent);
}
.disc-row__sub {
  margin-top: 2px;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}
.disc-row__reason {
  margin-top: var(--ys-space-1);
  display: inline-block;
  padding: 2px var(--ys-space-2);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
}
.disc-row__btn {
  flex: 0 0 auto;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}
.disc-row__btn.is-on {
  border-color: var(--ys-border-control);
  background: var(--ys-surface);
  color: var(--ys-muted);
}
.disc-row__btn:active {
  opacity: 0.8;
}
</style>
