package com.campus.social.service.impl;

import com.campus.social.entity.Group;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.GroupMemberMapper;
import com.campus.social.service.support.GroupAccessSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.campus.social.common.ResultCode.GROUP_NAME_EMPTY;
import static com.campus.social.common.ResultCode.NOT_GROUP_OWNER;

/**
 * 群设置（2026-10-30 从 GroupServiceImpl 拆出）：改名、公告、禁言。
 * 全部仅群主可操作。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupSettingsService {

    private final GroupAccessSupport access;
    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;

    public void rename(long groupId, long me, String name) {
        access.assertOwner(groupId, me);
        if (name == null || name.isBlank()) {
            throw new BusinessException(GROUP_NAME_EMPTY, "群名称不能为空");
        }
        Group upd = new Group();
        upd.setId(groupId);
        upd.setName(name.trim());
        groupMapper.updateById(upd);
        log.info("group rename: group={} owner={}", groupId, me);
    }

    public void setAnnouncement(long groupId, long me, String text) {
        access.assertOwner(groupId, me);
        Group upd = new Group();
        upd.setId(groupId);
        upd.setAnnouncement(text == null ? null : text.trim());
        groupMapper.updateById(upd);
        log.info("group announcement: group={} owner={} cleared={}", groupId, me, text == null || text.isBlank());
    }

    public void mute(long groupId, long me, long userId, boolean muted) {
        Group g = access.assertOwner(groupId, me);
        // 不能禁言群主自己(群主恒可发言)
        if (userId == g.getOwnerId()) {
            throw new BusinessException(NOT_GROUP_OWNER, "不能禁言群主自己");
        }
        if (groupMemberMapper.isMember(groupId, userId) == 0) {
            return; // 已不在群里,幂等
        }
        groupMemberMapper.setMuted(groupId, userId, muted ? 1 : 0);
        log.info("group mute: group={} owner={} target={} muted={}", groupId, me, userId, muted);
    }

    public void muteAll(long groupId, long me, boolean muted) {
        Group g = access.assertOwner(groupId, me);
        int n = groupMemberMapper.setAllMuted(groupId, g.getOwnerId(), muted ? 1 : 0);
        log.info("group muteAll: group={} owner={} muted={} rows={}", groupId, me, muted, n);
    }
}
