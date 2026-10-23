<template>
  <!--
    公告编辑器(AdminAnnouncement.vue.md §1.3)
    全屏页面而非抽屉:标题+正文+分类+置顶四个字段,抽屉装不下。

    路由 meta 不设 back —— 退出只能走顶栏「取消」。
    已填内容时取消弹 ActionSheet 二次确认(「丢掉 / 继续编辑」),
    防止 iOS 边缘返回手势把写了 20 分钟的公告丢掉。
  -->
  <div class="ed">
    <!-- 自带顶栏:左「取消」中标题右「发布」(§1.3)。路由 meta.ownTop 让 MainLayout 不渲染全站顶栏 -->
    <header class="ed__bar">
      <button type="button" class="ed__bar-btn" @click="onCancel">取消</button>
      <h1 class="ed__bar-title">{{ editId ? '改公告' : '新建公告' }}</h1>
      <button
        type="button"
        class="ed__bar-btn ed__bar-btn--primary"
        :disabled="!canPublish || busy"
        @click="submit(true)"
      >
        {{ busy ? '发中' : '发布' }}
      </button>
    </header>

    <form class="ed__form" @submit.prevent="submit(true)">
      <!-- 区 1:标题 -->
      <div class="ed__sec">
        <label class="ed__label" for="ann-title">标题</label>
        <el-input
          id="ann-title"
          v-model="form.title"
          class="ed__ctl"
          maxlength="64"
          show-word-limit
          placeholder="一句话说清这条公告要通知什么"
        />
      </div>

      <!--
        区 2:分类。
        2026-10-21:从 <fieldset>/<legend> 换成 div + 3×2 网格，两个原因：
          1. fieldset 的 UA 默认边框会露出来（CSS 里那段 border:0 之前漏了）
          2. 390px 下 6 个 chip 横排会折成「5 + 1」，「其他」孤零零挂第二行
        网格在手机上两行整齐 6 个，桌面端同样整齐。
      -->
      <div class="ed__sec">
        <span class="ed__label" id="ed-cat-label">分类</span>
        <div class="ed__cgrid" role="radiogroup" aria-labelledby="ed-cat-label">
          <button
            v-for="c in CATEGORY_CHIPS.slice(1)"
            :key="c.value"
            type="button"
            role="radio"
            class="ed__chip"
            :class="{ 'is-on': form.category === c.value }"
            :aria-checked="form.category === c.value"
            @click="form.category = c.value"
          >
            {{ c.label }}
          </button>
        </div>
      </div>

      <!-- 区 3:正文 -->
      <div class="ed__sec">
        <label class="ed__label" for="ann-body">正文</label>
        <el-input
          id="ann-body"
          v-model="form.content"
          type="textarea"
          class="ed__body"
          :rows="10"
          placeholder="把要紧的时间、地点、怎么报名写在前面"
        />
      </div>

      <!-- 区 4:置顶。hint 常显,不是报错后才出现 -->
      <div class="ed__sec ed__sec--row">
        <div class="ed__top">
          <span class="ed__label ed__label--plain">置顶这条公告</span>
          <span class="ed__hint">置顶最多同时 {{ MAX_TOP }} 条,超过时先取消一条</span>
        </div>
        <el-switch v-model="isTop" aria-label="置顶这条公告" />
      </div>
    </form>

    <!-- 吸底操作栏:左存草稿 / 右发布 -->
    <footer class="ed__foot">
      <p v-if="error" class="ed__err" role="alert">{{ error }}</p>
      <div class="ed__foot-row">
        <button type="button" class="ed__btn" :disabled="busy" @click="submit(false)">
          存草稿
        </button>
        <button
          type="button"
          class="ed__btn ed__btn--primary"
          :disabled="!canPublish || busy"
          @click="submit(true)"
        >
          {{ busy ? '提交中' : '发布' }}
        </button>
      </div>
      <SafeArea position="bottom" />
    </footer>

    <!-- 取消二次确认 -->
    <ActionSheet v-model="confirmOpen" title="内容还没发,确定丢掉?">
      <button type="button" class="asheet__act asheet__act--danger" @click="discard">
        丢掉,回列表
      </button>
      <button type="button" class="asheet__act" @click="confirmOpen = false">继续编辑</button>
    </ActionSheet>
  </div>
</template>

<script setup>
/**
 * 新建 / 编辑公告。
 * 路由 /admin/announcement/edit/:id? —— 有 id 是编辑,无 id 是新建。
 * 不设 meta.back,故本页**自带顶栏**(MainLayout 在无 back 时给品牌型顶栏,
 * 桌面端有侧栏;手机上品牌型顶栏没有返回箭头,取消只能靠底部这排按钮)。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter, useRoute, onBeforeRouteLeave } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import * as api from '@/api/announcement'
import { CATEGORY_CHIPS, MAX_TOP } from '@/constants/announcement'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import SafeArea from '@/components/mobile/SafeArea.vue'

const router = useRouter()
const route = useRoute()

const editId = computed(() => route.params.id || null)

const form = ref({ title: '', content: '', category: 6 })
const isTop = ref(false)
const busy = ref(false)
const error = ref('')
const confirmOpen = ref(false)
const loading = ref(false)
/**
 * submitting 与 busy 的区别(刻意分成两个):
 * busy    = 请求在飞,用来禁用按钮防连点
 * submitting = 本次提交会离开本页,用来让路由守卫放行
 * 合成一个会在「加载已有公告时(busy=true 但不提交)」误放行离开拦截。
 */
