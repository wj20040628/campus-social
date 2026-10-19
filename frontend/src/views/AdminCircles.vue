<template>
  <!--
    圈子官方名保护 + 管理员处置(AdminCircles.vue)

    两个 Tab:
      1. 圈子处置:被抢注官方名的圈子 → 移交圈主 / 封禁 / 解封。
      2. 预留名:维护「哪些名字是官方保留、建圈直接拦」(reserved_circle_name)。

    权限与 AdminVerify 同款:store.isAdmin 判 denied,路由守卫 + 服务端
    @RequireAdmin 各拦一道,前端 denied 只是第二道展示层。
  -->
  <div class="ac">
    <AdminDenied v-if="denied" class="ac__state" @action="router.replace('/')" />

    <template v-else>
      <!-- 自绘分段控件:圈子处置 / 预留名 -->
      <div class="ac__tabs" role="tablist" aria-label="圈子管理">
        <button
          v-for="t in TABS"
          :key="t.name"
          type="button"
          role="tab"
          class="ac__tab"
          :class="{ 'is-on': tab === t.name }"
          :aria-selected="tab === t.name"
          @click="tab = t.name"
        >
          {{ t.label }}
        </button>
        <span class="ac__tab-ind" :style="{ transform: `translateX(${tab === 'circles' ? '0%' : '100%'})` }" />
      </div>

      <!-- Tab 1:圈子处置(全量列表:含封禁/解散,移交/封禁/解封/恢复) -->
      <div v-show="tab === 'circles'" role="tabpanel" class="ac__body">
        <StateBlock
          v-if="!cLoading && circles.length === 0"
          variant="empty"
          title="还没有圈子"
          description="用户建了圈子后,被抢注官方名的会出现在这里供你处置"
        />
        <ul v-else class="ac__list">
          <li v-for="c in circles" :key="c.id" class="ac__row" :class="{ 'is-banned': c.status === 1 }">
            <div class="ac__cid">
              <div class="ac__ctitle">
                <span class="ac__cname">{{ c.name }}</span>
                <!-- 状态标签:正常(灰)/ 已封禁(红)。封禁/解散的圈子整行染淡红,一眼扫出 -->
                <span v-if="c.status === 0" class="ac__stag ac__stag--ok">正常</span>
                <span v-else class="ac__stag ac__stag--bad">已封禁</span>
                <span v-if="c.status === 0" class="ac__count">{{ c.memberCount || 0 }} 成员</span>
              </div>
              <!-- 圈主列:owner 对象由 toVoForAdmin 回填(脱敏 nickname);缺失时显示 id 兜底 -->
              <div class="ac__cowner">
                圈主:{{ c.owner?.nickname || c.owner?.username || c.ownerId }}
              </div>
            </div>
            <div class="ac__cacts">
              <button
                v-if="c.status === 0"
                type="button"
                class="ac__btn ac__btn--warn"
                :disabled="busy === `ban-${c.id}`"
                @click="ban(c)"
              >封禁</button>
              <button
                v-else
                type="button"
                class="ac__btn"
                :disabled="busy === `unban-${c.id}`"
                @click="unban(c)"
              >解封</button>
              <!--
                恢复:思路 B 兜底,仅 status=1(封禁/解散)时露出。
                与「解封」的区别:解封只翻 status=0,不解散级联的帖子 status=2;
                恢复额外把圈内帖子 status 2→1,让解散过的圈彻底复活。
                实现上 unban 与 restore 可互换(都走 setStatus(0)),
                这里保留两个按钮:封禁用「解封」,解散用「恢复」,语义对齐操作来源。
              -->
              <button
                v-if="c.status === 1"
                type="button"
                class="ac__btn"
                :disabled="busy === `restore-${c.id}`"
                @click="restore(c)"
              >恢复</button>
              <button
                type="button"
                class="ac__btn"
                :disabled="busy === `transfer-${c.id}` || c.status === 1"
                @click="promptTransfer(c)"
              >移交圈主</button>
            </div>
          </li>
        </ul>
        <SkeletonList v-if="cLoading" :count="3" />
        <div v-if="cFailed" class="ac__hint">圈子没拉出来,<button class="ac__link" @click="loadCircles()">重试</button></div>
      </div>

      <!-- Tab 2:预留名(增删 reserved_circle_name) -->
      <div v-show="tab === 'reserved'" role="tabpanel" class="ac__body">
        <form class="ac__add" @submit.prevent="addReserved">
          <input
            v-model="newName"
            class="ac__input"
            placeholder="预留圈子名(1–64 字)"
            maxlength="64"
            autocomplete="off"
          />
          <input
            v-model="newNote"
            class="ac__input ac__input--note"
            placeholder="预留说明(可选)"
            maxlength="128"
            autocomplete="off"
          />
          <button
            type="submit"
            class="ac__btn ac__btn--primary"
            :disabled="!newName.trim() || adding"
          >{{ adding ? '预留中' : '预留' }}</button>
        </form>

        <ul v-if="!rLoading && reserved.length" class="ac__list">
          <li v-for="r in reserved" :key="r.id" class="ac__row">
            <div class="ac__cid">
              <span class="ac__cname">{{ r.name }}</span>
              <span v-if="r.note" class="ac__cmeta">{{ r.note }}</span>
            </div>
            <div class="ac__cacts">
              <button
                type="button"
                class="ac__btn ac__btn--warn"
                :disabled="busy === `del-${r.name}`"
                @click="removeReserved(r)"
              >移除</button>
            </div>
          </li>
        </ul>
        <StateBlock
          v-else-if="!rLoading && reserved.length === 0"
          variant="empty"
          title="还没有预留名"
          description="预留后,普通用户建圈撞这些名字会被直接拦"
        />
        <SkeletonList v-if="rLoading" :count="3" />
        <div v-if="rFailed" class="ac__hint">预留名没拉出来,<button class="ac__link" @click="loadReserved()">重试</button></div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'
