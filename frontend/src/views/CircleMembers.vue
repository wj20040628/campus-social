<template>
  <!--
    圈子成员页（2026-10-21 新增）。

    【为什么要有它】改版前成员列表只存在于 CircleManagePanel，
    而那个面板是 v-if="canManage" —— 只有圈主与圈管理看得见。
    普通成员和访客在圈子里只看到一个「2 成员」的数字，不知道是谁。
    但「这个圈子里有哪些人」恰恰是决定要不要加入的主要依据。

    【为什么是独立页而不是弹窗】成员多时弹窗里滚动很难受，
    而且看成员是一个会反复回来看的动作（找人、看谁在）。
    独立页可以返回、可以被浏览器记住位置。
  -->
  <div class="cm-page">
    <!-- 加载骨架：形状对齐真实行，避免加载完时跳动 -->
    <SkeletonList v-if="loading" :count="5" />

    <StateBlock
      v-else-if="failed"
      variant="error"
      title="成员没拉出来"
      description="可能是网络断了，点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="load"
    />

    <template v-else>
      <!-- 圈主 -->
      <section v-if="grouped.owner.length" class="cm-group">
        <p class="cm-group__h">圈主<span class="cm-group__n">{{ grouped.owner.length }} 人</span></p>
        <MemberRow v-for="m in grouped.owner" :key="m.userId" :member="m" :show-op="false" />
      </section>

      <!-- 圈管理 -->
      <section v-if="grouped.admin.length" class="cm-group">
        <p class="cm-group__h">管理员<span class="cm-group__n">{{ grouped.admin.length }} 人</span></p>
        <MemberRow v-for="m in grouped.admin" :key="m.userId" :member="m" :show-op="false" />
      </section>

      <!-- 普通成员 -->
      <section v-if="grouped.member.length" class="cm-group">
        <p class="cm-group__h">成员<span class="cm-group__n">{{ grouped.member.length }} 人</span></p>
        <MemberRow
          v-for="m in grouped.member"
          :key="m.userId"
          :member="m"
          :show-op="true"
          @chat="goChat(m)"
        />
      </section>

      <!-- 空态：AC-19 要求给出口。圈子至少有一个圈主，走到这里是数据异常 -->
      <StateBlock
        v-if="!grouped.owner.length && !grouped.admin.length && !grouped.member.length"
        variant="empty"
        title="还没有成员"
        description="这个圈子还没有人加入"
        action-label="回圈子广场"
        :icon="User"
        @action="router.push('/circles')"
      />
    </template>
  </div>
</template>

<script setup>
/**
 * 圈子成员页。
 *
 * 数据来自既有的 GET /circle/{id}/members —— 它本来就没有权限校验
 * （viewerId 参数甚至没被用到），所以这个页面对所有人开放不需要改后端。
 * 2026-10-21 那次改动只是给 CircleMemberVO 补了 grade / dept 两个展示字段。
 *
 * 按角色分组：圈主 → 圈管理 → 成员。
 * 顺序不能乱 —— 它的实际用途是「出问题该找谁」，圈主必须在最上。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WarningFilled, User } from '@element-plus/icons-vue'
import { ElMessage } from '@/utils/feedback'
import { circleMembers } from '@/api/circle'
import SkeletonList from '@/components/common/SkeletonList.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import MemberRow from '@/components/circle/CircleMemberRow.vue'

const route = useRoute()
const router = useRouter()

const list = ref([])
const loading = ref(false)
const failed = ref(false)

/** 后端 role：1 圈主 / 2 圈管理 / 3 普通成员（与 CircleAdminServiceImpl 的常量一致） */
const grouped = computed(() => {
  const g = { owner: [], admin: [], member: [] }
  for (const m of list.value) {
    // 被封禁的成员不进公开列表：封禁是管理措施，不该把人挂在公开页示众。
    // 圈主/圈管理在管理面板里仍然能看到他们。
    if (m.banStatus === 1) continue
    if (m.role === 1) g.owner.push(m)
    else if (m.role === 2) g.admin.push(m)
    else g.member.push(m)
  }
  return g
})

async function load() {
  loading.value = true
  failed.value = false
  try {
    list.value = (await circleMembers(route.params.id)) || []
  } catch {
    // request.js 已弹错，这里只切本地状态给出重试入口
    failed.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 私信某个成员。
 *
 * 未认证用户点「私信」会被后端 @RequireVerified 拦成 403，
 * 但那时用户只看到一句通用错误、不知道下一步。所以这里先探一下本地
 * userInfo 的 verified，给一句具体的话。
 */
function goChat(m) {
  const me = JSON.parse(localStorage.getItem('user_info') || '{}')
  if (me?.verified !== true) {
    ElMessage.warning('完成校内认证后才能发私信')
    return
  }
  router.push(`/chat/t/dm/${m.userId}`)
}

onMounted(load)
watch(() => route.params.id, load)
</script>

<style scoped src="./CircleMembers.css"></style>
