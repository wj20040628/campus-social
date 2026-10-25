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
      <!--
        双栏容器（2026-10-21 加）：桌面端主列 640px + 右栏 268px。
        移动端单列，外观与改版前一致。
      -->
      <div class="ann-cols">
        <!-- ============ 主列 ============ -->
        <div class="ann-main">
          <article class="wrap">
            <header class="head">
              <div class="head__chips">
                <!--
                  2026-10-21：公告不再显示任何发布方身份。
                  原来的 .org 徽章装的是 publisher.schoolName / nickname，
                  已认证发布人的公告会显示「黄河交通学院」这类具体校名 ——
                  而本平台是毕业设计原型，与学校无隶属关系（见 legalDocs 免责声明），
                  公告挂具体校名等于替学校背书。
                  现在只保留三个客观标签：分类 / 紧急 / 置顶。
                -->
                <span class="cat">{{ categoryLabel }}</span>
                <span v-if="ann.category === 5" class="urgent">紧急</span>
                <span v-if="ann.isTop === 1" class="top">
                  <el-icon :size="14"><Top /></el-icon>置顶
                </span>
              </div>

              <h1 class="head__title">{{ ann.title }}</h1>

              <p class="head__meta">
                <span class="ys-num">{{ publishText }}</span>
                <template v-if="ann.readCount != null">
                  <span class="head__sep" aria-hidden="true">·</span>
                  <span class="ys-num">阅读 {{ cap(ann.readCount) }} 次</span>
                </template>
              </p>
            </header>

            <!-- 正文:无正文时整个区块隐藏,标题本身就是信息,不显示空态 -->
            <div v-if="ann.content" class="body" role="article">{{ ann.content }}</div>

            <!--
              相关公告（2026-10-21 新增）。
              改版前看完一条公告就只剩一个「我知道了」，页面上没有任何「下一条」的去向。
              数据复用列表接口取前几条、筛掉自己，**不新增接口**。
              同分类优先：用户读某类公告时，下一条往往还是同一类。
            -->
            <section v-if="related.length" class="rel" aria-label="相关公告">
              <h2 class="rel__h"><span class="rel__sq" aria-hidden="true"></span>相关公告</h2>
              <RouterLink
                v-for="r in related"
                :key="r.id"
                :to="`/announcement/${r.id}`"
                class="relrow"
              >
                <span class="relrow__t">{{ r.title }}</span>
                <span class="relrow__d ys-num">{{ shortDate(r.publishTime) }}</span>
              </RouterLink>
              <RouterLink to="/announcement" class="rel__more">看全部公告 ›</RouterLink>
            </section>
          </article>
        </div>

        <!-- ============ 右栏（仅桌面，CSS 控制显隐）============ -->
        <aside class="ann-aside" aria-label="公告信息">
          <section class="panel">
            <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>这条公告</h2>
            <div class="panel__row"><span>发布</span><b class="ys-num">{{ publishText || '—' }}</b></div>
            <div class="panel__row"><span>阅读</span><b class="ys-num">{{ ann.readCount ?? 0 }} 次</b></div>
            <div class="panel__row"><span>分类</span><b>{{ categoryLabel }}</b></div>
            <div class="panel__row"><span>状态</span><b>{{ ann.isRead ? '已读' : '未读' }}</b></div>
            <div class="panel__row"><span>是否置顶</span><b>{{ ann.isTop === 1 ? '是' : '否' }}</b></div>
          </section>

          <section v-if="related.length" class="panel">
            <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>相关公告</h2>
            <RouterLink
              v-for="r in related"
              :key="`p-${r.id}`"
              :to="`/announcement/${r.id}`"
              class="panel__row panel__row--link"
            >
              <span class="panel__t">{{ r.title }}</span>
              <b class="ys-num panel__d">{{ shortDate(r.publishTime) }}</b>
            </RouterLink>
          </section>

          <section class="panel">
            <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>提示</h2>
            <p class="panel__tip">
              <b>公告一经发布不可编辑</b>，如需更正由发布方撤回后重发。<br />
              对内容有疑问请走<b>意见反馈</b>。
            </p>
          </section>
        </aside>
      </div>

      <!--
        吸底操作栏。
        2026-10-21 修 bug：改版前 .actionbar 没有宽度约束，实测桌面端
        x=245 w=1243（横贯整个内容区），而正文列只有 x=547 w=640 ——
        吸底栏比正文宽 603px，左右各多出约 300px，里面却只有一行小字和一个按钮。
        现在把它包进与正文同宽的容器里。
      -->
      <div class="actionbar-wrap">
        <div class="actionbar">
          <span class="actionbar__state">{{ readText }}</span>
          <button type="button" class="actionbar__btn" @click="router.back()">
            我知道了
          </button>
        </div>
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
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { Top, WarningFilled, DocumentDelete } from '@element-plus/icons-vue'
import * as api from '@/api/announcement'
import { relTime } from '@/utils/relTime'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import { CATEGORY } from '@/constants/announcement'
/* 2026-10-21：VerifiedShield 已移除 —— 公告不再显示发布方的认证状态 */

