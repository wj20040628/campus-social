import { ref } from 'vue'
import dayjs from 'dayjs'
import { createPost } from '@/api/feed'
import { createActivity } from '@/api/circle'
import { ElMessage } from '@/utils/feedback'

/**
 * 圈子详情 —— 发帖 / 发活动两条写路径。
 * 从 CircleDetail.vue 抽出,因为它自带草稿态、校验、上传态和错误文案,
 * 内联在视图里会把文件推过 300 行门禁;而且这两条路径与读取逻辑无关。
 */
export function useCircleWrites(circleId, canCreate, reloadPosts, reloadActivities, onActivityCreated) {
  /* ---------------- 发帖 ---------------- */
  const postContent = ref('')
  const postMedia = ref([])
  const posting = ref(false)
  const uploading = ref(false)
  const postError = ref('')

  async function submitPost() {
    if (posting.value) return
    posting.value = true
    postError.value = ''
    try {
      await createPost({
        content: postContent.value,
        media: postMedia.value.length ? JSON.stringify(postMedia.value) : null,
        circleId: Number(circleId.value),
      })
      postContent.value = ''
      postMedia.value = []
      await reloadPosts()
      ElMessage.success('发到圈子了')
    } catch {
      // **内容保留**(§3):不清空 textarea。发失败就清,等于让用户重打一遍
      postError.value = '没发出去,再点一次发布'
    } finally {
      posting.value = false
    }
  }

  /* ---------------- 发活动 ---------------- */
  const showActForm = ref(false)
  const creatingAct = ref(false)
  const actError = ref('')
  const actForm = ref({ title: '', capacity: 0, startTime: null, endTime: null })

  const EMPTY_FORM = () => ({ title: '', capacity: 0, startTime: null, endTime: null })

  function openActForm() {
    actError.value = ''
    showActForm.value = true
  }

  async function submitActivity() {
    if (creatingAct.value) return
    const f = actForm.value

    // 校验错误写回表单区,不弹 toast:抽屉里弹出的 toast 会盖住提交按钮,
    // 用户看不到自己错在哪一行
    if (!f.title.trim()) {
      actError.value = '先写个活动标题'
      return
    }
    if (!f.startTime || !f.endTime) {
      actError.value = '开始和结束时间都要选'
      return
    }
    if (dayjs(f.endTime).isBefore(dayjs(f.startTime))) {
      actError.value = '结束时间要晚于开始时间'
      return
    }

    creatingAct.value = true
    actError.value = ''
    try {
      await createActivity({
        title: f.title.trim(),
        circleId: Number(circleId.value),
        capacity: f.capacity || 0,
        startTime: f.startTime.toISOString(),
        endTime: f.endTime.toISOString(),
      })
      showActForm.value = false
      actForm.value = EMPTY_FORM()
      await reloadActivities()
      // 发完活动切到活动 Tab:不然用户在帖子 Tab 发完,看不到自己发的在哪
      onActivityCreated?.()
      ElMessage.success('活动发出去了')
    } catch {
      // 同上:抽屉不关,用户填的东西还在
      actError.value = '没发出去,再点一次'
    } finally {
      creatingAct.value = false
    }
  }

  return {
    postContent,
    postMedia,
    posting,
    uploading,
    postError,
    submitPost,
    showActForm,
    creatingAct,
    actError,
    actForm,
    openActForm,
    submitActivity,
  }
}