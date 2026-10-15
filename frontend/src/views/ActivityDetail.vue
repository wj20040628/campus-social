<template>
  <div class="adetail">
    <div class="adetail-inner">
      <el-page-header @back="router.back()" :title="'活动详情'" />

      <el-card v-if="act">
        <div class="a-title">{{ act.title }}</div>
        <div class="a-meta">
          <el-tag :type="actStatusTag(act.status)">{{ actStatusText(act.status) }}</el-tag>
          <span>名额 {{ act.capacity === 0 ? '不限' : act.currentCount + ' / ' + act.capacity }}</span>
        </div>
        <div class="a-time">
          <el-icon><Calendar /></el-icon>
          <div class="a-time-box">
            <div>
              <span class="a-time-k">开始</span>
              {{ act.startTime ? dayjs(act.startTime).format('YYYY-MM-DD HH:mm') : '-' }}
            </div>
            <div>
              <span class="a-time-k">结束</span>
              {{ act.endTime ? dayjs(act.endTime).format('YYYY-MM-DD HH:mm') : '-' }}
            </div>
          </div>
        </div>

        <el-divider />

        <div class="a-actions">
          <template v-if="act.signedUp">
            <el-tag type="success">已报名</el-tag>
            <el-button v-if="canCancel" type="danger" plain @click="onCancel">取消报名</el-button>
          </template>
          <template v-else>
            <el-button
              type="primary"
              :disabled="!act.canSignup"
              :loading="signing"
              @click="onSignup"
            >
              立即报名
            </el-button>
            <el-text v-if="!act.canSignup" type="info">{{ signupBlockedReason(act) }}</el-text>
          </template>
        </div>
      </el-card>
      <StateBlock v-else variant="error" title="这个活动不存在" description="可能已经结束或被发起人撤掉了" action-label="回圈子广场" :icon="WarningFilled" @action="router.push('/circles')" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { Calendar, WarningFilled } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import {
  activityDetail,
  signup as signupApi,
  cancelSignup,
} from '@/api/circle'

const route = useRoute()
const router = useRouter()
const act = ref(null)
const signing = ref(false)

const canCancel = computed(() => {
  if (!act.value) return false
  // 报名中/已满 可取消;进行中/已结束不可
  return [0, 1].includes(act.value.status)
})

function actStatusText(s) {
  return ['报名中', '名额已满', '进行中', '已结束', '已取消'][s] || '未知'
}
function actStatusTag(s) {
  return ['primary', 'warning', 'success', 'info', 'danger'][s] || 'info'
}

/** 不可报名时的原因,按 status 细分,不笼统一句「不可报名」 */
function signupBlockedReason(a) {
  switch (Number(a?.status)) {
    case 1:
      return '名额已报满,关注下一场'
    case 2:
      return '活动已开打,报名通道关闭'
    case 3:
      return '活动已结束'
    case 4:
      return '活动已被取消'
    default:
      return '该活动当前不可报名'
  }
}

async function load() {
  act.value = await activityDetail(route.params.id)
}

async function onSignup() {
  signing.value = true
  try {
    await signupApi(route.params.id)
    ElMessage.success('报名成功')
    await load()
  } catch {
    // request.js 已弹错
  } finally {
    signing.value = false
  }
}

async function onCancel() {
  try {
    await ElMessageBox.confirm('确定取消报名?', '提示', { type: 'warning' })
  } catch {
    return
  }
  await cancelSignup(route.params.id)
  ElMessage.success('已取消报名')
  await load()
}

onMounted(load)
</script>

<style scoped>
/*
 * 变量纪律(2026-10-15):原写法混用 --el-* 与 --card-radius。
 * --el-* 虽经 Bridge 最终指向 --ys-*,但业务代码直接写违反
 * design-tokens.css 头部硬规则 2;--card-radius 是 tokens.css 的
 * 兼容垫片,新代码一律直接写 --ys-*(Profile.css 有过因此踩直角的历史)。
 */
.adetail-inner {
  max-width: 560px;
  margin: 0 auto;
  padding: 20px;
}
.adetail-inner .el-card {
  border-radius: var(--ys-radius-xl);
}
.a-title { font-size: 22px; font-weight: 700; }
.a-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}
.a-time {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--ys-surface-2);
  border-radius: var(--ys-radius-lg);
  padding: 12px 16px;
  color: var(--ys-fg);
}
.a-time .el-icon {
  font-size: 22px;
  color: var(--ys-accent);
}
.a-time-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 14px;
}
.a-time-k {
  display: inline-block;
  width: 32px;
  margin-right: 8px;
  font-size: 12px;
  color: var(--ys-meta);
}
.a-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}
/* A 方向:主动作按钮统一胶囊(与全站 CTA 同形) */
.a-actions :deep(.el-button) {
  border-radius: var(--ys-radius-full);
}
</style>
