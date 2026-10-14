<template>
  <div class="mpub">
    <!--
      顶栏:ownTop(路由 meta)—— 返回 + 标题 + 提交。
      手机/桌面同构:本页不做 el-drawer/el-dialog 双壳,是独立发布页。
      编辑模式(?id=)标题变「编辑活动」,隐藏时长选择。
    -->
    <header class="mpub__bar">
      <button type="button" class="mpub__back" aria-label="返回" @click="goBack">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <h1 class="mpub__title">{{ isEdit ? '编辑活动' : '发布活动' }}</h1>
      <button
        type="button"
        class="mpub__submit"
        :disabled="submitting"
        @click="submit"
      >
        {{ submitting ? (isEdit ? '存中' : '发中') : (isEdit ? '保存' : '发布') }}
      </button>
    </header>

    <div class="mpub__body">
      <MktPublishForm :form="form" :is-edit="isEdit" label-position="top" />
    </div>

    <!-- 吸底提交栏:手机键盘弹起时仍可达 -->
    <div class="mpub__foot">
      <button
        type="button"
        class="mpub__foot-btn"
        :disabled="submitting"
        @click="submit"
      >
        {{ submitting ? '提交中…' : (isEdit ? '保存修改' : '发布活动') }}
      </button>
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
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
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
const form = reactive({
  category: '',
  subTag: '',
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

/** 自填时长覆盖快捷选项;范围 1–12 */
const effectiveHours = () => {
  const h = form.customHours ?? form.launchHours
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

<style scoped>
.mpub {
  min-height: 100%;
  display: flex;
  flex-direction: column;
}

.mpub-inner {
  max-width: 720px;
  margin: 0 auto;
  width: 100%;
}

/* 顶栏:自绘(ownTop),返回 + 标题 + 提交 */
.mpub__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  min-height: var(--ys-topbar-h);
  padding: 0 var(--ys-gutter-phone);
  border-bottom: 1px solid var(--ys-border);
}

.mpub__back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: var(--ys-touch-min);
  min-height: var(--ys-touch-min);
  border: 0;
  background: none;
  color: var(--ys-fg-2);
  cursor: pointer;
}

.mpub__title {
  flex: 1;
  margin: 0;
  font-size: var(--ys-text-lg);
  font-weight: var(--ys-weight-semibold);
  text-align: center;
  color: var(--ys-fg);
}

.mpub__submit {
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-accent);
  cursor: pointer;
}

.mpub__submit:disabled { color: var(--ys-meta); cursor: not-allowed; }

.mpub__body {
  flex: 1;
  padding: var(--ys-space-4) var(--ys-gutter-phone);
}

@media (min-width: 1024px) {
  .mpub__body {
    padding: var(--ys-space-6) var(--ys-gutter-desktop);
    max-width: 720px;
    margin: 0 auto;
  }
}

/* 吸底提交栏:手机键盘弹起仍可达 */
.mpub__foot {
  padding: var(--ys-space-3) var(--ys-gutter-phone);
  padding-top: 0;
  border-top: 1px solid var(--ys-border);
  background: var(--ys-surface);
}

.mpub__foot-btn {
  width: 100%;
  min-height: var(--ys-touch-min);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.mpub__foot-btn:hover { background: var(--ys-accent-hover); }

.mpub__foot-btn:disabled {
  border-color: var(--ys-border-control);
  background: var(--ys-surface-2);
  color: var(--ys-meta);
  cursor: not-allowed;
}
</style>