import * as circleApi from '@/api/circle'
import * as adminApi from '@/api/admin'
import AdminDenied from '@/components/common/AdminDenied.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

const router = useRouter()
const userStore = useUserStore()

const TABS = [
  { name: 'circles', label: '圈子处置' },
  { name: 'reserved', label: '预留名' },
]
const tab = ref('circles')

// ---------------- 圈子处置 ----------------
const circles = ref([])
const cLoading = ref(false)
const cFailed = ref(false)
const busy = ref('')

async function loadCircles() {
  cLoading.value = true
  cFailed.value = false
  try {
    // 走 admin 全量端点(含 status=1 封禁/解散),旧 circleApi.list 只查 status=0,
    // 封禁后这条会消失、管理员再也找不回来 —— 真 bug
    const data = await adminApi.listAllCircles(null, 200)
    circles.value = data?.list || []
  } catch {
    cFailed.value = true
  } finally {
    cLoading.value = false
  }
}

async function ban(c) {
  busy.value = `ban-${c.id}`
  try {
    await adminApi.banCircle(c.id)
    ElMessage.success('已封禁该圈子')
    await loadCircles()
  } catch { /* request.js 已弹错 */ } finally {
    busy.value = ''
  }
}

async function unban(c) {
  busy.value = `unban-${c.id}`
  try {
    await adminApi.unbanCircle(c.id)
    ElMessage.success('已解封该圈子')
    await loadCircles()
  } catch { /* request.js 已弹错 */ } finally {
    busy.value = ''
  }
}

/** 恢复解散的圈子(思路 B 兜底):圈 status 1→0 + 圈内帖子 status 2→1。仅管理员。 */
async function restore(c) {
  busy.value = `restore-${c.id}`
  try {
    await adminApi.restoreCircle(c.id)
    ElMessage.success('已恢复该圈子')
    await loadCircles()
  } catch { /* request.js 已弹错 */ } finally {
    busy.value = ''
  }
}

/** 移交圈主:选圈内某成员为新圈主(原圈主降为普通成员)。 */
async function promptTransfer(c) {
  try {
    const data = await circleApi.circleMembers(c.id)
    const members = data?.list || data || []
    if (!members.length) {
      ElMessage.warning('该圈子没有可移交的成员')
      return
    }
    const { value } = await ElMessageBox.prompt(
      `把「${c.name}」的圈主位交给哪位成员?(输入该成员在列表中的昵称)\n可移交成员:${members.map((m) => m.nickname).join('、')}`,
      '移交圈主',
      {
        confirmButtonText: '移交',
        cancelButtonText: '取消',
        inputType: 'text',
        inputPlaceholder: '成员昵称',
        inputValidator: (v) => (v && v.trim() ? true : '填成员昵称'),
      },
    ).catch(() => ({ value: undefined }))
    if (!value) return
    const target = members.find((m) => m.nickname === value.trim())
    if (!target) {
      ElMessage.warning('没找到昵称完全匹配的成员')
      return
    }
    await adminApi.transferCircle(c.id, target.userId)
    ElMessage.success(`已把「${c.name}」的圈主位交给 ${target.nickname}`)
  } catch (e) {
    if (e !== undefined && !(e instanceof Error)) {
      /* request.js 已弹错 */
    }
  }
}

// ---------------- 预留名 ----------------
const reserved = ref([])
const rLoading = ref(false)
const rFailed = ref(false)
const adding = ref(false)
const newName = ref('')
const newNote = ref('')

async function loadReserved() {
  rLoading.value = true
  rFailed.value = false
  try {
    const data = await adminApi.listReservedNames(null, 100)
    reserved.value = data?.list || []
  } catch {
    rFailed.value = true
  } finally {
    rLoading.value = false
  }
}

async function addReserved() {
  adding.value = true
  try {
    await adminApi.addReservedName(newName.value.trim(), newNote.value.trim() || null)
    ElMessage.success('已预留该圈名')
    newName.value = ''
    newNote.value = ''
    await loadReserved()
  } catch { /* request.js 已弹错 */ } finally {
    adding.value = false
  }
}

async function removeReserved(r) {
  busy.value = `del-${r.name}`
  try {
    await ElMessageBox.confirm(`确定移除预留名「${r.name}」?移除后普通用户即可建同名圈子。`, '移除预留名', {
      type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消',
    })
  } catch { return }
  try {
    await adminApi.delReservedName(r.name)
    ElMessage.success('已移除预留名')
    await loadReserved()
  } catch { /* request.js 已弹错 */ } finally {
    busy.value = ''
  }
}

// ---------------- 权限守卫 ----------------
const ready = computed(() => !!userStore.userInfo)
const denied = computed(() => ready.value && userStore.isAdmin !== true)

onMounted(() => {
  if (denied.value) return
  loadCircles()
  loadReserved()
})
</script>

<style scoped src="./AdminCircles.css"></style>
