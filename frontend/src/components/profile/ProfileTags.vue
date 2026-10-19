<template>
  <!--
    兴趣标签区(2026-10-18 从 Profile.vue 抽出,原 326 行超标的主因之一)。
    自包含:自己拉 ofUser(targetId)、自己处理失败重试,父级只传 id 与视角。
    路由参数切换时父级会把 user 置 null,本组件随卡片 v-else 卸载重挂,
    onMounted 即重新加载,无需 watch targetId。
  -->
  <section v-if="tags.length" class="ptags">
    <div class="ptags__head">
      <span class="ptags__title">{{ isMe ? '我的兴趣' : 'TA 的兴趣' }}</span>
      <button v-if="isMe" type="button" class="ptags__more" @click="router.push('/discover')">
        发现同好
      </button>
    </div>
    <div class="ptags__cloud">
      <span v-for="t in tags" :key="t.id" class="ptags__tag">{{ t.name }}</span>
    </div>
  </section>
  <p v-else-if="failed" class="ptags__err">
    兴趣没拉出来<button type="button" class="ptags__retry" @click="load">重试</button>
  </p>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ofUser } from '@/api/tag'

const props = defineProps({
  targetId: { type: Number, required: true },
  isMe: { type: Boolean, default: false },
})

const router = useRouter()
const tags = ref([])
const failed = ref(false)

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
/* 兴趣标签:紧挨身份头,属于「你是谁」而非统计或内容(计划 §6 场景1) */
.ptags {
  margin: var(--ys-space-2) 0 0;
}
.ptags__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-bottom: var(--ys-space-2);
}
.ptags__title {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
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
.ptags__cloud {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}
.ptags__tag {
  display: inline-flex;
  align-items: center;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
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
