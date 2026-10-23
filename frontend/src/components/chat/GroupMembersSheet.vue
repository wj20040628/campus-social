<template>
  <!--
    群成员名单(2026-10-15 从 GroupHead.vue 拆出,2026-10-21 重写)。
    这一版只做一件事:看人。

    删掉的东西(都有明确理由,不是"顺手精简"):
    - 公告 pill        → 同一份数据在聊天页顶部已经有一条,第二次出现零信息。
    - 行内三个按钮      → 20 人的名单会出现 20 组并排热区,其中一个是不可逆的移出。
    - 底部三个动作      → 与 ⋯ 菜单完全重复,两处权限判断必然漂移。
    - 脱敏学号          → 群成员之间不需要互认学号,且位数结构全暴露。
  -->
  <ActionSheet
    :model-value="modelValue"
    title="群成员"
    height="70vh"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="gms">
      <!--
        G13:邀请是这个页面唯一的正向动作,放标题下面第一行。
        用虚线框 —— 虚线的语义是「这里还缺东西」,比实线按钮更轻;
        名单为空时它也在原位,不会因为没人而消失。
      -->
      <button type="button" class="gms__invite" @click="emit('add')">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        邀请成员
      </button>

      <ul class="gms__list">
        <li
          v-for="mm in members"
          :key="mm.user?.id"
          class="gms__row"
          :class="{ 'is-self': mm.isSelf }"
          @click="onPick(mm)"
        >
          <InitialAvatar
            :size="40"
            :src="mm.user?.avatarUrl ? resolveThumb(mm.user.avatarUrl) : ''"
            :seed="mm.user?.id"
            :name="nickOf(mm)"
          />
          <div class="gms__i">
            <div class="gms__n">
              <span class="gms__nick">{{ nickOf(mm) }}</span>
              <span v-if="mm.isOwner" class="gms__tag gms__tag--owner">群主</span>
              <span v-if="mm.muted" class="gms__tag gms__tag--muted">已禁言</span>
            </div>
            <div class="gms__sub">{{ subOf(mm) }}</div>
          </div>
          <!-- 自己那一行不给 › :点自己没有任何可做的事,给个箭头会让人白点一次 -->
          <span v-if="mm.isSelf" class="gms__me">我</span>
          <svg v-else class="gms__go" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true">
            <path d="m9 6 6 6-6 6" />
          </svg>
        </li>
      </ul>
    </div>
  </ActionSheet>
</template>

<script setup>
/**
 * 群成员名单。开合由父组件持有(v-model),本组件不发任何请求、不持有任何弹层。
 *
 * 点某一行的处理交给父组件(GroupMenu):它会先收起名单、再弹出针对这个人的
 * 操作表 —— 两个 ActionSheet 不能同时在场。
 */
import { computed } from 'vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { resolveThumb } from '@/utils/resolve'

const props = defineProps({
  /** 弹层开合(v-model) */
  modelValue: { type: Boolean, default: false },
  /** 群信息头(GroupMemberInfoVO),含 members */
  info: { type: Object, default: null },
})

const emit = defineEmits(['update:modelValue', 'add', 'pick'])

/** 群主排第一、我在中间 —— 顺序由后端给,这里不重排 */
const members = computed(() => props.info?.members || [])

function nickOf(mm) {
  return mm?.user?.nickname || mm?.user?.username || '成员'
}

/**
 * G14:副行 = 角色 + 入群时间,不再显示脱敏学号。
 * 群主写「创建于」,其余写「入群」—— 群主没有"入群"这个动作,他就是建群的人。
 * joinDate 缺失(后端旧版本 / 历史行 create_time 为空)时只留角色,不显示空日期。
 */
function subOf(mm) {
  const d = mm?.joinDate || ''
  if (mm?.isOwner) return d ? `创建于 ${d}` : '群主'
  return d ? `${d} 入群` : '群成员'
}

function onPick(mm) {
  if (mm?.isSelf) return
  emit('pick', mm)
}
</script>

<style scoped src="./GroupMembersSheet.css"></style>
