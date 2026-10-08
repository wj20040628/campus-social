/**
 * 全站空态文案(AC-19:「一句人话 + 一个可点行动按钮」)。
 *
 * 为什么不各页内联:11 个页面各写一套,半年后必然漂移成
 * 「暂无数据」「敬请期待」这类没有出口的文案。集中在这里之后,
 * review 时只查一个文件,且新增页面没有理由再自己编文案。
 *
 * 每条都遵守三条硬规则:
 * 1. 主句说「现在是什么状态」,不说「暂无」;
 * 2. 副句说「你能怎么办」,给出下一步;
 * 3. actionLabel 必须是动词短语(去搜索 / 发一条),不是「确定」。
 */

export const EMPTY = {
  /** 首页信息流(可能是还没关注人,也可能是服务端真没内容) */
  feed: {
    title: '这里还很空',
    description: '关注几个同学,他们的动态就会出现在这儿',
    action: '去圈子广场逛逛',
  },
  /** 圈子广场 */
  circles: {
    title: '还没有圈子',
    description: '圈子是同好聚在一起的地方,你可以自己开一个',
    action: '开个圈子',
  },
  /** 个人主页 - 动态 Tab */
  profilePosts: {
    title: '还没发过动态',
    description: '发一条,同校的同学就能看到你',
    action: '发一条',
  },
  /** 话题页 */
  topic: {
    title: '这个话题下还没有动态',
    description: '发一条,这个话题就有内容了',
    action: '发第一条',
  },
  /** 搜索 - 帖子结果 */
  searchPosts: {
    title: '没有匹配的帖子',
    description: '换个词试试,或者直接发一条这个话题的',
    action: '去发一条',
  },
  /** 搜索 - 用户结果 */
  searchUsers: {
    title: '没有匹配的同学',
    description: '关键词可能太长了,试试只输入姓或昵称',
    action: '改个关键词',
  },
  /** 搜索 - 圈子结果 */
  searchCircles: {
    title: '没有匹配的圈子',
    description: '换个词试试,或者自己开一个',
    action: '去圈子广场',
  },
  /** 搜索未开始 */
  searchIdle: {
    title: '搜搜看谁在聊什么',
    description: '输入昵称、话题或圈子名,三个方向一起搜',
    action: '',
  },
  /** 后台审核 - 待审 */
  reviewPending: {
    title: '没有待审内容',
    description: '有人发帖时会出现在这里',
    action: '',
  },
  /** 后台审核 - 已处理 */
  reviewDone: {
    title: '还没有处理记录',
    description: '审过的内容会留档在这里',
    action: '',
  },
  /** 审计日志 */
  audit: {
    title: '没有匹配的日志',
    description: '换个筛选条件,或者清空关键词',
    action: '清空筛选',
  },
  /** 通知 */
  notify: {
    title: '没有新通知',
    description: '有人点赞、评论或关注你时会出现在这儿',
    action: '',
  },
  /** 帖子详情 - 评论列表 */
  comments: {
    title: '还没有人评论',
    description: '说点什么,让发这条的人知道你看到了',
    action: '',
  },
  /** 帖子不存在 */
  postMissing: {
    title: '这条帖子不在了',
    description: '可能被作者删了,或者链接过期了',
    action: '回首页',
  },
  /** 活动不存在 */
  activityMissing: {
    title: '这个活动不存在',
    description: '可能已经结束或被发起人撤掉了',
    action: '回圈子广场',
  },
  /** 权限不足 */
  denied: {
    title: '你不是管理员',
    description: '这个页面只有管理员能进',
    action: '回首页',
  },
  /** 加载失败(通用) */
  failed: {
    title: '没拉出来',
    description: '可能是网络断了,点一下重试',
    action: '重试',
  },
}