package com.campus.social.service.impl;

import com.campus.social.common.CursorPage;
import com.campus.social.service.GroupService;
import com.campus.social.vo.GroupMemberInfoVO;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 群聊服务门面（2026-10-30 拆分）。
 *
 * <p>成员与群主变更 → {@link GroupMembershipService}，设置 → {@link GroupSettingsService}，
 * 消息 → {@link GroupMessageService}，读取 → {@link GroupQueryService}。
 * 事务边界留在门面，写方法仍是一个事务。
 */
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    private final GroupMembershipService membership;
    private final GroupSettingsService settings;
    private final GroupMessageService messages;
    private final GroupQueryService query;

    @Override
    @Transactional
    public long createGroup(long ownerId, String name, String avatarUrl, List<Long> memberIds) {
        return membership.createGroup(ownerId, name, avatarUrl, memberIds);
    }

    @Override
    @Transactional
    public int addMembers(long groupId, long me, List<Long> userIds) {
        return membership.addMembers(groupId, me, userIds);
    }

    @Override
    @Transactional
    public void kick(long groupId, long me, long userId) {
        membership.kick(groupId, me, userId);
    }

    @Override
    @Transactional
    public void rename(long groupId, long me, String name) {
        settings.rename(groupId, me, name);
    }

    @Override
    @Transactional
    public void setAnnouncement(long groupId, long me, String text) {
        settings.setAnnouncement(groupId, me, text);
    }

    @Override
    @Transactional
    public void transfer(long groupId, long me, long newOwnerId) {
        membership.transfer(groupId, me, newOwnerId);
    }

    @Override
    @Transactional
    public void leaveGroup(long groupId, long me) {
        membership.leaveGroup(groupId, me);
    }

    @Override
    @Transactional
    public void mute(long groupId, long me, long userId, boolean muted) {
        settings.mute(groupId, me, userId, muted);
    }

    @Override
    @Transactional
    public void muteAll(long groupId, long me, boolean muted) {
        settings.muteAll(groupId, me, muted);
    }

    @Override
    public boolean isMuted(long groupId, long me) {
        return query.isMuted(groupId, me);
    }

    @Override
    public CursorPage<GroupVO> myGroups(long me, Long lastId, int size) {
        return query.myGroups(me, lastId, size);
    }

    @Override
    public CursorPage<GroupMessageVO> groupMessages(long groupId, long me, Long lastId, int size) {
        return messages.groupMessages(groupId, me, lastId, size);
    }

    @Override
    @Transactional
    public com.campus.social.vo.GroupSendVO sendGroupMessage(long me, long groupId, String content, Long replyToId) {
        return messages.sendGroupMessage(me, groupId, content, replyToId);
    }

    @Override
    @Transactional
    public void recallGroupMessage(long me, long msgId) {
        messages.recallGroupMessage(me, msgId);
    }

    @Override
    @Transactional
    public void markGroupRead(long me, long groupId) {
        messages.markGroupRead(me, groupId);
    }

    @Override
    public GroupMemberInfoVO members(long groupId, long me) {
        return query.members(groupId, me);
    }
}
