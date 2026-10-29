<template>
  <!--
    学号核验审核(AdminVerify.vue)

    [为什么是独立页面而不是 AdminReview 的第四个 Tab]
    1. AdminReview.vue 已 298 行,单文件 300 行门禁只剩 2 行;
       挂一个 Tab 至少要 +6 行(标签页、挂载点、import、切页分支),
       而学号核验自己就要一个面板 —— 塞进去必然破门禁,
       破门禁的代价是把现有两个域的注释砍掉。
    2. 语义不同:那页的三个 Tab 是「举报 / 内容审核 / 已处理」,
       全是**内容**处置;学号核验是**身份**处置。
       放在一个叫「审核处置」的页签下,用户会以为通过的是内容而不是身份。
    3. 契约分成两个 tag(admin-report / admin-verify)、两个权限注解
       (AdminVerifyController 明确不打 @RequireVerified —— 管理员自己
       很可能还没通过认证),两条链路各自独立。

    本页只做视图与五态。分页、乐观出栈、失败回滚全在 useVerifyQueue.js。
  -->
  <div class="verify-admin">
    <!--
      权限不足(AC-20)。userInfo 还没到时先不判 denied ——
      直接跳「只有管理员能看」会把加载中误判成越权。
      路由守卫已经拦过一道,这里是组件内的第二道,不替代服务端鉴权。
    -->
    <AdminDenied v-if="denied" class="verify-admin__state" @action="router.replace('/')" />

    <template v-else>
      <!-- 骨架:形状对齐真实卡片(学号行 + 一行小字 + 一行更短),避免 CLS -->
      <ul v-if="loading && list.length === 0" class="verify-admin__skeletons" aria-hidden="true">
        <li v-for="n in 3" :key="n" class="verify-skeleton">
          <span class="verify-skeleton__no" />
          <span class="verify-skeleton__line" />
          <span class="verify-skeleton__line verify-skeleton__line--short" />
        </li>
      </ul>

      <ul v-else-if="rows.length" class="verify-admin__list">
        <li v-for="row in rows" :key="row.item.userId" class="vcard">
          <!--
            行 1:学号本体。
            14 位数字挤在 375px 里,管理员的动作是拿它去跟证件比对 ——
            所以等宽数字、不换行、不截断、字号 ≥ 13px(取 --ys-text-lg 18px)。
          -->
          <div class="vcard__no-row">
            <p class="vcard__no">{{ row.item.studentNo }}</p>
            <!-- 灰标签:只在后端明确回「解析不了」时出现。字段未下发时什么都不显示 -->
            <span v-if="row.unparsable" class="vcard__tag">无法解析</span>
          </div>

          <!--
            行 2:学号解析出的年级 与 用户自填的年级 **并排**。
            2026-10-28 起比对由后端用字典值算（年级 enroll_year、专业 6 位 code），
            前端只读 gradeMatch / majorMatch：false 标红，null（解析不出或缺字典）
            不标。不在这里做任何字符串匹配——规则只有后端一份。
          -->
          <p v-if="row.parsed || row.item.grade" class="vcard__grades">
            <span v-if="row.parsed" class="vcard__parsed">{{ row.parsed }}</span>
            <span v-if="row.parsed && row.item.grade" class="vcard__vs">/</span>
            <span v-if="row.item.grade" class="vcard__claimed">本人填 {{ row.item.grade }}</span>
          </p>

          <!-- 自填的学校归属：与学号不符的项直接标红，管理员不用自己心算 -->
          <ul v-if="row.hasClaim" class="vcard__info">
            <li :class="{ 'is-bad': row.item.gradeMatch === false }">
              <span class="vcard__info-k">年级</span>
              <span class="vcard__info-v">{{ row.item.grade || '—' }}</span>
              <span v-if="row.item.gradeMatch === false" class="vcard__bad">与学号不符</span>
            </li>
            <li :class="{ 'is-bad': row.item.majorMatch === false }">
              <span class="vcard__info-k">专业</span>
              <span class="vcard__info-v">{{ row.item.major || '—' }}</span>
              <span v-if="row.item.majorMatch === false" class="vcard__bad">与学号不符</span>
            </li>
            <li>
              <span class="vcard__info-k">层次</span>
              <span class="vcard__info-v">{{ row.item.level || '—' }}</span>
            </li>
            <li :class="{ 'is-bad': row.item.deptMatch === false }">
              <span class="vcard__info-k">学院</span>
              <span class="vcard__info-v">{{ row.item.dept || '—' }}</span>
              <span v-if="row.item.deptMatch === false" class="vcard__bad">与专业不符</span>
            </li>
          </ul>
          <p v-if="row.mismatch" class="vcard__warn">有 {{ row.mismatch }} 项与学号不一致，核实后再决定</p>

          <p class="vcard__who">
            <!--
              昵称可点跳主页(2026-10-24 补):核验真伪的旁证是「这个人像不像本校学生」
              ——头像/昵称风格/历史动态,此前都是纯文本,管理员得手拼 URL。
            -->
            <button
              type="button"
              class="vcard__nick vcard__nick--link"
              @click="router.push(`/user/${row.item.userId}`)"
            >
              {{ row.item.nickname }}
            </button>
            <span v-if="row.item.dept" class="vcard__dept">{{ row.item.dept }}</span>
          </p>

          <p class="vcard__time">{{ fmt(row.item.submittedAt) }} 提交</p>

          <!--
            处置区。**解析结果永远不置灰、不拦截这两个按钮** ——
            解析只是提示,放行权在管理员手上。
          -->
          <div class="vcard__actions">
            <template v-if="rejecting === row.item.userId">
              <label class="vcard__reason">
                <span class="vcard__reason-label">驳回理由(用户会看到)</span>
                <input
                  v-model="reason"
                  type="text"
                  maxlength="200"
                  placeholder="写清该怎么改"
                />
              </label>
              <!--
                常用理由（2026-10-28）：点一下直接填进输入框，还能再改。
                输入框已按比对结果预填，这里的短句覆盖它——
                管理员最常见的动作是「换一句更具体的」。
              -->
              <div class="vcard__presets">
                <button
                  v-for="p in REJECT_PRESETS"
                  :key="p"
                  type="button"
                  class="vcard__preset"
                  @click="reason = p"
                >
                  {{ p }}
                </button>
              </div>
              <div class="vcard__btns">
                <button type="button" class="vcard__btn" @click="cancelReject()">取消</button>
                <button
                  type="button"
                  class="vcard__btn vcard__btn--danger"
                  :disabled="busy[row.item.userId]"
                  @click="confirmReject(row.item)"
                >
                  提交驳回
                </button>
              </div>
            </template>
            <template v-else>
              <div class="vcard__btns">
                <button
                  type="button"
                  class="vcard__btn vcard__btn--pass"
                  :disabled="busy[row.item.userId]"
                  @click="approve(row.item)"
                >
                  通过
                </button>
                <button
                  type="button"
                  class="vcard__btn vcard__btn--reject"
                  :disabled="busy[row.item.userId]"
                  @click="openReject(row.item)"
                >
                  驳回
                </button>
              </div>
            </template>
          </div>
        </li>
      </ul>

      <!--
        空态分两种,不能共用一句「暂无」:「现在没活干」和「页面坏了」
        混起来会让管理员以为系统正常,实际是拉取挂了(AC-19)。
      -->
      <StateBlock
        v-else-if="failed"
        variant="error"
        title="核验队列没拉出来"
        description="可能是网络断了,点一下重试"
        action-label="重试"
        :icon="WarningFilled"
        @action="load(true)"
      />
      <StateBlock
        v-else
        variant="empty"
        title="没有待核验的学号"
        description="有人提交学号后会进这里"
        action-label="刷新一下"
        :icon="Postcard"
        @action="load(true)"
      />

      <!--
        无限滚动哨兵。放在列表**外面**:列表为空时它一开始就在视口里,
        IntersectionObserver 会连发多次 load,第一页被重复追加。
      -->
      <div class="verify-admin__sentinel">
        <p v-if="loading && list.length" class="verify-admin__hint">正在加载更多</p>
        <p v-else-if="!hasMore && list.length" class="verify-admin__hint">到底了</p>
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * 学号核验待审列表 + 通过/驳回。
 *
 * 分页、乐观出栈、404(已被别的设备处置)的处理都在 useVerifyQueue,
 * 本文件只负责「显示成什么样」和四态判定。
 */
import { computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { Postcard, WarningFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useVerifyQueue } from '@/composables/useVerifyQueue'
import AdminDenied from '@/components/common/AdminDenied.vue'
import StateBlock from '@/components/common/StateBlock.vue'

const router = useRouter()
const userStore = useUserStore()

const {
  list, hasMore, loading, failed, busy, rejecting, reason,
  load, observeSentinel, approve, openReject, cancelReject, confirmReject,
} = useVerifyQueue()

/**
 * 学号解析结果的展示口径 —— **三态,不是两态**。
 *
 *   parsed === true       -> 「2025级 · 02班」
 *   parsed === false      -> 灰色「无法解析」标签
 *   字段不存在(undefined)-> 什么都不显示
 *
 * 第三态是为「后端还没下发这个字段」留的。
 * 若把 undefined 当成 parsed=false,后端未落地时**每张卡片**都会挂上
 * 「无法解析」—— 那是谎报:我们没收到解析结果,不代表解析失败。
 * 契约 §parsed 也写明「展示错误的解析结果比展示无法解析危害更大」,
 * 同理,谎报「无法解析」也不该做。
 *
 * 前端不做任何字符串切分:规则在后端,前端只读结果。
 */
function parsedLine(item) {
  const p = item?.studentNoParsed
  if (!p || p.parsed !== true) return ''
  const parts = []
  if (p.entranceYear !== null && p.entranceYear !== undefined) parts.push(`${p.entranceYear}级`)
  // classNo 契约要求保留前导零('02' 就是 '02'),这里原样显示不做去零
  if (p.classNo) parts.push(`${p.classNo}班`)
  return parts.join(' · ')
}

const rows = computed(() =>
  list.value.map((item) => {
    const mismatch = [item?.deptMatch, item?.gradeMatch, item?.majorMatch].filter(
      (v) => v === false
    ).length
    return {
      item,
      parsed: parsedLine(item),
      unparsable: item?.studentNoParsed != null && item.studentNoParsed.parsed === false,
      hasClaim: !!(item?.dept || item?.major || item?.level || item?.grade),
      mismatch,
    }
  }),
)

/**
 * 常用驳回理由。前四条与三个比对位一一对应，管理员看到哪一项标红就点哪条；
 * 最后一条覆盖「看不出哪里错、但就是不像本人」的情况。
 */
const REJECT_PRESETS = [
  '学号与本人不符，请填写自己的学号',
  '学院与学号不匹配，请重新选择学院',
  '专业与学号不匹配，请重新选择专业',
  '年级与学号不匹配，请重新选择年级',
  '学校信息不全，请补全后重新提交',
]

/**
 * 权限判定用 store 的 isAdmin,不等 API 返 403 ——
 * 等 403 意味着管理员已经看完一屏他不该看见的内容骨架。
 */
const ready = computed(() => !!userStore.userInfo)
const denied = computed(() => ready.value && userStore.isAdmin !== true)

onMounted(() => {
  if (denied.value) return
  load(true).then(() => observeSentinel())
})

watch(
  () => userStore.isAdmin,
  (v) => {
    // 权限在会话中途变化(被降权):立刻离开本页,别让越权数据留在屏幕上
    if (v === false) router.replace('/')
  },
)

function fmt(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}
</script>

<style scoped src="./AdminVerify.css"></style>