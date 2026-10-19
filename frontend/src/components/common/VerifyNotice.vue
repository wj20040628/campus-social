<template>
  <!--
    认证状态提示条(VerifyNotice.vue)

    [为什么它长这样,而不是一行 toast]
    被驳回是**需要用户做决定**的状态:他必须去改学号再提交一次。
    toast 5 秒就消失,而「为什么被驳回」这件事他多半要回头再看 ——
    要求用户记住一条一闪而过的信息,是产品设计里最常见的劝退点。
    所以做成常驻条:进个人主页就在,进首页也在。

    [两种形态]
      驳回:警示色 + 「下一步做什么」+ 出口按钮
      审核中:中性色 + 说明在等谁 + 出口按钮
    审核中**不用警示色**:它是可预期的正常态,不是出错了。
    用警告色会让用户以为被驳回了。

    [三态与本组件的对应]
      verifyStatus=3 且 rejectReason 非空 -> 驳回条(本组件的主要场景)
      verifyStatus=3 但 rejectReason 为空 -> **不渲染**(见 useVerifyStatus.showRejected)
      verifyStatus=2                   -> 审核中条
      verifyStatus=0/1                -> 不渲染
  -->
  <aside
    v-if="showRejected || showPending || showWeakVerified"
    class="vnotice"
    :class="showRejected ? 'vnotice--reject' : showPending ? 'vnotice--pending' : 'vnotice--weak'"
    role="status"
  >
    <!--
      图标只是辅助。状态由标题文字承载 —— 只靠图标传达信息,
      读屏用户与色觉障碍用户会完全漏掉(设计 §5:语义必须三重传达)。
    -->
    <el-icon class="vnotice__icon" :size="20" aria-hidden="true">
      <component :is="showRejected ? WarningFilled : showPending ? Clock : CircleCheck" />
    </el-icon>

    <div class="vnotice__body">
      <p class="vnotice__title">{{ title }}</p>

      <!--
        驳回理由原样透出。后端在 audit_log.detail 里存的就是管理员写的那句,
        AdminVerifyServiceImpl 特意不拼前缀(「驳回理由: xxx」),
        避免用户看到二次包装的文案 —— 这里也就不加任何前缀。
      -->
      <p v-if="showRejected && rejectReason" class="vnotice__reason">{{ rejectReason }}</p>

      <!--
        下一步。**必须写「换一个学号」**,这是契约约束决定的:
        uk_student_no 是库级唯一索引,驳回不释放学号,
        用户拿回原来那个号重提必然 409 STUDENT_NO_EXISTS。
        只写「请重新提交」的话,用户会原样再填一次然后撞上 409,
        第二次就走了 —— 实测同学号重提 409、换号重提 200。
      -->
      <p v-if="showRejected" class="vnotice__how">
        确认一下你的学号有没有填错,改完后需要一个<strong>不同的学号</strong>重新提交
        (原学号已被占用,继续用会失败)
      </p>
      <p v-else-if="showPending" class="vnotice__how">
        管理员会尽快人工核验。有结果会在这里更新,不用反复刷新
      </p>
      <p v-else class="vnotice__how">
        陌生人能直接给你发消息、你能发官方公告;发动态不需要认证。
        这是自动通过的弱认证,权限弱于人工审核通过。
      </p>
    </div>

    <button v-if="!showWeakVerified" type="button" class="vnotice__btn" @click="goSubmit">
      {{ showRejected ? '去改学号' : '查看进度' }}
    </button>
  </aside>
</template>

<script setup>
/**
 * 认证状态提示条。
 *
 * 数据与状态判定全在 useVerifyStatus(模块级单例,多页面共用一份),
 * 本组件只负责「长什么样」和点了去哪。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Clock, WarningFilled, CircleCheck } from '@element-plus/icons-vue'
import { useVerifyStatus, VERIFY_OK } from '@/composables/useVerifyStatus'

const router = useRouter()
const { showRejected, showPending, rejectReason, load, code, autoApproved } = useVerifyStatus()

/**
 * 标题不写「认证被驳回」这种系统词。
 * 用户填的是学号,不是「认证」;说「学号没通过」他能立刻对上自己填的那串数字。
 */
