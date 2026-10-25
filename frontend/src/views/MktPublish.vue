<template>
  <div class="mpub">
    <!--
      顶栏:ownTop(路由 meta) —— 返回 + 标题。
      手机/桌面同构:本页不做 el-drawer/el-dialog 双壳,是独立发布页。
      编辑模式(?id=)标题变「编辑活动」,隐藏时长选择。

      【2026-10-21 去掉顶栏提交按钮】改版前这里有个 .mpub__submit（文案「发布」），
      它和吸底的 .mpub__foot-btn（文案「发布活动」）绑的是**同一个 submit 函数** ——
      同一屏两个提交入口、文案还不一致，用户会怀疑「点上面那个是不是不完整」。
      现在顶栏只留标题，提交只走吸底那一个（键盘弹起时也够得着）。
    -->
    <header class="mpub__bar">
      <button type="button" class="mpub__back" aria-label="返回" @click="goBack">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <h1 class="mpub__title">{{ isEdit ? '编辑活动' : '发布活动' }}</h1>
      <!-- 占位：与左侧返回按钮等宽，让标题真正居中 -->
      <span class="mpub__bar-spacer" aria-hidden="true" />
    </header>

    <div class="mpub__body">
      <!-- 2026-10-21：表单已不用 el-form，label 布局由 MktPublishForm 内部掌控 -->
      <MktPublishForm :form="form" :is-edit="isEdit" />

      <!--
        桌面端右栏（2026-10-21 加，仅 ≥1280px 显示）。
        实测内容列 720px、1512 视口下右侧 500px 整片空白。
        这里放三块，全部是**静态/本地计算**的内容，不新增接口：
          发布须知 —— 说明流程与规则
          卡片预览 —— 发布前看看自己的活动在广场上长什么样
          安全提示 —— 与详情页/留言区同一套口径
      -->
      <aside class="mpub__aside" aria-label="发布须知">
        <section class="panel">
          <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>发布须知</h2>
          <p class="panel__tip">
            <b>先选分类再写详情</b>，详情里把时间、地点、费用说清楚。<br />
            <b>投放最长 12 小时</b>，到期自动下架，可重新激活。
          </p>
        </section>

        <section class="panel">
          <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>卡片会长这样</h2>
          <!--
            预览直接复用广场的 MktCard(2026-10-24 改):
            此前是一套自绘 pvcard,样式与真实卡片各自漂移(排版/字段/口径都对不上)。
            复用组件后格式天然 100% 一致,广场改版预览自动跟随。
            点击事件忽略:预览不是真卡片,点了不该跳详情。
            宽度收窄到广场两列网格的单卡宽度(~173px)再放开一点,
            让"会长这样"连尺寸感都贴近真实。
          -->
          <div class="mpub__pv">
            <MktCard :activity="previewActivity" variant="plaza" @open="() => {}" />
          </div>
        </section>

        <section class="panel">
          <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>安全提示</h2>
          <p class="panel__tip">
            <b>不要写手机号、微信号</b>，沟通只在平台留言区。<br />
            涉及金钱请当面结清，<b>拒绝转账</b>。
          </p>
        </section>
      </aside>
    </div>

    <!-- 吸底提交栏:手机键盘弹起时仍可达，也是**唯一**的提交入口 -->
    <div class="mpub__foot">
      <div class="mpub__foot-row">
        <button
          type="button"
          class="mpub__foot-btn"
          :disabled="submitting"
          @click="submit"
        >
          {{ submitting ? '提交中…' : (isEdit ? '保存修改' : '发布活动') }}
        </button>
      </div>
      <SafeArea position="bottom" />
    </div>
  </div>
</template>

<script setup>
/**
 * 集市活动发布/编辑页(计划 §发布页)
 * ------------------------------------------------------------------
 * 表单字段与校验在 MktPublishForm(表单体只有一份)。
 * 本页负责:构建初始 form、编辑模式预填、提交(前后端双重校验)、跳详情。
 *
 * 编辑模式(?id=):拉 detail 预填,隐藏时长选择(编辑不改 expire_time),
 * 提交走 editMkt(仅文案字段)。计划 §编辑语义。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import MktCard from '@/components/mkt/MktCard.vue'
import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/feedback'
import { ArrowLeft } from '@element-plus/icons-vue'
import SafeArea from '@/components/mobile/SafeArea.vue'
import MktPublishForm from '@/components/mkt/MktPublishForm.vue'
import { MAX_LAUNCH_HOURS } from '@/components/mkt/mktConstants'
import * as mkt from '@/api/mkt'

const route = useRoute()
const router = useRouter()

/** 编辑模式:路由带 ?id= */
const editId = ref(route.query.id || null)
const isEdit = ref(!!editId.value)
const submitting = ref(false)

function goBack() {
  router.back()
}

/* ---------------- 表单状态 ---------------- */
const userStore = useUserStore()

const form = reactive({
  category: '',
  subTag: '',
  /* 标题（2026-10-21 补）：可留空，留空时后端 derivedTitle 用「分类 · 游戏」兜底 */
  title: '',
  content: '',
  launchHours: 6,
  customHours: null,
  targetNum: 0,
  price: 0,
  location: '',
  // 校验错误文案(贴字段,不弹 toast)
  categoryError: '',
  contentError: '',
  durError: '',
  error: '',
})

function clearErrors() {
  form.categoryError = form.contentError = form.durError = form.error = ''
}