const submitting = ref(false)

/** 标题必填、正文必填、分类必选。发布与存草稿同一套校验 —— 草稿也要有标题,否则列表里全是「无标题」 */
const canPublish = computed(
  () => !!(form.value.title.trim() && form.value.content.trim() && form.value.category),
)

/** 「有内容」的判定:只有标题非空也算 —— 用户可能刚打了个标题就被打断了 */
const dirty = computed(() => !!(form.value.title.trim() || form.value.content.trim()))

/**
 * dirty 的「已处置」开关:内容被成功提交(发布/存草稿)或用户显式放弃后置 true。
 * 关键时序坑:submit 里 router.replace 是**异步**的,它排在微任务后真正执行守卫,
 * 而 submit 的 finally 早已把 submitting 置回 false —— 等守卫跑起来时 submitting
 * 已经是 false,只剩 dirty 还是 true(内容还在 form 里)→ 成功提交的离开被自己拦截,
 * 弹「内容还没发,确定丢掉?」,而那个确认框的「丢掉」重放导航又被拦一次 → 死循环。
 * 修法:提交成功发起导航前先置 disposed=true,让守卫与「放弃」两条路都不再拦。
 */
const disposed = ref(false)

async function load() {
  if (!editId.value) return
  loading.value = true
  try {
    // 后台取单条公告没有独立端点,复用前台 detail(后台身份读得到全部状态字段)
    const data = await api.detail(editId.value)
    form.value = {
      title: data.title || '',
      content: data.content || '',
      category: data.category ?? 6,
    }
    isTop.value = data.isTop === 1
  } catch {
    ElMessage.error('公告没拉出来,回列表重试')
    router.replace('/admin/announcement')
  } finally {
    loading.value = false
  }
}

async function submit(publishNow) {
  if (!canPublish.value || busy.value) return
  busy.value = true
  submitting.value = true
  error.value = ''
  try {
    const body = {
      title: form.value.title.trim(),
      content: form.value.content.trim(),
      category: form.value.category,
      isTop: isTop.value ? 1 : 0,
      topExpireTime: isTop.value ? expireIn7Days() : null,
      publishNow,
    }
    if (editId.value) {
      // PATCH 是部分更新,必须带 status:草稿改内容后仍应是草稿,
      // 不带的话后端可能按「内容有变更即发布」处理 —— 那是后端行为,前端显式声明
      await api.update(editId.value, {
        title: body.title,
        content: body.content,
        category: body.category,
        isTop: body.isTop,
        topExpireTime: body.topExpireTime,
      })
    } else {
      await api.create(body)
    }
    // 禁止庆祝动画(§4):一句 Toast 就够
    // 内容已成功落库,标记「已处置」:即将的离开导航(哪怕异步晚一步跑守卫)
    // 与后续放弃都不会再被 onBeforeRouteLeave 拦下来 —— 见 disposed 的注释。
    disposed.value = true
    ElMessage.success(publishNow ? '公告发出去了,全校同学会看到' : '草稿存好了')
    router.replace('/admin/announcement')
  } catch {
    // §3 错误态:**标题与正文全部保留**,只给一句可行动的话
    error.value = publishNow ? '没发出去,再点一次' : '草稿没存上,再点一次'
  } finally {
    busy.value = false
    submitting.value = false
  }
}

function expireIn7Days() {
  const d = new Date()
  d.setDate(d.getDate() + 7)
  return d.toISOString()
}

onMounted(load)

/*
 * 拦截路由级离开:iOS 边缘返回手势、桌面侧栏点别的页面都会走到这里。
 * 这比「只在顶栏放个取消按钮」可靠 —— 顶栏按钮用户可以绕过,手势绕不过。
 *
 * next(false) 保持在当前页,等用户在 ActionSheet 里选「丢掉 / 继续编辑」。
 * 选了「丢掉」时 confirmOpen 已关且本页已被再次进入,
 * 用 pendingLeave 记住原本要去哪,discard() 里重放这次跳转。
 */
const pendingLeave = ref(null)

onBeforeRouteLeave((to, from, next) => {
  // 不该被拦的三种情况:
  //  - 没脏内容(!dirty):无可丢,直接放行
  //  - 本次正在提交(submitting):放行
  //  - 内容已处置(disposed):已成功提交或已显式放弃,放行 —— 修复提交后死循环
  if (!dirty.value || submitting.value || disposed.value) {
    next()
    return
  }
  pendingLeave.value = to.fullPath
  confirmOpen.value = true
  next(false)
})

function onCancel() {
  if (dirty.value && !disposed.value) confirmOpen.value = true
  else discard()
}

function discard() {
  confirmOpen.value = false
  // 放弃即「内容已处置」:重放的这次导航与之后任何离开都不该再弹
  disposed.value = true
  if (pendingLeave.value) {
    const to = pendingLeave.value
    pendingLeave.value = null
    router.replace(to)
    return
  }
  router.replace('/admin/announcement')
}
</script>

<style scoped src="./AdminAnnouncementEdit.css"></style>
