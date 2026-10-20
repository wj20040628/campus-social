<template>
  <!--
    兴趣标签区(2026-10-18 从 Profile.vue 抽出,2026-10-20 改为单行轮播)。

    为什么改:原来标签是多行 wrap,12 个兴趣占掉 4 行近 190px,
    把首屏的动态挤到看不见。改成单行无限滚动后只占 34px,
    信息一条不丢,首屏能完整看到 Tab 和第一条动态。

    自包含:自己拉 ofUser(targetId)、自己处理失败重试,父级只传 id 与视角。
    路由参数切换时父级会把 user 置 null,本组件随卡片卸载重挂,onMounted 即重新加载。
  -->
  <section v-if="tags.length" class="ptags">
    <div class="ptags__head">
      <span class="ptags__title">{{ isMe ? '我的兴趣' : 'TA 的兴趣' }}</span>
      <button v-if="isMe" type="button" class="ptags__more" @click="router.push('/discover')">
        发现同好
      </button>
    </div>

    <!--
      无限滚动靠「两份完全相同的内容 + translateX(-50%)」:
      第一份滚出屏幕时,第二份的第一个标签恰好落在第一份原来的起点,
      画面与起点完全一致,浏览器把位移重置回 0 时肉眼看不出接缝。
      所以第二份必须是完整副本,少一个标签就会在接缝处跳一下。
    -->
    <div class="ptags__marquee" :class="{ 'ptags__marquee--static': !scrollable }">
      <div class="ptags__track">
        <div class="ptags__group">
          <span v-for="t in tags" :key="t.id" class="ptags__tag">{{ t.name }}</span>
        </div>
        <!-- 副本仅供视觉衔接,对读屏器隐藏,否则同一个兴趣会被念两遍 -->
        <div v-if="scrollable" class="ptags__group" aria-hidden="true">
          <span v-for="t in tags" :key="'dup-' + t.id" class="ptags__tag">{{ t.name }}</span>
        </div>
      </div>
    </div>
  </section>
  <p v-else-if="failed" class="ptags__err">
    兴趣没拉出来<button type="button" class="ptags__retry" @click="load">重试</button>
  </p>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ofUser } from '@/api/tag'

const props = defineProps({
  targetId: { type: Number, required: true },
  isMe: { type: Boolean, default: false },
})

const router = useRouter()
const tags = ref([])
const failed = ref(false)

/**
 * 标签太少时不启动滚动。
 * 少于 6 个标签总宽通常不足一屏,强行滚动会露出空白循环,反而像卡住了。
 * 阈值按 390px 视口估算(平均每个标签约 70px),不追求精确 ——
 * 判错的代价只是「该滚的时候没滚」,不会破版。
 */
const scrollable = computed(() => tags.value.length >= 6)

async function load() {
  failed.value = false
  try {
    tags.value = await ofUser(props.targetId)
  } catch {
    failed.value = true
  }
}

onMounted(load)
</script>

<style scoped>
/* 兴趣标签:紧挨身份头与数据行,属于「你是谁」而非统计或内容 */
.ptags {
  margin: var(--ys-space-3) 0 0;
}
.ptags__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-bottom: var(--ys-space-2);
}
.ptags__title {
  font-size: var(--ys-text-xs);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-muted);
}
.ptags__more {
  min-height: 32px;
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}
.ptags__more:hover {
  background: var(--ys-accent-soft);
}

/* 两端渐隐:胶囊在屏幕边缘被硬切一半很难看,淡出后像「滚动到边缘自然消失」 */
.ptags__marquee {
  overflow: hidden;
  position: relative;
  -webkit-mask-image: linear-gradient(90deg, transparent 0, #000 20px, #000 calc(100% - 20px), transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0, #000 20px, #000 calc(100% - 20px), transparent 100%);
}
.ptags__track {
  display: flex;
  width: max-content;
  animation: ptags-scroll 44s linear infinite;
}
/* 悬停暂停:用户想看清某个标签时不该一直跑 */
.ptags__marquee:hover .ptags__track {
  animation-play-state: paused;
}
.ptags__group {
  display: flex;
  gap: var(--ys-space-2);
  /* 组间距必须和组内间距一致,否则两份的接缝处会挤在一起 */
  padding-right: var(--ys-space-2);
}
.ptags__tag {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  padding: 0 var(--ys-space-3);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  white-space: nowrap;
}
@keyframes ptags-scroll {
  from { transform: translateX(0); }
  to { transform: translateX(-50%); }
}
/* 标签少:不滚动也不遮罩,避免露出空白循环 */
.ptags__marquee--static {
  -webkit-mask-image: none;
  mask-image: none;
}
.ptags__marquee--static .ptags__track {
  animation: none;
}
/*
  尊重系统「减少动效」设置:关掉自动滚动,改成可手动横向滚动。
  继续自动滚会让前庭功能敏感的用户眩晕,这是无障碍的硬要求,不是可选项。
*/
@media (prefers-reduced-motion: reduce) {
  .ptags__track {
    animation: none;
  }
  .ptags__marquee {
    overflow-x: auto;
    -webkit-mask-image: none;
    mask-image: none;
  }
}

.ptags__err {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}
.ptags__retry {
  margin-left: var(--ys-space-2);
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  color: var(--ys-accent);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}
</style>
