package com.campus.social.service.support;

import com.campus.social.entity.Group;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.GroupMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.campus.social.common.ResultCode.NOT_FOUND;
import static com.campus.social.common.ResultCode.NOT_GROUP_MEMBER;
import static com.campus.social.common.ResultCode.NOT_GROUP_OWNER;

/**
 * 群访问校验（2026-10-30 从 GroupServiceImpl 抽出）：
 * 群存在性、群主断言、成员断言，供群管理/设置/消息/查询共用。
 */
@Component
@RequiredArgsConstructor
public class GroupAccessSupport {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;

    /** 取群行(存在性 + 群主信息),已解散/不存在则 NOT_FOUND */
    public Group requireGroup(long groupId) {
        Group g = groupMapper.selectById(groupId);
        if (g == null || g.getStatus() == 0) {
            throw new BusinessException(NOT_FOUND, "群不存在或已解散");
        }
        return g;
    }

    /** 校验我是群主,返回群行 */
    public Group assertOwner(long groupId, long me) {
        Group g = requireGroup(groupId);
        if (!g.getOwnerId().equals(me)) {
            throw new BusinessException(NOT_GROUP_OWNER, "仅群主可操作");
        }
        return g;
    }

    public void assertMember(long groupId, long me) {
        if (groupMemberMapper.isMember(groupId, me) == 0) {
            throw new BusinessException(NOT_GROUP_MEMBER, "你不是该群成员");
        }
    }
}
