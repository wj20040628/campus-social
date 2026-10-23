<template>
  <!--
    话题 chips(PostDetail.vue.md §1 行 4)
    `--ys-accent-soft` 底 + `--ys-accent` 字,高 24px,圆角 --ys-radius-md。

    超过 5 个只显示前 5 个 + 「+N」(设计 §3「内容超长」硬要求)。
    **不用横向滚动装全部**:一行 chips 在 375px 下放满 5 个就该换行了,
    藏起来的第 6 个话题在手机上永远发现不了,而话题是进题页的主要入口。
  -->
  <ul v-if="shown.length" class="chips">
    <li v-for="t in shown" :key="t">
      <button type="button" class="chips__item" @click="$emit('pick', t)">
        #{{ t }}
      </button>
    </li>
    <li v-if="overflow > 0" class="chips__more" :title="`还有 ${overflow} 个话题`">
      +{{ overflow }}
    </li>
  </ul>
</template>

<script setup>
/**
 * 话题 chips。**纯展示 + 抛事件**,不自己跳路由 ——
 * 让宿主页决定跳法(PostDetail 跳 /topic/:name,Feed 可能要带上当前筛选)。
 *
 * @param {string[]} topics 后端 PostVO.topics。脏值(非数组/含空串)在 computed 里挡掉
 */
import { computed } from 'vue'

/** 一行最多显示几个。超过的合并成「+N」 */
const MAX_SHOWN = 5

const props = defineProps({
  topics: { type: Array, default: () => [] },
})

defineEmits(['pick'])

/**
 * 后端 topics 是 List<String>,但历史数据里有过 null 与空串。
 * 直接 v-for 会在渲染时炸掉 —— 故先过滤再截断。
 */
const clean = computed(() =>
  (Array.isArray(props.topics) ? props.topics : [])
    .map((t) => String(t ?? '').replace(/^#+/, '').replace(/#+$/, '').trim())
    .filter(Boolean),
)

const shown = computed(() => clean.value.slice(0, MAX_SHOWN))
const overflow = computed(() => Math.max(0, clean.value.length - MAX_SHOWN))
</script>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0 var(--ys-space-4);
  list-style: none;
}

.chips__item {
  /* 高 24px 是视觉高;热区靠 padding 补到 44px 会让视觉尺寸对不上设计稿,
     所以这里保持 24px 视觉高 + 上下 padding 让热区达标,底色只包住文字 */
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  display: inline-flex;
  align-items: center;
  border: none;
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium); /* 500:accent 落 accent-soft 上仅 4.59:1 */
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
  /*
   * 长话题必须折行，不能撑破容器。
   *
   * 后端上限已收紧到 15 字，但**存量数据里有 40+ 字的旧话题**
   * （旧上限 64 时入库），所以前端这道防护不能省 —— 它兜的是历史数据。
   * overflow-wrap:anywhere 是唯一能断「#1111…（无空格长数字串）」的方式；
   * 折行后胶囊变高是可接受代价，好过卡片被顶宽、右侧内容被裁掉。
   *
   * text-align:left —— 折行后多行文字不能居中，否则像标题。
   */
  max-width: 100%;
  overflow-wrap: anywhere;
  word-break: break-word;
  text-align: left;
  line-height: 1.45;
}

.chips__item:hover {
  background: var(--ys-accent-hover);
  color: var(--ys-accent-on);
}

.chips__more {
  display: inline-flex;
  align-items: center;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}
</style>