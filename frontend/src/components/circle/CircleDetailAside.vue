<template>
  <!--
    圈子详情 · 桌面端右栏（2026-10-21 新增）。

    【为什么加】桌面 1512 视口下内容列固定 640，右侧 600px 全空。
    这个圈子的信息量（成员数、帖子数、圈主）确实不多，但空白不该是
    「没设计」的样子 —— 把这类静态元信息收到右栏，主列就能专心承载内容。

    【数据全部来自已有 CircleVO 与已加载列表】
    memberCount / postCount / owner / createTime / myRole 都在契约里，
    不需要新增接口。活动数用已加载的 activities.length（同一页拉过）。

    【只在桌面显示】与集市详情页的 MktDetailAside 同一做法：
    手机端没有横向空间，硬塞右栏会变成上下堆叠、把内容推到屏幕外。
  -->
  <aside class="cda">
    <!-- 圈子概况 -->
    <section class="cda__panel">
      <p class="cda__h"><i class="cda__sq" />这个圈子</p>
      <div class="cda__row"><span>成员</span><b>{{ memberText }}</b></div>
      <div class="cda__row"><span>帖子</span><b>{{ postText }}</b></div>
      <div class="cda__row"><span>活动</span><b>{{ actText }}</b></div>
      <div class="cda__row"><span>创建</span><b>{{ createdText }}</b></div>
    </section>

    <!-- 圈主 -->
    <section v-if="ownerNickname" class="cda__panel">
      <p class="cda__h"><i class="cda__sq" />圈主</p>
      <div class="cda__owner">
        <InitialAvatar
          :size="34"
          :src="ownerAvatar ? resolveThumb(ownerAvatar) : ''"
          :seed="ownerId || ''"
          :name="ownerNickname"
        />
        <div class="cda__owner-txt">
          <p class="cda__owner-n">{{ ownerNickname }}</p>
          <p class="cda__owner-d">发起这个圈子的人</p>
        </div>
      </div>
    </section>

    <!-- 圈内规则 -->
    <section class="cda__panel">
      <p class="cda__h"><i class="cda__sq" />圈内规则</p>
      <p class="cda__tip">
        发帖前确认内容与圈子主题相关。圈主与管理可以置顶、下架、移出帖子。
      </p>
    </section>
  </aside>
</template>

<script setup>
/**
 * 圈子详情右栏：纯展示，不含请求、不含业务判断。
 *
 * 所有数据由父组件算好传进来 —— 与 MktDetailAside 同一个约定：
 * 右栏只负责「长什么样」，避免它自己再发一次请求导致数据口径不一致。
 */
import { computed } from 'vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { resolveThumb } from '@/utils/resolve'

const props = defineProps({
  /** CircleVO.memberCount */
  memberCount: { type: Number, default: null },
  /** CircleVO.postCount */
  postCount: { type: Number, default: null },
  /** 已加载的活动条数（本页拉过的 activities.length） */
  actCount: { type: Number, default: null },
  /** CircleVO.createTime，后端下发的是 date-time 字符串 */
  createTime: { type: String, default: '' },
  /** CircleVO.owner 里的展示字段 */
  ownerId: { type: [Number, String], default: null },
  ownerNickname: { type: String, default: '' },
  ownerAvatar: { type: String, default: '' },
})

/** 数字缺省时显示「—」而不是 0：0 是「确实没有」，缺省是「不知道」 */
const fmt = (n, unit) => (n == null ? '—' : `${n} ${unit}`)

const memberText = computed(() => fmt(props.memberCount, '人'))
const postText = computed(() => fmt(props.postCount, '条'))
const actText = computed(() => fmt(props.actCount, '个'))

/**
 * 创建时间只保留「年月日」。
 * 右栏是概览，精确到分钟没有意义；而且 createTime 是 ISO 字符串，
 * 直接切前 10 位比引入 dayjs 更省（这一栏不会跨时区显示歧义）。
 */
const createdText = computed(() => {
  const s = props.createTime || ''
  if (!s) return '—'
  const d = s.slice(0, 10)
  return /^\d{4}-\d{2}-\d{2}$/.test(d) ? d : '—'
})
</script>

<style scoped src="./CircleDetailAside.css"></style>
