<template>
  <div class="ann-detail">
    <!-- 加载骨架:标题区 + 正文 8 行,宽度参差 -->
    <div v-if="loading" class="wrap">
      <SkeletonList :count="3" variant="panel" />
    </div>

    <!-- 公告不存在 / 已下架 -->
    <StateBlock
      v-else-if="notFound"
      variant="error"
      title="这条公告已经撤下了"
      description="可能是发布方下架了,去列表看看别的"
      action-label="看全部公告"
      :icon="DocumentDelete"
      @action="router.push('/announcement')"
    />

    <!-- 网络失败 -->
    <StateBlock
      v-else-if="error"
      variant="error"
      title="公告没加载出来"
      description="可能是网络断了,点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="load"
    />

    <template v-else-if="ann">
      <article class="wrap">
        <header class="head">
          <div class="head__chips">
            <span class="org">{{ orgName }}</span>
            <span class="cat">{{ categoryLabel }}</span>
            <span v-if="ann.category === 5" class="urgent">紧急</span>
            <span v-if="ann.isTop === 1" class="top">
              <el-icon :size="16"><Top /></el-icon>置顶
            </span>
          </div>

          <h1 class="head__title">{{ ann.title }}</h1>

          <p class="head__meta">
            <span class="ys-num">{{ publishText }}</span>
            <template v-if="ann.readCount != null">
              <span aria-hidden="true">·</span>
              <span class="ys-num">阅读 {{ cap(ann.readCount) }} 次</span>
            </template>
          </p>

          <!-- 署名 + 认证标记:官方公告必须能看到「是谁发的、是否已认证」(AC-12) -->
          <p class="head__sign">
            {{ ann.publisher?.schoolName || ann.publisher?.nickname || '学校通知' }}
            <VerifiedShield
              v-if="ann.publisher?.verified"
              :size="16"
              class="head__verified"
            />
            <span v-if="ann.publisher?.verified" class="head__verified-text">已认证</span>
          </p>
        </header>

        <!-- 正文:无正文时整个区块隐藏,标题本身就是信息,不显示空态 -->
        <div v-if="ann.content" class="body" role="article">{{ ann.content }}</div>
      </article>

      <!-- 吸底操作栏 -->
      <div class="actionbar">
        <span class="actionbar__state">{{ readText }}</span>
        <button type="button" class="actionbar__btn" @click="router.back()">
          我知道了
        </button>
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * 公告详情(AnnouncementDetail.vue.md)
 *
 * **已读回执的最容易做错的一点**(§7):
 * 看到 AC-09 写「幂等」很容易顺手加一句 `if (!ann.is_read) postRead()`。
 * 这会导致:手机读了、后端写成功但前端 is_read 缓存没更新,
 * 下次进入前端以为没读过就不发 -> 发布方的「谁还没看」名单里永远有这个人,
 * AC-10 直接失效。
 *
 * 正确做法:**无条件调用**,幂等交给后端 uk_ann_user 保证。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Top, WarningFilled, DocumentDelete } from '@element-plus/icons-vue'
import * as api from '@/api/announcement'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import VerifiedShield from '@/icons/VerifiedShield.vue'

const route = useRoute()
const router = useRouter()

const ann = ref(null)
const loading = ref(true)
const error = ref(false)
const notFound = ref(false)
const readDone = ref(false)

const CATEGORY = { 1: '教务', 2: '后勤', 3: '活动', 4: '社团', 5: '紧急', 6: '其他' }

const categoryLabel = computed(() => CATEGORY[ann.value?.category] || '通知')

const orgName = computed(() => {
  const p = ann.value?.publisher
  const raw = p?.schoolName || p?.nickname || '学校通知'
  return raw.length > 6 ? `${raw.slice(0, 6)}…` : raw
})

const publishText = computed(() => {
  const t = ann.value?.publishTime
  return t ? String(t).slice(0, 16).replace('T', ' ') : ''
})

/**
 * 已读状态文字。
 * 回执失败时显示「已读没记上,不影响你看」—— 回执失败对读者零影响,
 * 报错是噪音(AnnouncementDetail.vue.md §1.4 第 3 条)。
 */
const readText = computed(() => {
  if (readDone.value) {
    const t = ann.value?.readTime
    return t ? `已读 · ${String(t).slice(5, 16).replace('T', ' ')}` : '已读'
  }
  if (ann.value?.isRead) return '已读'
  return '标记为已读'
})

function cap(n) {
  return n > 9999 ? '9999+' : String(n)
}

async function load() {
  loading.value = true
  error.value = false
  notFound.value = false
  try {
    ann.value = await api.detail(route.params.id)
    // 延迟 300ms 再发回执,不与首屏渲染抢主线程(贡献 AC-17 的 LCP 预算)
    setTimeout(markRead, 300)
  } catch (e) {
    // 404 与网络错误区分:404 是「撤下了」,其余是「没加载出来」
    if (e?.response?.status === 404) notFound.value = true
    else error.value = true
  } finally {
    loading.value = false
  }
}

/** 幂等回执。无条件调,失败静默重试一次,再不成功就只在页面上说明。 */
async function markRead() {
  try {
    await api.markRead(route.params.id)
    readDone.value = true
  } catch {
    try {
      await api.markRead(route.params.id)
      readDone.value = true
    } catch {
      readDone.value = false
    }
  }
}

onMounted(load)
</script>

<style scoped src="./AnnouncementDetail.css"></style>