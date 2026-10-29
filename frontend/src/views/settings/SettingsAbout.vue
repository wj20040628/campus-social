<template>
  <div class="sub-inner">
    <!-- 品牌块:logo + 名称 + 版本 + slogan -->
    <el-card class="sgroup">
      <div class="brand">
        <span class="brand__logo">友</span>
        <span class="brand__name">友声</span>
        <span class="brand__ver">校园生活社区 · v{{ APP_VERSION }}</span>
      </div>
      <p class="slogan">嘤其鸣矣,求其友声<br>——《诗经 · 小雅 · 伐木》</p>
    </el-card>

    <!--
      联系我们（2026-10-28）：优先 QQ 群。邮箱还在，但只当兜底——
      学生不会为一个反馈去开邮箱，群是这一类产品真正常用的出口。
      群号没填时这一行不渲染，不留「筹备中」的空壳。
    -->
    <el-card class="sgroup">
      <button v-if="CONTACT.qqGroup" type="button" class="srow" @click="groupOpen = true">
        <span class="sicon"><el-icon :size="18"><ChatDotRound /></el-icon></span>
        <span class="stext">
          <span class="slabel">加入 QQ 群</span>
          <span class="sdesc">群号 {{ CONTACT.qqGroup }}（扫码或点开复制）</span>
        </span>
        <el-icon class="schev"><ArrowRight /></el-icon>
      </button>
      <button type="button" class="srow" @click="copyEmail">
        <span class="sicon"><el-icon :size="18"><Promotion /></el-icon></span>
        <span class="stext">
          <span class="slabel">{{ CONTACT.qqGroup ? '邮件反馈' : '联系我们' }}</span>
          <span class="sdesc">{{ CONTACT.email }}(点击复制)</span>
        </span>
        <el-icon class="schev"><ArrowRight /></el-icon>
      </button>
    </el-card>

    <!--
      QQ 群弹层（2026-10-28）：群二维码 + 群号一键复制。
      二维码用 QQ 群资料页导出的图（src/assets/qq-group.jpg），
      在微信里扫码会直接跳 QQ，不用用户手打群号。
    -->
    <ActionSheet v-model="groupOpen" title="加入友声交流群">
      <div class="qq">
        <img class="qq__pic" :src="QQ_GROUP_QR" alt="友声交流群二维码" />
        <p class="qq__no">
          群号 <b class="ys-num">{{ CONTACT.qqGroup }}</b>
        </p>
        <button type="button" class="qq__copy" @click="copyGroup">
          {{ copied ? '已复制群号' : '复制群号' }}
        </button>
        <p class="qq__tip">截图后用 QQ 扫一扫，或在 QQ 里搜索群号</p>
      </div>
    </ActionSheet>

    <!--
      更新日志:数据在 src/constants/changelog.js,版本号读 package.json。
      发版只改「package.json 的 version + changelog 最前面加一条」,这里零改动。
    -->
    <el-card class="sgroup">
      <div class="log">
        <p class="log__caption">更新日志</p>
        <!--
          每个版本可独立折叠（2026-10-28）：版本一多整页要滑很久，
          默认只展开最新一版，其余收成一行标题按需点开。
        -->
        <div v-for="rel in CHANGELOG" :key="rel.version" class="log__rel">
          <button
            type="button"
            class="log__meta log__meta--btn"
            :aria-expanded="isOpen(rel.version)"
            @click="toggle(rel.version)"
          >
            <span class="log__ver">v{{ rel.version }}</span>
            <span class="log__date ys-num">{{ rel.date }}</span>
            <el-icon class="log__caret" :class="{ 'is-open': isOpen(rel.version) }">
              <ArrowRight />
            </el-icon>
          </button>
          <div v-show="isOpen(rel.version)">
            <ul v-if="rel.added?.length" class="log__list">
              <li v-for="t in rel.added" :key="'a-' + t">
                <span class="log__tag log__tag--add">新增</span><span>{{ t }}</span>
              </li>
            </ul>
            <ul v-if="rel.fixed?.length" class="log__list">
              <li v-for="t in rel.fixed" :key="'f-' + t">
                <span class="log__tag log__tag--fix">修复</span><span>{{ t }}</span>
              </li>
            </ul>
            <ul v-if="rel.changed?.length" class="log__list">
              <li v-for="t in rel.changed" :key="'c-' + t">
                <span class="log__tag log__tag--opt">优化</span><span>{{ t }}</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 关于我们(/settings/about)。
 * 纯静态信息页。版本号读 package.json,更新日志读 constants/changelog.js,
 * 两处都不在页面里手写 —— 发版只改数据,不动模板。
 */
import { ref } from 'vue'
import { version as APP_VERSION } from '../../../package.json'
import { ArrowRight, ChatDotRound, Promotion } from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import qqGroupQr from '@/assets/qq-group.jpg'
import { CHANGELOG } from '@/constants/changelog'
import { CONTACT } from '@/constants/contact'
import { copyText } from '@/utils/clipboard'
import { ElMessage } from '@/utils/feedback'

const QQ_GROUP_QR = qqGroupQr
const groupOpen = ref(false)
/** 复制成功态：按钮文案变「已复制群号」，比 toast 更贴近手指位置 */
const copied = ref(false)

/** 展开状态：默认只开最新一版（CHANGELOG 第一条），其余收起 */
const openMap = ref({ [CHANGELOG[0]?.version || '']: true })
function isOpen(v) {
  return openMap.value[v] === true
}
function toggle(v) {
  openMap.value = { ...openMap.value, [v]: !isOpen(v) }
}

async function copyEmail() {
  const ok = await copyText(CONTACT.email)
  if (ok) ElMessage.success('邮箱已复制')
  // 复制不了(极旧浏览器)也不能把邮箱藏起来：直接念出来，用户还能手抄
  else ElMessage.info(`联系邮箱：${CONTACT.email}`)
}

async function copyGroup() {
  const ok = await copyText(CONTACT.qqGroup)
  if (!ok) {
    ElMessage.info(`QQ 群：${CONTACT.qqGroup}`)
    return
  }
  copied.value = true
  ElMessage.success('群号已复制，去 QQ 搜索加群')
  setTimeout(() => { copied.value = false }, 2000)
}
</script>

<style scoped src="./SettingsSub.css"></style>

<style scoped>
/* QQ 群弹层：二维码尽量占满宽度但留安全边距，二维码在任何缩放下都要清晰 */
.qq {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-4) var(--ys-space-4);
}

.qq__pic {
  width: 100%;
  max-width: 300px;
  border-radius: var(--ys-radius-lg);
  border: 1px solid var(--ys-border-soft);
  background: var(--ys-surface);
}

.qq__no {
  margin: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
}

.qq__no b {
  font-size: var(--ys-text-lg);
  letter-spacing: 0.04em;
  color: var(--ys-fg);
}

.qq__copy {
  width: 100%;
  max-width: 300px;
  min-height: var(--ys-touch-min);
  border: 0;
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-md);
  font-weight: var(--ys-weight-semibold);
  cursor: pointer;
}

.qq__tip {
  margin: 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  text-align: center;
}
</style>
