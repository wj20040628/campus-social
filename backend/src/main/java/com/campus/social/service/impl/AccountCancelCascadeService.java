package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.Activity;
import com.campus.social.entity.ActivitySignup;
import com.campus.social.entity.Circle;
import com.campus.social.entity.CircleMember;
import com.campus.social.entity.Group;
import com.campus.social.entity.Relation;
import com.campus.social.mapper.ActivityMapper;
import com.campus.social.mapper.ActivitySignupMapper;
import com.campus.social.mapper.CircleMapper;
import com.campus.social.mapper.CircleMemberMapper;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.GroupMemberMapper;
import com.campus.social.mapper.GroupMessageMapper;
import com.campus.social.mapper.GroupReadMapper;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 账号注销时的结构关系清退（2026-11-01，1.13.9）。
 *
 * <p>注销不是「只改 user 行」：群/圈成员关系、群已读水位、活动报名都是
 * 以 user_id 挂在其他业务行上的。若不清退，群主/圈主注销会留下无法管理的
 * 组织，报名会永久占用活动名额，成员数也会把已注销账号算进去。
 *
 * <p>口径（与产品确认）：
 * <ul>
 *   <li>群/圈直接退出；群主/圈主优先移交给仍正常的成员，
 *       圈子优先圈管理、其余按加入顺序；无人可接则解散。</li>
 *   <li>活动报名物理删除并回补 current_count；活动本身保留，由创建者/管理员处置。</li>
 *   <li>帖子/评论、私聊历史保留；这些属于内容留痕，和成员关系是两套口径。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCancelCascadeService {

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupReadMapper groupReadMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final CircleMapper circleMapper;
    private final CircleMemberMapper circleMemberMapper;
    private final PostMapper postMapper;
    private final ActivitySignupMapper signupMapper;
    private final ActivityMapper activityMapper;
    private final MktActivityMapper mktActivityMapper;
    private final RelationMapper relationMapper;
    private final PostNotifySupport notifySupport;
    private final UserMapper userMapper;
    private final FeedPageCacheService feedCache;

    /**
     * 清退一个已注销账号的全部成员关系。调用前 user.status 必须已经置 2，
     * 这样「仍正常成员」的筛选会自动排除本人。
     */
    public Result purge(long userId) {
        int groupsLeft = 0;
        int groupsTransferred = 0;
        int groupsDisbanded = 0;
        Set<Long> handledGroups = new HashSet<>();

        List<Group> ownedGroups = groupMapper.selectList(new LambdaQueryWrapper<Group>()
                .eq(Group::getOwnerId, userId)
                .eq(Group::getStatus, 1));
        for (Group g : ownedGroups) {
            handledGroups.add(g.getId());
            if (transferOrDisbandGroup(g, userId)) {
                groupsTransferred++;
            } else {
                groupsDisbanded++;
            }
        }
        for (Long groupId : groupMemberMapper.groupIdsByUser(userId)) {
            if (!handledGroups.add(groupId)) {
                continue;
            }
            Group g = groupMapper.selectById(groupId);
            if (g == null || !Integer.valueOf(1).equals(g.getStatus())) {
                continue;
            }
            if (g.getOwnerId().equals(userId)) {
                // 兜底：群行存在但不在上面的 ownedGroups 结果里
                if (transferOrDisbandGroup(g, userId)) {
                    groupsTransferred++;
                } else {
                    groupsDisbanded++;
                }
                continue;
            }
            groupMemberMapper.hardDeleteByGroupAndUsers(groupId, List.of(userId));
            groupReadMapper.deleteByGroupAndUser(groupId, userId);
            groupMapper.recountMemberCount(groupId);
            groupsLeft++;
        }

        int circlesLeft = 0;
        int circlesTransferred = 0;
        int circlesDisbanded = 0;
        Set<Long> handledCircles = new HashSet<>();

        List<Circle> ownedCircles = circleMapper.selectList(new LambdaQueryWrapper<Circle>()
                .eq(Circle::getOwnerId, userId)
                .eq(Circle::getStatus, 0));
        for (Circle c : ownedCircles) {
            handledCircles.add(c.getId());
            List<CircleMember> active = circleMemberMapper.activeMembersOrdered(c.getId());
            if (active.isEmpty()) {
                disbandCircle(c);
                circlesDisbanded++;
            } else {
                CircleMember next = active.get(0);
                circleMapper.transferOwner(c.getId(), next.getUserId());
                circleMemberMapper.setRole(c.getId(), next.getUserId(), 1);
                removeCircleMember(c, userId);
                circlesTransferred++;
                log.info("cancel cascade: circle {} transferred {} -> {}",
                        c.getId(), userId, next.getUserId());
            }
        }

        List<CircleMember> memberships = circleMemberMapper.selectList(
                new LambdaQueryWrapper<CircleMember>().eq(CircleMember::getUserId, userId));
        for (CircleMember m : memberships) {
            long circleId = m.getCircleId();
            if (handledCircles.contains(circleId)) {
                continue;
            }
            Circle c = circleMapper.selectById(circleId);
            circleMemberMapper.hardDelete(circleId, userId, m.getRole());
            if (c != null && Integer.valueOf(0).equals(c.getStatus())) {
                circleMapper.recountMemberCount(circleId);
                userMapper.adjustJoinedCircleCount(userId, -1);
                circlesLeft++;
            }
            // 已解散/已封禁圈子的残留成员行也删掉，但不再改活跃计数
        }

        // 注销用户创建的活动：无人能再管理，统一取消并通知仍报名的正常用户。
        // 先取消再删报名，通知时能拿到完整报名名单；当前用户自己的报名随后统一清退。
        int activitiesCancelled = 0;
        List<Activity> createdActivities = activityMapper.selectList(new LambdaQueryWrapper<Activity>()
                .eq(Activity::getCreatorId, userId)
                .in(Activity::getStatus, 0, 1, 2));
        for (Activity a : createdActivities) {
            if (activityMapper.cancelOpenById(a.getId()) == 0) {
                continue; // 并发下已被其他请求取消，避免重复通知
            }
            List<ActivitySignup> listeners = signupMapper.selectList(
                    new LambdaQueryWrapper<ActivitySignup>().eq(ActivitySignup::getActivityId, a.getId()));
            for (ActivitySignup s : listeners) {
                if (!s.getUserId().equals(userId)) {
                    notifySupport.pushSystemNotify(s.getUserId(), 12, a.getId(), null,
                            "活动创建者已注销，活动已取消");
                }
            }
            activitiesCancelled++;
        }

        int signupsRemoved = 0;
        List<ActivitySignup> signups = signupMapper.selectList(
                new LambdaQueryWrapper<ActivitySignup>().eq(ActivitySignup::getUserId, userId));
        for (ActivitySignup s : signups) {
            if (signupMapper.hardDelete(s.getActivityId(), userId) > 0) {
                activityMapper.cancelSignin(s.getActivityId());
                signupsRemoved++;
            }
        }

        // 集市发布立即下线；帖子/评论/私聊仍按既定口径保留
        int mktDisabled = mktActivityMapper.disableByUser(userId);

        // 关注关系随账号注销清除；黑名单保留以支持对方解除拉黑。
        // 计数只改关系另一端，注销账号自身计数随后由全量对账归零。
        int followsRemoved = 0;
        List<Relation> follows = relationMapper.selectList(new LambdaQueryWrapper<Relation>()
                .eq(Relation::getType, 1)
                .and(w -> w.eq(Relation::getFromUserId, userId).or().eq(Relation::getToUserId, userId)));
        for (Relation r : follows) {
            if (relationMapper.hardDelete(r.getFromUserId(), r.getToUserId(), 1) > 0) {
                if (r.getFromUserId().equals(userId)) {
                    userMapper.adjustFollowerCount(r.getToUserId(), -1);
                } else {
                    userMapper.adjustFollowingCount(r.getFromUserId(), -1);
                }
                followsRemoved++;
            }
        }

        userMapper.resetJoinedCircleCount(userId);
        Result result = new Result(groupsLeft, groupsTransferred, groupsDisbanded,
                circlesLeft, circlesTransferred, circlesDisbanded, signupsRemoved,
                activitiesCancelled, mktDisabled, followsRemoved);
        log.info("account cancel cascade: uid={}, {}", userId, result.summary());
        return result;
    }

    private boolean transferOrDisbandGroup(Group g, long userId) {
        long groupId = g.getId();
        List<Long> active = groupMemberMapper.activeMemberIdsOrdered(groupId).stream()
                .filter(id -> !id.equals(userId))
                .toList();
        if (active.isEmpty()) {
            List<Long> all = groupMemberMapper.memberIds(groupId);
            if (!all.isEmpty()) {
                groupMemberMapper.hardDeleteByGroupAndUsers(groupId, all);
            }
            groupReadMapper.deleteByGroup(groupId);
            groupMessageMapper.deleteByGroup(groupId);
            groupMapper.deleteById(groupId);
            log.info("cancel cascade: group {} disbanded (no active member)", groupId);
            return false;
        }
        long nextOwner = active.get(0);
        groupMapper.transferOwner(groupId, nextOwner);
        groupMemberMapper.updateRole(groupId, nextOwner, 1);
        groupMemberMapper.hardDeleteByGroupAndUsers(groupId, List.of(userId));
        groupReadMapper.deleteByGroupAndUser(groupId, userId);
        groupMapper.recountMemberCount(groupId);
        log.info("cancel cascade: group {} transferred {} -> {}", groupId, userId, nextOwner);
        return true;
    }

    private void removeCircleMember(Circle c, long userId) {
        CircleMember row = circleMemberMapper.selectOne(new LambdaQueryWrapper<CircleMember>()
                .eq(CircleMember::getCircleId, c.getId())
                .eq(CircleMember::getUserId, userId));
        if (row != null) {
            circleMemberMapper.hardDelete(c.getId(), userId, row.getRole());
            userMapper.adjustJoinedCircleCount(userId, -1);
        }
        circleMapper.recountMemberCount(c.getId());
    }

    private void disbandCircle(Circle c) {
        long circleId = c.getId();
        circleMemberMapper.decrementJoinedCountsForCircle(circleId);
        circleMemberMapper.hardDeleteAllByCircle(circleId);
        circleMapper.disband(circleId);
        circleMapper.clearMemberCount(circleId);
        postMapper.invalidateCirclePosts(circleId);
        feedCache.invalidate();
        log.info("cancel cascade: circle {} disbanded (no active member)", circleId);
    }

    /** 一次注销的级联结果，写进审计说明，便于事后核对清退了哪些关系。 */
    public record Result(int groupsLeft, int groupsTransferred, int groupsDisbanded,
                         int circlesLeft, int circlesTransferred, int circlesDisbanded,
                         int signupsRemoved, int activitiesCancelled,
                         int mktDisabled, int followsRemoved) {
        public String summary() {
            return "退出群 " + groupsLeft + "、移交群 " + groupsTransferred + "、解散群 " + groupsDisbanded
                    + "；退出圈 " + circlesLeft + "、移交圈 " + circlesTransferred + "、解散圈 " + circlesDisbanded
                    + "；退报活动 " + signupsRemoved + "、取消自己创建的活动 " + activitiesCancelled
                    + "；下线集市 " + mktDisabled + "、清理关注关系 " + followsRemoved;
        }
    }
}