/* ---------------- 右栏「卡片预览」（2026-10-24 重写为复用 MktCard） ---------------- */
/**
 * 发布前预览:用当前表单值实时拼 MktCard 需要的 activity 对象。
 * 展示规则(标题派生/价格口径/满员/倒计时)全在 MktCard 内部,
 * 预览不再自算任何文案 —— 与广场的一致性由组件本身保证。
 * 倒计时按「现在 + 生效时长」实时走,用户能看到真实的紧迫感样式。
 */
const previewActivity = computed(() => {
  const h = effectiveHours()
  const price = Number(form.price)
  const target = Number(form.targetNum)
  return {
    id: 0,
    // 传自己的 id:广场上自己发的卡带 mine 配色,预览也如实呈现
    userId: userStore.userInfo?.id ?? 0,
    category: form.category || '未选分类',
    subTag: form.subTag || null,
    /*
     * title 模拟后端落库规则(MktActivityServiceImpl.create):
     * 用户填了标题用标题,没填落 derivedTitle(「分类」或「分类 · 副标签」)。
     * MktCard 的去重逻辑会自动处理「派生值==分类」时不渲染标题行。
     */
    title: form.title.trim() || (form.subTag
      ? `${form.category} · ${form.subTag}`
      : form.category),
    content: form.content.trim() || '（详情还没写）',
    location: form.location.trim() || '',
    price: Number.isFinite(price) && price > 0 ? price : 0,
    targetNum: Number.isFinite(target) && target > 0 ? target : null,
    isFull: 0,
    expireTime: h ? dayjs().add(h, 'hour').format('YYYY-MM-DDTHH:mm:ss') : null,
  }
})

/** 自填时长覆盖快捷选项;范围 1–12 */
/**
 * 生效时长：自填优先，否则用快捷选项。
 *
 * 2026-10-21：`??` 只对 null/undefined 生效，0 会被当成「有效值」返回 0，
 * 进而让 validate 报「投放时长填 1–12 小时」—— 但用户只是把自填框清空或点了减号到 0。
 * 现在把 0 与负数也视为「没填自填」，回落到 launchHours。
 */
const effectiveHours = () => {
  const raw = form.customHours
  const hasCustom = raw != null && Number.isFinite(Number(raw)) && Number(raw) > 0
  const h = hasCustom ? Number(raw) : form.launchHours
  return Number.isFinite(Number(h)) ? Number(h) : null
}

/** 前端敏感词预检:只拦明显违规,后端 SensitiveWordFilter 是最终判据 */
const BANNED = ['代考', '代写', '赌博', '广告']
function hasBannedWord(text) {
  return BANNED.some((w) => text.includes(w))
}

function validate() {
  clearErrors()
  let ok = true
  if (!form.category) { form.categoryError = '先选个一级分类'; ok = false }
  if (form.content.trim().length < 1) { form.contentError = '详情写点什么'; ok = false }
  if (!isEdit.value) {
    const h = effectiveHours()
    if (h == null || h < 1 || h > MAX_LAUNCH_HOURS) {
      form.durError = `投放时长填 1–${MAX_LAUNCH_HOURS} 小时`; ok = false
    }
  }
  if (hasBannedWord(form.content)) {
    form.error = '内容疑似含违规词,换个说法或删掉;提交时平台也会再查一遍'
    ok = false
  }
  return ok
}

async function submit() {
  if (submitting.value) return
  if (!validate()) return
  submitting.value = true
  clearErrors()
  try {
    if (isEdit.value) {
      // 编辑:仅文案字段,不带时长(后端也不改 expire_time)
      await mkt.editMkt(editId.value, {
        category: form.category,
        subTag: form.subTag || null,
        title: form.title.trim() || null,
        content: form.content.trim(),
        targetNum: form.targetNum,
        price: form.price,
        location: form.location || null,
      })
      ElMessage.success('已保存')
      router.replace(`/mkt/${editId.value}`)
    } else {
      const hours = effectiveHours()
      // 后端 expireTime 是 naive LocalDateTime(东八区),不是带时区的 Instant。
      // 必须按本地(东八)墙钟时间序列化,不能用 toISOString()(那会发 UTC,被后端
      // 当 naive 读 → 短时长落在过去被拒,12h 也会实际少投 8h)。
      const expireTime = dayjs().add(hours, 'hour').format('YYYY-MM-DDTHH:mm:ss')
      const id = await mkt.createMkt({
        category: form.category,
        subTag: form.subTag || null,
        title: form.title.trim() || null,
        content: form.content.trim(),
        targetNum: form.targetNum,
        price: form.price,
        location: form.location || null,
        expireTime,
      })
      ElMessage.success('发出去了,广场上能看到啦')
      router.push(`/mkt/${id}`)
    }
  } catch (e) {
    // 后端业务错误(标题长度/时长篡改/敏感词/限流)message 是后端文案
    form.error = e?.message || '没发出去,再点一次'
  } finally {
    submitting.value = false
  }
}

/* ---------------- 编辑模式预填 ---------------- */
onMounted(async () => {
  if (!isEdit.value) return
  try {
    const d = await mkt.mktDetail(editId.value)
    form.category = d.category
    form.subTag = d.subTag || ''
    // 编辑时回填标题；若库里的 title 是派生出来的（等于「分类 · 游戏」），
    // 回填成空串更诚实 —— 否则用户会以为那条是自己写的标题。
    form.title = d.title && d.title !== d.mainText ? d.title : ''
    form.content = d.content
    form.targetNum = d.targetNum ?? 0
    form.price = d.price ?? 0
    form.location = d.location || ''
  } catch {
    ElMessage.error('这条活动没拉出来')
    router.replace('/mkt')
  }
})
</script>

<style scoped src="./MktPublish.css"></style>
