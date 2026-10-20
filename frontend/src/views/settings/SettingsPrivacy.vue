<template>
  <div class="sub-inner">
    <!-- 陌生人消息开关(从设置首页搬来,逻辑不变:即时保存,失败回滚) -->
    <el-card class="sgroup">
      <div class="srow">
        <span class="sicon"><el-icon :size="18"><ChatDotRound /></el-icon></span>
        <span class="stext">
          <span class="slabel">允许陌生人给我发消息</span>
          <span class="sdesc">
            <template v-if="canStrangerChat">
              关闭后,未关注你的人无法主动给你发私信(你仍可主动找别人)。切换即时生效,无需点保存。
            </template>
            <template v-else>
              先完成校内认证,才能接收陌生人消息。认证在「编辑资料」的学号处提交,审核通过即生效。
            </template>
          </span>
        </span>
        <el-switch
          v-model="strangerMsg"
          :loading="strangerSaving"
          :disabled="!canStrangerChat || strangerSaving"
          @change="onStrangerMsgChange"
        />
      </div>
    </el-card>

    <!--
      黑名单管理。数据来自 GET /relation/blacklist(只列「我拉黑的人」),
      解除走 DELETE /relation/blacklist?uid=(2026-10-20 接真接口,替换原空壳 TODO)。
    -->
    <el-card class="sgroup">
      <div class="srow srow--static">
        <span class="sicon"><el-icon :size="18"><UserFilled /></el-icon></span>
        <span class="stext">
          <span class="slabel">黑名单管理</span>
          <span class="sdesc">拉黑后互相看不到对方的动态与私信</span>
        </span>
      </div>
      <p v-if="!blacklist.length" class="bempty">还没有拉黑任何人</p>
      <div v-for="u in blacklist" :key="u.id" class="srow">
        <span class="bava">{{ String(u.nickname || '友').slice(0, 1) }}</span>
        <span class="stext">
          <span class="slabel">{{ u.nickname }}</span>
        </span>
        <button type="button" class="bbtn" :disabled="unblockingId === u.id" @click="unblockUser(u)">
          {{ unblockingId === u.id ? '解除中…' : '解除拉黑' }}
        </button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 隐私设置(/settings/privacy)。
 * - 陌生人消息开关:与旧设置页同一套即时保存逻辑(api/auth.setStrangerMsg)
 * - 黑名单:GET/DELETE /relation/blacklist(2026-10-20 接真接口)。
 *   解除成功后本地摘除该行,失败时请求层已弹错、列表保持原样。
 */
import { ref, onMounted } from 'vue'
import { ChatDotRound, UserFilled } from '@element-plus/icons-vue'
import { ElMessage } from '@/utils/feedback'
import { me as meApi, setStrangerMsg } from '@/api/auth'
import { blacklistList, unblacklist } from '@/api/relation'
import { useVerifyStatus } from '@/composables/useVerifyStatus'

/* ---- 陌生人消息开关(与旧设置页同逻辑) ---- */
const strangerMsg = ref(true)
const strangerSaving = ref(false)
const { canStrangerChat, load: loadVerifyStatus } = useVerifyStatus()

async function onStrangerMsgChange(val) {
  strangerSaving.value = true
  try {
    await setStrangerMsg(val)
    ElMessage.success(val ? '已允许陌生人发消息' : '已关闭,陌生人无法主动找你')
  } catch {
    strangerMsg.value = !val
  } finally {
    strangerSaving.value = false
  }
}

/* ---- 黑名单(真接口:GET/DELETE /relation/blacklist) ---- */
const blacklist = ref([])
/** 正在解除的行 id:按钮转文案,防重复点 */
const unblockingId = ref(null)

async function loadBlacklist() {
  try {
    const data = await blacklistList(null, 50)
    blacklist.value = data?.list || []
  } catch {
    blacklist.value = []
  }
}

async function unblockUser(u) {
  unblockingId.value = u.id
  try {
    await unblacklist(u.id)
    blacklist.value = blacklist.value.filter((x) => x.id !== u.id)
    ElMessage.success(`已解除对 ${u.nickname} 的拉黑`)
  } finally {
    unblockingId.value = null
  }
}

onMounted(() => {
  loadBlacklist()
  // 认证能力位:canStrangerChat 决定开关可不可用(未认证置灰)
  loadVerifyStatus()
  // 开关初值与旧设置页同源:/user/me 的 strangerMsgEnabled
  meApi()
    .then((me) => {
      strangerMsg.value = me.strangerMsgEnabled !== false
    })
    .catch(() => {})
})
</script>

<style scoped src="./SettingsSub.css"></style>
