<template>
  <!--
    意见反馈审核条目(AdminFeedback.vue)

    操作不折叠进「更多」(与 AdminAnnItem 同理由):OD-19 要求手机端可用,
    375px 下「采纳 / 忽略」两个文字按钮横排刚好放得下。

    附件直接铺缩略图而不是「查看附件」按钮 —— 管理员判断一条反馈
    靠的往往就是那张截图,让他多点一次等于让这条反馈被误判。
  -->
  <li class="item">
    <div class="item__chips">
      <!--
        认证申诉角标（2026-10-19）：申诉与普通意见反馈共用一张表，
        用 content 前缀「【认证申诉】」标记。前缀去掉后再显示正文，
        否则管理员会同时看到前缀和正文里的「学号 xxx 认证未通过」。
      -->
      <span v-if="isAppeal" class="chip chip--appeal">认证申诉</span>
      <span class="chip" :class="`chip--${STATUS_TONE[item.status ?? 0]}`">
        {{ FEEDBACK_STATUS_TEXT[item.status ?? 0] }}
      </span>
      <!--
        申诉行不显示内容分类（2026-10-19）。认证申诉不是「使用问题」这类内容分类：
        落库时 category 只是 NOT NULL 的兜底填充（未注册申诉没有用户选择的分类），
        真正的区分靠上面的「认证申诉」角标。把兜底的「使用问题」摆在申诉旁边，
        会让管理员误以为申诉被归到了使用问题。
      -->
      <span v-if="!isAppeal" class="chip chip--cat">{{ CATEGORY_TEXT[item.category] || '其他' }}</span>
    </div>

    <p class="item__body">{{ bodyText }}</p>

    <!--
      认证申诉的联系邮箱（2026-10-19）。未注册用户没有账号，邮箱是管理员
      唯一的联系渠道，落进了 content 的「联系邮箱 xxx」段。抽出来做成
      mailto 直连，管理员不用再手动把邮箱复制到邮件软件。
    -->
    <p v-if="appealMail" class="item__mail">
      联系邮箱：<a :href="`mailto:${appealMail}`">{{ appealMail }}</a>
    </p>

    <!-- 附件:解析失败宁可显示提示,也不能整条反馈崩掉 -->
    <ul v-if="media.length" class="item__media">
      <li v-for="(m, i) in media" :key="i" class="item__cell">
        <el-image
          v-if="m.type === 'img'"
          :src="resolveThumb(m.url)"
          fit="cover"
          class="item__thumb"
          :preview-src-list="imgUrls"
          :initial-index="i"
          preview-teleported
          :alt="`反馈截图 ${i + 1}`"
        />
        <video v-else :src="resolveUrl(m.url)" class="item__thumb" controls preload="metadata" />
      </li>
    </ul>
    <p v-else-if="mediaBroken" class="item__mediawarn">附件加载不出来</p>

    <p class="item__meta ys-num">
      {{ submitterText }}
      <span class="item__dot">·</span>
      {{ timeText }}
    </p>

    <!-- 已处置:把处理说明回显出来。反馈是人对人的沟通,
         管理员写的那句话要能被下一个管理员和提交者看见,而不是躺在库里 -->
    <p v-if="item.handleNote" class="item__note">
      处理说明：{{ item.handleNote }}
    </p>

    <div v-if="item.status === 0" class="item__ops">
      <button
        type="button"
        class="op op--accent"
        :disabled="busy"
        :aria-label="`采纳这条反馈:${preview}`"
        @click="$emit('adopt', item)"
      >
        采纳
      </button>
      <button
        type="button"
        class="op"
        :disabled="busy"
        :aria-label="`忽略这条反馈:${preview}`"
        @click="$emit('ignore', item)"
      >
        忽略
      </button>
    </div>
  </li>
</template>

<script setup>
/**
 * 单条反馈的审核操作行。
 *
 * mediaJson 由后端原样透传字符串(与 post.media 同形),解析放前端:
 * 只有这一个消费方,后端拆成 List<MediaVO> 只会多一层无意义的转换。
 */
import { computed } from 'vue'
import { CATEGORY_TEXT, FEEDBACK_STATUS_TEXT } from '@/api/feedback'
import { resolveThumb, resolveUrl } from '@/utils/resolve'

/** status -> chip 色调类名。与 AdminAnnItem 的 chip--live 等同一套命名 */
const STATUS_TONE = { 0: 'live', 1: 'done', 2: 'off' }

/** 申诉前缀，与后端 FeedbackServiceImpl.APPEAL_PREFIX 保持一致 */
const APPEAL_PREFIX = '【认证申诉】'

const props = defineProps({
  item: { type: Object, required: true },
  busy: { type: Boolean, default: false },
})

defineEmits(['adopt', 'ignore'])

const isAppeal = computed(() => String(props.item?.content || '').startsWith(APPEAL_PREFIX))
/** 去掉前缀的正文：前缀已经由角章承担，正文里再出现一次是重复 */
const bodyText = computed(() =>
  isAppeal.value ? String(props.item?.content || '').slice(APPEAL_PREFIX.length) : props.item?.content,
)

