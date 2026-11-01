package com.campus.social.service.support;

import com.campus.social.mapper.ActivityMapper;
import com.campus.social.mapper.ActivitySignupMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.GroupMemberMapper;
import com.campus.social.mapper.GroupReadMapper;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 成员关系清理与冗余计数的唯一入口（2026-11-01，1.13.11）。
 *
 * <p>此前每个 Service 各写一遍「先查再删、删完再减计数」，并发重复请求时
 * 第二个请求删到 0 行仍会继续减计数。这里统一按「实际删除行数 > 0」
 * 决定是否调整计数，业务方不再直接调用 adjustX / decMemberCount。
 *
 * <p>只处理活跃实体上的正常成员关系；群解散、圈封禁后的残留行由级联
 * 或修复脚本按各自口径清理，不走这里的计数逻辑。
 */
@Component
@RequiredArgsConstructor
public class MembershipCleanupSupport {

    private static final int TYPE_FOLLOW = 1;

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupReadMapper groupReadMapper;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final ActivityMapper activityMapper;
    private final ActivitySignupMapper signupMapper;
    private final RelationMapper relationMapper;
    private final UserMapper userMapper;

    /** 退出/移出活跃群；真正删到成员行才清已读水位并减群成员数 */
    public boolean removeGroupMember(long groupId, long userId) {
        if (groupMemberMapper.hardDeleteByGroupAndUsers(groupId, List.of(userId)) == 0) {
            return false;
        }
        groupReadMapper.deleteByGroupAndUser(groupId, userId);
        groupMapper.decMemberCount(groupId);
        return true;
    }

    /** 退出活跃圈；真正删到成员行才减圈人数与用户「加入的圈子数」 */
    public boolean removeCircleMember(long circleId, long userId, int role) {
        if (circleMemberMapper.hardDelete(circleId, userId, role) == 0) {
            return false;
        }
        circleMapper.adjustMemberCount(circleId, -1);
        userMapper.adjustJoinedCircleCount(userId, -1);
        return true;
    }

    /** 取消活动报名；真正删到报名行才回补活动名额 */
    public boolean cancelSignup(long activityId, long userId) {
        if (signupMapper.hardDelete(activityId, userId) == 0) {
            return false;
        }
        activityMapper.cancelSignin(activityId);
        return true;
    }

    /** 取消关注；真正删到关系行才减双方关注/粉丝数 */
    public boolean unfollow(long fromUserId, long toUserId) {
        if (relationMapper.hardDelete(fromUserId, toUserId, TYPE_FOLLOW) == 0) {
            return false;
        }
        userMapper.adjustFollowingCount(fromUserId, -1);
        userMapper.adjustFollowerCount(toUserId, -1);
        return true;
    }
}
