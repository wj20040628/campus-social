package com.campus.social.service;

import com.campus.social.common.CursorPage;
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

    /** 撤回群消息:仅发送方、8s 窗口、幂等(镜像单聊 recall)。已撤回直接返。 */
    void recallGroupMessage(long me, long msgId);

    /** 群已读:更新我的水位到最新 + WS 推已读回执给相关发送者(镜像单聊 markRead)。幂等。 */
    void markGroupRead(long me, long groupId);
}
