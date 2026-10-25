<template>
  <div class="sn">
    <!-- 通知设置独立页:把「可免打扰」的通知类型按组开关。类型 id 与后端 NotifyServiceImpl 对齐 -->
    <header class="sn__bar">
      <button type="button" class="sn__bar-btn" aria-label="返回" @click="router.back()">
        <el-icon :size="22"><ArrowLeft /></el-icon>
      </button>
      <h1 class="sn__bar-title">通知设置</h1>
    </header>

    <div class="sn-inner">
      <p class="sn-tip">关闭某类通知后,该类型不再提醒;已收到的历史通知不受影响</p>
      <el-card v-for="g in groups" :key="g.key" class="sn-group">
        <div class="sn-row">
          <span class="sn-text">
            <span class="sn-label">{{ g.label }}</span>
            <span class="sn-desc">{{ g.desc }}</span>
          </span>
          <el-switch
            :model-value="isOn(g)"
            aria-label="通知开关"
            style="--el-switch-on-color: var(--ys-accent)"
            @change="(v) => toggle(g, v)"
          />
        </div>
      </el-card>

      <!-- 邮件摘要(2026-10-25):回访触达。默认关,用户 opt-in -->
      <h2 class="sn-sec">邮件摘要</h2>
      <p class="sn-tip">每天最多一封(傍晚),昨天有新动态才发;没动静不打扰</p>
      <el-card class="sn-group">
        <div class="sn-row">
          <span class="sn-text">
            <span class="sn-label">每日摘要邮件</span>
            <span class="sn-desc">把昨天的新互动汇总成一封邮件发给你</span>
          </span>
          <el-switch
            v-model="digest.enabled"
            aria-label="邮件摘要开关"
            style="--el-switch-on-color: var(--ys-accent)"
            @change="saveDigest"
          />
        </div>
      </el-card>
      <el-card v-if="digest.enabled" class="sn-group sn-group--types">
        <p class="sn-types-label">想收哪些动态</p>
        <el-checkbox-group v-model="digest.types" class="sn-types" @change="saveDigest">
          <el-checkbox v-for="t in digestTypes" :key="t.key" :value="t.key">
            {{ t.label }} <span class="sn-types-desc">{{ t.desc }}</span>
          </el-checkbox>
        </el-checkbox-group>
      </el-card>
    </div>
  </div>
</template>

<script setup>
/**
 * 通知偏好 / 免打扰(2026-10-20)。
 * 后端存 user.notify_pref = {"disabled":[类型id...]};空/NULL=全开。
 * 这里只暴露「社交动态类」六个可关闭分组(审核/私信/反馈回复属事务型,不给关),
 * 每个分组映射到一到两个类型 id;disabled 集合是所有「关掉的组」的并集。
 */
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { ArrowLeft } from '@element-plus/icons-vue'
import { getNotifyPref, setNotifyPref, getEmailDigest, setEmailDigest } from '@/api/auth'

const router = useRouter()

const groups = [
  { key: 'like', label: '点赞通知', desc: '有人赞了你的内容', types: [2] },
  { key: 'comment', label: '评论通知', desc: '有人评论了你的内容', types: [3] },
  { key: 'follow', label: '关注通知', desc: '有人关注了你', types: [1] },
  { key: 'mention', label: '@提及通知', desc: '有人在评论中@了你', types: [9] },
  { key: 'activity', label: '活动通知', desc: '活动报名 / 活动取消', types: [5, 12] },
  { key: 'mkt', label: '集市通知', desc: '集市活动下的留言', types: [10] },
]

/** 被关闭的类型 id 集合;空=全开 */
const disabled = ref(new Set())
const saving = ref(false)

/** 组内任一类型被关 = 整组关;全部开 = 开 */
function isOn(g) {
  return !g.types.some((t) => disabled.value.has(t))
}

async function toggle(g, on) {
  const next = new Set(disabled.value)
  if (on) g.types.forEach((t) => next.delete(t))
  else g.types.forEach((t) => next.add(t))
  disabled.value = next
  await save()
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    const pref = disabled.value.size ? JSON.stringify({ disabled: [...disabled.value] }) : ''
    await setNotifyPref(pref)
  } catch {
    ElMessage.error('保存失败,请重试')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    const raw = await getNotifyPref()
    if (raw) {
      const arr = JSON.parse(raw).disabled
      if (Array.isArray(arr)) disabled.value = new Set(arr.map(Number))
    }
  } catch {
    /* 偏好解析失败不阻塞页面,默认全开 */
  }
  try {
    const d = await getEmailDigest()
    digest.enabled = d?.enabled === true
    digest.types = Array.isArray(d?.types) ? d.types : []
  } catch {
    /* 邮件摘要拉取失败不阻塞站内通知设置 */
  }
})

/** 邮件摘要订阅(后端合并写,不会碰上面的站内通知开关) */
const digest = reactive({ enabled: false, types: [] })
const digestTypes = [
  { key: 'chat', label: '搭话与私信', desc: '新同学找你搭话' },
  { key: 'comment', label: '评论与@你', desc: '评论、回复、集市留言' },
  { key: 'like', label: '被赞', desc: '有人赞了你的内容' },
  { key: 'follow', label: '新粉丝', desc: '有人关注了你' },
  { key: 'system', label: '活动与审核', desc: '报名变更、审核结果' },
]
let digestSaving = false

async function saveDigest() {
  if (digestSaving) return
  digestSaving = true
  try {
    await setEmailDigest({ enabled: digest.enabled, types: digest.types })
  } catch {
    ElMessage.error('保存失败,请重试')
  } finally {
    digestSaving = false
  }
}
</script>

<style scoped>
.sn {
  min-height: 100vh;
  background: var(--ys-bg);
}
.sn__bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: 0 12px;
  background: var(--ys-surface);
  border-bottom: 1px solid var(--ys-border-soft);
}
.sn__bar-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  border: 0;
  background: transparent;
  color: var(--ys-text);
  cursor: pointer;
}
.sn__bar-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--ys-text);
}
.sn-inner {
  max-width: 680px;
  margin: 0 auto;
  padding: 16px 12px 32px;
}
.sn-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--ys-meta);
}
.sn-group {
  border-radius: var(--ys-radius-xl);
  margin-bottom: 12px;
}
/* 邮件摘要分区标题(2026-10-25) */
.sn-sec {
  margin: 24px 0 8px;
  font-size: 15px;
  color: var(--ys-text);
}
.sn-group--types {
  padding: 4px 16px;
}
.sn-types-label {
  margin: 8px 0 4px;
  font-size: 13px;
  color: var(--ys-meta);
}
.sn-types {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 10px;
}
.sn-types-desc {
  font-size: 12px;
  color: var(--ys-meta);
}
.sn-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.sn-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sn-label {
  font-size: 15px;
  color: var(--ys-text);
}
.sn-desc {
  font-size: 12px;
  color: var(--ys-meta);
}
</style>
