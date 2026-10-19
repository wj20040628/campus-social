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
      黑名单管理。**后端接口待做**(GET /user/blacklist + DELETE /user/blacklist/{id}),
      先给真实空态而不是假列表 —— 接口好了把 loadBlacklist 里的 TODO 换成真调用即可,
      页面结构(图标行 + 列表 + 解除按钮)已在样式层备好。
    -->
    <el-card class="sgroup">
      <div class="srow srow--static">
        <span class="sicon"><el-icon :size="18"><UserFilled /></el-icon></span>
        <span class="stext">
          <span class="slabel">黑名单管理</span>
          <span class="sdesc">拉黑后互相看不到对方的动态与私信</span>
        </span>
      </div>
      <p class="bempty">{{ blacklist.length ? '' : '还没有拉黑任何人' }}</p>
      <div v-for="u in blacklist" :key="u.id" class="srow">
        <span class="bava">{{ String(u.nickname || '友').slice(0, 1) }}</span>
        <span class="stext">
          <span class="slabel">{{ u.nickname }}</span>
        </span>
        <button type="button" class="bbtn" @click="unblockUser(u)">解除拉黑</button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 隐私设置(/settings/privacy)。
 * - 陌生人消息开关:与旧设置页同一套即时保存逻辑(api/auth.setStrangerMsg)
 * - 黑名单:**后端接口待做**,当前渲染真实空态;结构已备好,接口到位后
 *   只需补 loadBlacklist/unblockUser 的调用(见下方 TODO)
 */
import { ref, onMounted } from 'vue'
import { ChatDotRound, UserFilled } from '@element-plus/icons-vue'
import { ElMessage } from '@/utils/feedback'
import { me as meApi, setStrangerMsg } from '@/api/auth'
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

/* ---- 黑名单(待后端:GET /user/blacklist / DELETE /user/blacklist/{id}) ---- */
const blacklist = ref([])

async function loadBlacklist() {
  // TODO(后端接口就绪后): const data = await blacklistApi(); blacklist.value = data?.list || []
  blacklist.value = []
}

async function unblockUser(u) {
  // TODO(后端接口就绪后): await unblockApi(u.id); blacklist.value = blacklist.value.filter(x => x.id !== u.id)
  ElMessage.info(`已准备解除 ${u.nickname} 的拉黑(等待后端接口)`)
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
