<template>
  <div class="sp">
    <!--
      「我的」页的编辑资料独立页(把原 Settings.vue 里的资料表单抽出来)。
      2026-10-18 按改版预览稿重排版:头像区独立成块 + 四个分组卡
      (基本信息 / 学校信息 / 兴趣标签 / 安全邮箱),底部一个整宽保存按钮,
      不再用 el-card + label-position=top 的后台表单样式。
    -->
    <header class="sp__bar">
      <button type="button" class="sp__bar-btn" aria-label="返回" @click="router.back()">
        <el-icon :size="22"><ArrowLeft /></el-icon>
      </button>
      <h1 class="sp__bar-title">编辑资料</h1>
    </header>

    <div class="sp-inner">
      <div class="sp-form">
        <!-- 头像区:整块可点,点哪换哪 -->
        <el-upload
          class="sp-ava"
          :show-file-list="false"
          :http-request="uploadAvatar"
          accept="image/*"
        >
          <span class="sp-ava__ring">
            <el-avatar :size="76" :src="resolveThumb(form.avatarUrl)" />
          </span>
          <span class="sp-ava__tip">点击更换头像</span>
        </el-upload>

        <!-- 基本信息:昵称 / 学号,写同一个 PUT /user/me -->
        <section class="sp-group">
          <h2 class="sp-group__label">基本信息</h2>
          <div class="sp-card">
            <div class="sp-row">
              <span class="sp-row__label">昵称</span>
              <el-input v-model="form.nickname" placeholder="别人看到的名字" maxlength="32" />
            </div>
            <div class="sp-row">
              <span class="sp-row__label">学号</span>
              <el-input v-model="form.studentNo" placeholder="如 2024001234" maxlength="32" />
            </div>
          </div>
        </section>

        <!--
          学校信息:五项(学校 / 学院 / 本专科 / 专业 / 年级)走 POST /user/school-profile,
          与上面的 /user/me **不是同一个端点**,所以底部保存按钮会分两次提交,
          顺序为先学校后资料;不合成一次请求是为了避免「昵称存上了学院没存上」的半成功态。
        -->
        <section class="sp-group">
          <h2 class="sp-group__label">学校信息</h2>
          <div class="sp-card sp-card--school">
            <SchoolFields v-model="identity" :invalid="identityInvalid" />
            <p v-if="schoolSaving" class="sp-note">正在保存学校归属…</p>
            <p v-else-if="identitySaved" class="sp-note sp-note--ok">学校归属已保存</p>
            <p v-else-if="identityDirty" class="sp-note sp-note--warn">学校归属有改动,保存后生效</p>
          </div>
          <p v-if="verifyTip" class="sp-verify" :class="`sp-verify--${verifyTip.tone}`">
            <el-icon :size="15"><CircleCheckFilled v-if="verifyTip.ok" /><WarningFilled v-else /></el-icon>
            <span>{{ verifyTip.text }}</span>
          </p>
        </section>

        <!-- 兴趣标签:当前选中直接铺成 chip,最后一个是「+ 添加」入口 -->
        <section class="sp-group">
          <h2 class="sp-group__label">兴趣标签</h2>
          <div class="sp-card sp-card--pad">
            <div class="sp-chips">
              <span v-for="t in myTags" :key="t.id" class="sp-chip">{{ t.name }}</span>
              <button type="button" class="sp-chip sp-chip--add" @click="openTagEditor">
                <el-icon :size="13"><Plus /></el-icon>
                <span>{{ myTags.length ? '管理' : '添加' }}</span>
              </button>
            </div>
          </div>
        </section>

        <!-- 安全凭证邮箱:学号是主账号,邮箱只用于找回密码,不公开展示 -->
        <section class="sp-group">
          <h2 class="sp-group__label">安全邮箱</h2>
          <div class="sp-card">
            <button type="button" class="sp-link" @click="emailOpen = true">
              <span class="sp-link__ic">
                <el-icon :size="17"><Message /></el-icon>
              </span>
              <span class="sp-link__text">
                <span class="sp-link__label">{{ emailBound ? emailMasked : '尚未绑定邮箱' }}</span>
                <span class="sp-link__desc">用于找回密码,点击可{{ emailBound ? '修改' : '绑定' }}</span>
              </span>
              <el-icon class="sp-link__chev" :size="16"><ArrowRight /></el-icon>
            </button>
          </div>
        </section>

        <div class="sp-savebar">
          <button type="button" class="sp-save" :disabled="saving || schoolSaving" @click="saveAll">
            {{ saving || schoolSaving ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <ProfileTagDialog ref="tagDialog" @saved="loadTags" />
    <ProfileEmailDialog v-model="emailOpen" :bound="emailBound" @saved="onEmailSaved" />
  </div>
</template>

<script setup>
/**
 * 编辑资料独立页。保存拆两段提交(见模板「学校信息」注释):
 * 学校归属 = POST /user/school-profile,昵称/学号/头像 = PUT /user/me。
 */
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { ArrowLeft, ArrowRight, Plus, Message, CircleCheckFilled, WarningFilled } from '@element-plus/icons-vue'
import { me as meApi } from '@/api/auth'
import { uploadToOss } from '@/composables/useOssUpload'
import { saveSchoolProfile, readIdentity } from '@/api/dict'
import { useVerifyStatus } from '@/composables/useVerifyStatus'
import { useVerifyTip } from '@/composables/useVerifyTip'
import request from '@/utils/request'
import { resolveThumb } from '@/utils/resolve'
import { IDENTITY_MODEL, SCHOOLS } from '@/constants/schoolDict'
import { dictWriteError, refreshDictAfterInvalid, applyDictInvalid } from '@/utils/dictError'
import { useUserStore } from '@/stores/user'
import { useDictStore } from '@/stores/dict'
import { ofUser } from '@/api/tag'
import SchoolFields from '@/components/common/SchoolFields.vue'
import ProfileTagDialog from '@/views/settings/ProfileTagDialog.vue'
import ProfileEmailDialog from '@/views/settings/ProfileEmailDialog.vue'

const userStore = useUserStore()
const dict = useDictStore()
const router = useRouter()
const { load: loadVerify } = useVerifyStatus()

const saving = ref(false)
const schoolSaving = ref(false)
const identitySaved = ref(false)
const identityInvalid = ref({})

/** PUT /user/me 管的字段。不含 dept / grade —— 它们走 school-profile */
const form = reactive({ nickname: '', studentNo: '', avatarUrl: '' })

/** POST /user/school-profile 管的字段。与 IDENTITY_MODEL 同形 */
const identity = ref({ ...IDENTITY_MODEL })
const identityOriginal = ref({ ...IDENTITY_MODEL })

/** 安全凭证邮箱:脱敏值 + 是否已绑定(仅「我的资料」ofPrivate 暴露) */
const emailBound = ref(false)
const emailMasked = ref('')
const emailOpen = ref(false)

/** 兴趣标签:页面上直接铺 chip,所以要一份「当前已选」的浅拷贝(写回走弹层) */
const myTags = ref([])
const tagDialog = ref(null)

/** 五字段都比。只比 school/dept/grade 会漏掉「只改专业」——那时 dirty=false,saveAll 直接跳过提交 */
const identityDirty = computed(() =>
  identity.value.schoolId !== identityOriginal.value.schoolId
  || identity.value.deptId !== identityOriginal.value.deptId
  || identity.value.level !== identityOriginal.value.level
  || identity.value.gradeId !== identityOriginal.value.gradeId
  || identity.value.majorId !== identityOriginal.value.majorId,
)

/** 认证状态提示行的文案与主色(映射逻辑见 useVerifyTip) */
const { verifyTip } = useVerifyTip()

async function load() {
  const me = await meApi()
  form.nickname = me.nickname || ''
  /**
   * 学号**必须从 /user/verify/status 拿**,不能期待 /user/me:
   * UserVO 同时用于关注列表 / 搜索等多人场景,后端刻意不在里面放学号(隐私)。
   */
  let studentNo = ''
  try {
    const vs = await loadVerify()
    studentNo = vs?.studentNo || ''
  } catch {
    /* 认证状态拉不到不影响其它字段 */
  }
  form.studentNo = studentNo
  form.avatarUrl = me.avatarUrl || ''
  emailBound.value = !!me.emailBound
  emailMasked.value = me.email || ''

  const read = readIdentity(me, { depts: dict.depts, grades: dict.grades })
  // 有专业才需要等专业字典(level 由 majorId 反查);无专业时不阻塞表单回填
  if (read.majorId) await dict.ensureMajorsLoaded()
  identity.value = {
    schoolId: me.schoolId || SCHOOLS[0].id,
    deptId: read.deptId,
    level: dict.levelOfMajor(read.majorId),
    gradeId: read.gradeId,
    majorId: read.majorId,
  }
  identityOriginal.value = { ...identity.value }
  identitySaved.value = false
}

async function loadTags() {
  try {
    myTags.value = (await ofUser(userStore.userInfo?.id)) || []
  } catch {
    myTags.value = []
  }
}

async function uploadAvatar({ file }) {
  try {
    const data = await uploadToOss(file, 'image')
    form.avatarUrl = data.url
    ElMessage.success('头像已更新,记得点保存')
  } catch {
    ElMessage.error('上传失败')
  }
}

function openTagEditor() {
  tagDialog.value?.open(userStore.userInfo?.id)
}

/** 学校归属保存。返回 true 表示存上了,底部总按钮据此决定要不要继续存资料 */
async function saveSchool() {
  // 五项齐了才提交:后端 majorId 必填;level 是专业下拉的前置,缺了选不到专业
  if (!identity.value.schoolId || !identity.value.deptId || !identity.value.level
      || !identity.value.gradeId || !identity.value.majorId) {
    ElMessage.warning('学校、学院、本专科、专业、年级都选了再保存')
    return false
  }
  schoolSaving.value = true
  identityInvalid.value = {}
  try {
    await saveSchoolProfile(identity.value)
    identityOriginal.value = { ...identity.value }
    identitySaved.value = true
    await userStore.fetchMe()
    return true
  } catch (e) {
    const bad = dictWriteError(e)
    if (bad) {
      identityInvalid.value = applyDictInvalid(identity.value, bad)
      await refreshDictAfterInvalid(dict)
    }
    return false
  } finally {
    schoolSaving.value = false
  }
}

async function saveProfile() {
  const data = await request.put('/user/me', {
    nickname: form.nickname,
    studentNo: form.studentNo,
    avatarUrl: form.avatarUrl,
  })
  userStore.setUserInfo(data)
}

/**
 * 底部唯一保存按钮。先存学校归属,存上了再存资料。
 * 学校失败就停在这里 —— 继续往下走只会得到「学校没存上、其它存上了」的半成功态,
 * 而用户完全看不出到底哪一半生效了。
 */
async function saveAll() {
  if (identityDirty.value) {
    saving.value = true
    const ok = await saveSchool()
    saving.value = false
    if (!ok) return
    ElMessage.success('学校归属已保存')
  }
  saving.value = true
  try {
    await saveProfile()
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

function onEmailSaved(vo) {
  emailBound.value = vo.emailBound !== false
  emailMasked.value = vo.email || emailMasked.value
}

onMounted(async () => {
  await dict.ensureLoaded()
  await load()
  await loadTags()
})
</script>

<style scoped src="./SettingsProfile.css"></style>
