package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.vo.GroupMemberInfoVO;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupVO;

import java.util.List;

/**
 * 群聊(在单聊私信之上扩展):建群/拉人/群消息/群已读/群撤回。
 * 群走独立四表(group/group_member/group_message/group_read),单聊 chat_* 不动。
 */
public interface GroupService {

    /**
     * 建群:建群 + 拉人 + owner 也是 member(群主),member_count 原子累加。
     * 幂等:同 owner+name 重复建群会撞(由调用方决定);群主必在成员里。
     * @return 新建群 id
     */
    long createGroup(long ownerId, String name, String avatarUrl, List<Long> memberIds);

    /** 我加入的群(含我侧未读),游标分页 */
    CursorPage<GroupVO> myGroups(long me, Long lastId, int size);

    /** 群历史消息(校验我是成员),批量回填被引片段 */
    CursorPage<GroupMessageVO> groupMessages(long groupId, long me, Long lastId, int size);

    /**
     * 发群消息:插入 + WS 多播给除发送者外的全体成员。
     * 幂等:非成员/空内容抛业务异常。
     * @return 新消息 id
     */
    long sendGroupMessage(long me, long groupId, String content, Long replyToId);

    /** 撤回群消息:仅发送方、120s 窗口、幂等(镜像单聊 recall)。已撤回直接返。 */
    void recallGroupMessage(long me, long msgId);

    /** 群已读:更新我的水位到最新 + WS 推已读回执给相关发送者(镜像单聊 markRead)。幂等。 */
    void markGroupRead(long me, long groupId);

    // ---------------------------------------------------------------- 群管理(群信息头)

    /** 查群信息头:群名/人数/群主/公告 + 成员名单(校验我是成员;群主置顶、我标 isSelf) */
    GroupMemberInfoVO members(long groupId, long me);

    /** 拉人(仅群主):把一批已存在、未入群的成员加进现有群,member_count 原子累加;满员抛 GROUP_FULL */
    int addMembers(long groupId, long me, List<Long> userIds);

    /** 踢人(仅群主):把某普通成员移出(不能踢群主自己),清其水位 */
    void kick(long groupId, long me, long userId);

    /** 群改名(仅群主):name 非空 */
    void rename(long groupId, long me, String name);

    /** 群公告(仅群主):空串=清除 */
    void setAnnouncement(long groupId, long me, String text);

    /** 群主转让(仅群主):把群主位传给某普通成员(须是群成员且非我),我降为普通成员留在群里 */
    void transfer(long groupId, long me, long newOwnerId);

    /**
     * 退群:非群主→物理删我 + 减成员数 + 清我水位;
     * 群主退→若还有其它成员自动转让给"第一个非我成员",若只剩我则解散(物理清四表 + 软删群行)。
     */
    void leaveGroup(long groupId, long me);

    // ---------------------------------------------------------------- 群禁言(群主)

    /** 禁言某成员(仅群主):muted=1 禁言 / 0 解除。目标不能是群主自己 */
    void mute(long groupId, long me, long userId, boolean muted);

    /** 禁言全员(仅群主):除群主外全部成员 muted 置值(1 全员禁言 / 0 解除全员),幂等 */
    void muteAll(long groupId, long me, boolean muted);

    /** 查我是否被该群禁言(发消息校验 + 前端输入框禁用用);非成员/无行则 false */
    boolean isMuted(long groupId, long me);
}