const title = computed(() =>
  showRejected.value
    ? '你的学号没通过核验'
    : showPending.value
      ? '学号正在核验中'
      : '你已通过校内认证(自动通过)',
)

/**
 * §5 A4:自动通过(弱认证)与人工通过要区分开。
 * autoApproved=true 且状态为已认证(1)时,渲染一条中性提示,
 * 让用户知道自己是「弱认证」——能收陌生人消息、能发公告,但权限弱于人工审核。
 */
const showWeakVerified = computed(() => code.value === VERIFY_OK && autoApproved.value)

/**
 * 出口。
 *
 * 去编辑资料页 /profile 而不是 Onboarding:Onboarding 是新用户引导(第 1 步选院系年级),
 * 已完成过的用户再进去会被路由到已完成的态,看不到「改学号」这个动作;
 * 编辑资料页有独立的「学号」输入框,改完点保存就走 PUT /user/me。
 *
 * 离开前 force 重拉:用户提交完新学号回来,这条提示必须消失 ——
 * 不重拉的话它会一直挂在页面上,用户以为没生效。
 */
async function goSubmit() {
  router.push('/profile')
  await load(true)
}
</script>

<style scoped>
.vnotice {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-3);
  margin: 0 0 var(--ys-space-4);
  padding: var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
  background: var(--ys-surface);
}

.vnotice__icon {
  flex-shrink: 0;
  margin-top: 2px;
}

/* 驳回用 danger 底色:这是需要用户处理的状态 */
.vnotice--reject {
  border-color: var(--ys-danger);
  background: var(--ys-danger-soft);
}

.vnotice--reject .vnotice__icon {
  color: var(--ys-danger);
}

/*
  审核中刻意用中性色而不是 warning:
  它是正常流程中的一次等待,不是错误。挂成警告色,用户会以为「出问题了」,
  然后去问管理员「是不是坏了」—— 实测里最常见的咨询就是这个。
*/
.vnotice--pending {
  border-color: var(--ys-border);
  background: var(--ys-surface-2);
}

.vnotice--pending .vnotice__icon {
  color: var(--ys-meta);
}

/*
  弱认证(自动通过)提示:中性偏正向,不告警不报危 ——
  它是「通过了,但权限弱」,不是错误或等待。用 surface-2 浅底 + success 绿图标,
  与驳回(danger)/审核中(中性灰)拉开三档。
*/
.vnotice--weak {
  border-color: var(--ys-border);
  background: var(--ys-surface-2);
}

.vnotice--weak .vnotice__icon {
  color: var(--ys-success);
}

.vnotice__body {
  flex: 1;
  min-width: 0;
}

.vnotice__title {
  margin: 0;
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-base);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.vnotice__reason {
  margin: var(--ys-space-2) 0 0;
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-md);
  background: var(--ys-surface);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-fg);
  /* 管理员写的长理由不能撑破卡片 */
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.vnotice__how {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-fg-2);
  overflow-wrap: anywhere;
}

.vnotice__how strong {
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.vnotice__btn {
  flex-shrink: 0;
  align-self: center;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border: 1px solid var(--ys-fg);
  border-radius: var(--ys-radius-md);
  background: var(--ys-fg);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-bg);
  cursor: pointer;
  transition: opacity var(--ys-motion-instant) var(--ys-ease-out);
}

.vnotice__btn:active {
  opacity: 0.7;
}

/*
  375px 下三行(标题/理由/下一步)加一个按钮已经占满一屏宽度,
  按钮换到下一行 —— 拉伸到与文案同宽反而像一个巨大的空按钮。

  **只让按钮换行,不让图标与文案分开。**
  早先给 .vnotice__body 也写了 `flex: 1 1 100%`,结果 body 占满整行、
  把图标挤成孤零零的一行(截图实测:图标独占一行,标题在下一行)。
  图标必须与标题同排 —— 它是给标题做视觉锚点的,单独一行既费高度又失去意义。

  断点取 sm(640px),不取 480px:断点唯一源是 design-tokens.json 的
  640/768/1024/1280/1536 五档(SPEC §10.4),480 是我自己编的,
  不在任何一个客户端的断点集合里,改设计稿时没人会想到它。
*/
@media (max-width: 640px) {
  .vnotice {
    flex-wrap: wrap;
  }

  .vnotice__btn {
    flex: 1 1 100%;
  }
}
</style>