/**
 * 认证申诉里的联系邮箱。未注册用户没账号，邮箱是管理员唯一联系渠道。
 * 由后端落进 content 的「联系邮箱 xxx」段，这里按那个固定措辞抽出，
 * 非申诉行 / 抽不到时返回空（v-if 不渲染该行）。
 */
const appealMail = computed(() => {
  if (!isAppeal.value) return ''
  const m = String(props.item?.content || '').match(/联系邮箱\s*([^\s；;，,]+)/)
  return m ? m[1] : ''
})

/**
 * 提交人显示名。
 * 申诉人 user_id 为 NULL（注册没成功、还没有账号），后端联表取不到昵称。
 * 直接显示「已注销用户」会误导（人家是从未注册），申诉行改标「未注册（申诉人）」。
 */
const submitterText = computed(() => {
  if (props.item?.userNickname) return props.item.userNickname
  return isAppeal.value ? '未注册（申诉人）' : '已注销用户'
})

/** 解析失败时降级成空数组 + 标记,而不是让整个页面崩在 JSON.parse 上 */
const parsed = computed(() => {
  const raw = props.item.mediaJson
  if (!raw) return { list: [], broken: false }
  try {
    const list = JSON.parse(raw)
    return { list: Array.isArray(list) ? list : [], broken: false }
  } catch {
    return { list: [], broken: true }
  }
})

const media = computed(() => parsed.value.list)
const mediaBroken = computed(() => parsed.value.broken)
/** 只给图片,EP 的预览层只对图片有意义 */
const imgUrls = computed(() => media.value.filter((m) => m.type !== 'video').map((m) => resolveThumb(m.url)))

/** 给 aria-label 用的一句话摘要:不能把 1000 字塞进 label */
const preview = computed(() => String(props.item.content || '').slice(0, 20))

const timeText = computed(() => {
  const t = props.item.createTime
  if (!t) return ''
  // 契约是 ISO 字符串;Safari 不认空格分隔的 'YYYY-MM-DD HH:mm:ss',统一换成 T
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(t)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
})
</script>

<style scoped>
/* 每条反馈是一张独立圆角卡片:列表不再用「一张大卡 + 分隔线」,
   卡片之间有间距,管理员一眼能分出边界(2026-10-19) */
.item {
  padding: var(--ys-space-4);
  list-style: none;
  background: var(--ys-surface);
  border-radius: var(--ys-radius-xl);
  box-shadow: var(--ys-shadow-1);
}

.item__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-2);
}

.chip {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 var(--ys-space-2);
  border-radius: var(--ys-radius-sm);
  font-size: var(--ys-text-xs);
  line-height: 1;
}

.chip--live {
  background: var(--ys-success-soft);
  color: var(--ys-success);
}

.chip--done {
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}

.chip--off {
  background: var(--ys-surface-2);
  color: var(--ys-meta);
}

.chip--cat {
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
}

/* 认证申诉（2026-10-19）：用暖色与「待处理」的绿区分开——
   申诉有真实用户等着被解封，优先级高于普通意见反馈，必须一眼可见 */
.chip--appeal {
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-weight: var(--ys-weight-medium);
}

.item__body {
  margin: 0;
  font-size: var(--ys-text-md);
  line-height: var(--ys-lh-md);
  color: var(--ys-fg);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 认证申诉的联系邮箱（2026-10-19）：未注册用户唯一联系渠道，突出为 accent 色 + 可点 mailto */
.item__mail {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
}

.item__mail a {
  color: var(--ys-accent);
  text-decoration: underline;
  word-break: break-all;
}

.item__media {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--ys-space-2);
  margin: var(--ys-space-2) 0 0;
  padding: 0;
  list-style: none;
}

.item__cell {
  aspect-ratio: 1;
  min-height: 0;
  overflow: hidden;
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
}

/* el-image 内部还有一层 <img>,根元素上的 object-fit 管不到它。
   不下钻到内层 img,长截图会按原始比例把格子撑高,缩略图区变成一大片空白。
   统一钉成 100% + cover,保证每张都是正方形缩略图。 */
.item__cell :deep(.el-image),
.item__cell :deep(img) {
  display: block;
  width: 100%;
  height: 100%;
}

.item__cell :deep(img) {
  object-fit: cover;
}

.item__thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.item__mediawarn {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-danger);
}

.item__meta {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}

.item__dot {
  margin: 0 var(--ys-space-1);
}

.item__note {
  margin: var(--ys-space-2) 0 0;
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-md);
  background: var(--ys-surface-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
}

.item__ops {
  display: flex;
  gap: var(--ys-space-5);
  margin-top: var(--ys-space-2);
}

.op {
  min-height: var(--ys-touch-min);
  padding: 0;
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
}

.op--accent {
  color: var(--ys-accent);
}

.op:disabled {
  color: var(--ys-meta);
  cursor: not-allowed;
}

.op:not(:disabled):active {
  opacity: 0.7;
}
</style>