const route = useRoute()
const router = useRouter()

const ann = ref(null)
const loading = ref(true)
const error = ref(false)
const notFound = ref(false)
const readDone = ref(false)
/** 相关公告（同分类优先，取自列表接口，不新增后端端点） */
const related = ref([])

/* 分类文案来自 constants/announcement.js，本文件不再自写一份 */
const categoryLabel = computed(() => CATEGORY[ann.value?.category] || '通知')

/*
 * 2026-10-21：hasRealPublisher / orgName / publisherName / orgInitial / publisherDesc
 * 五个 computed 全部删除。
 *
 * 它们原本用于渲染「发布方徽章 + 发布方信息卡」，而那块内容的来源是
 * publisher.schoolName —— 已认证发布人的公告会显示「黄河交通学院」。
 * 本平台是毕业设计原型、与学校无隶属关系（legalDocs 有明确免责声明），
 * 公告上挂具体校名等于替学校背书，所以整块移除，不再显示任何发布方身份。
 * 后端也同步把 AnnouncementPublisherVO.schoolName 恒置为 null。
 */

/**
 * 时间（2026-10-21 改相对时间）。
 * 原来是 `String(t).slice(0,16).replace('T',' ')`，得到 "2026-10-12 15:38"，
 * 与首页卡片、公告列表页已统一的相对时间不一致。
 */
const publishText = computed(() => {
  const t = ann.value?.publishTime
  if (!t) return ''
  return relTime(t) || String(t).slice(0, 16).replace('T', ' ')
})

/** 相关公告里的日期：短格式，与列表页一致 */
function shortDate(t) {
  return t ? relTime(t) || String(t).slice(5, 10) : ''
}

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
    // 相关公告独立加载：它失败不该影响正文阅读（下面 loadRelated 内部吞错）
    loadRelated()
  } catch (e) {
    // 404 与网络错误区分:404 是「撤下了」,其余是「没加载出来」
    if (e?.response?.status === 404) notFound.value = true
    else error.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 拉相关公告。
 *
 * 复用 GET /announcement 列表接口（不新增后端端点）：取一页，筛掉当前这条，
 * 再优先保留**同分类**的 —— 用户读某类公告时，下一条往往还是同一类。
 * 最多 3 条，凑不满就用其他分类补齐（总比空着好，它同时是「看完的去向」）。
 *
 * 失败静默：相关公告是增强信息，挂了不该让人以为正文出了问题。
 */
async function loadRelated() {
  try {
    const data = await api.list({ category: ann.value?.category ?? null, size: 8 })
    const sameCat = (data?.list || []).filter((a) => String(a.id) !== String(route.params.id))
    if (sameCat.length >= 3) {
      related.value = sameCat.slice(0, 3)
      return
    }
    // 同分类不够 3 条：再拉一次全量补齐（这次不带 category）
    const all = await api.list({ size: 8 })
    const seen = new Set([String(route.params.id), ...sameCat.map((a) => String(a.id))])
    const fill = (all?.list || []).filter((a) => !seen.has(String(a.id)))
    related.value = [...sameCat, ...fill].slice(0, 3)
  } catch {
    related.value = []
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