package com.campus.social.service.impl;

import com.campus.social.config.CampusProperties;
import com.campus.social.entity.Group;
import com.campus.social.entity.GroupMember;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.GroupMemberMapper;
import com.campus.social.mapper.GroupMessageMapper;
import com.campus.social.mapper.GroupReadMapper;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.UploadService;
import com.campus.social.service.support.CampusAccessService;
import com.campus.social.service.support.GroupAccessSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.campus.social.common.ResultCode.BAD_REQUEST;
import static com.campus.social.common.ResultCode.GROUP_FULL;
import static com.campus.social.common.ResultCode.GROUP_NAME_EMPTY;
import static com.campus.social.common.ResultCode.NOT_GROUP_MEMBER;
import static com.campus.social.common.ResultCode.NOT_GROUP_OWNER;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;
import static com.campus.social.common.ResultCode.USER_CANCELLED;

/**
 * 群成员与群主变更（2026-10-30 从 GroupServiceImpl 拆出）：
 * 建群、拉人、踢人、转让、退群。
 *
 * <p>权限口径：拉人对所有群成员开放；踢人/转让/退群的处理仍按原规则，
 * 群主退群自动转让给剩余成员，只剩自己时解散。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMembershipService {

    /** 被邀请进群的通知（2026-10-28） */
    private static final int NOTIFY_GROUP_INVITE = 22;

    private final GroupAccessSupport access;
    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupReadMapper groupReadMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final UserMapper userMapper;
    private final UploadService uploadService;
    /** 拉人关系校验:只能邀请关注我/我关注的人,防任意 userId 拉群骚扰 */
    private final RelationMapper relationMapper;
    /** 新成员入群通知:至少让对方知道自己在哪个群、能自己退 */
    private final PostNotifySupport notifySupport;
    /** 行为信任门槛：建群/拉人需账号满 3 天 + 信誉 60（校园信息不参与） */
    private final CampusAccessService campusAccess;
    private final CampusProperties campus;

    public long createGroup(long ownerId, String name, String avatarUrl, List<Long> memberIds) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(GROUP_NAME_EMPTY, "群名称不能为空");
        }
        User owner = userMapper.selectById(ownerId);
        if (owner == null || owner.getStatus() == 1) {
            throw new BusinessException(USER_CANCELLED);
        }
        campusAccess.requireCreateGroup(ownerId, "创建群聊");

        Group g = new Group();
        g.setName(name.trim());
        if (avatarUrl != null && !uploadService.isAvatarAllowed(avatarUrl, ownerId)) {
            throw new BusinessException(BAD_REQUEST, "群头像必须是您自己上传的图片");
        }
        g.setAvatarUrl(avatarUrl);
        g.setOwnerId(ownerId);
        g.setMemberCount(1); // 先算群主 1 人,拉人后累加
        g.setStatus(1);
        groupMapper.insert(g);

        // 群主也是成员(role=1 群主)
        insertMember(g.getId(), ownerId, 1);

        // 拉人:去重、去空、排除群主自己
        if (memberIds != null && !memberIds.isEmpty()) {
            List<Long> valid = memberIds.stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(id -> id != ownerId)
                    .distinct()
                    .toList();
            // 只算"确实存在的人"(与 addMembers 同口径),再校验群人数上限
            List<Long> toAdd = valid.stream()
                    .filter(id -> userMapper.selectById(id) != null)
                    .toList();
            // 2026-10-28:只能拉关注我/我关注的人。此前任意 userId 都能被塞进群,
            // 新账号可以建群后把陌生人批量拉进来发广告。
            assertInvitables(ownerId, toAdd);
            // 建群时也要守上限(群主 1 人 + 本次新增),与 addMembers 的 GROUP_FULL 一致。
            // 此前建群不检查上限,能绕过 groupMaxMembers。
            if (toAdd.size() + 1 > campus.getGroupMaxMembers()) {
                throw new BusinessException(GROUP_FULL, "群成员已达上限,请减少初始成员");
            }
            for (Long uid : toAdd) {
                insertMember(g.getId(), uid, 2);
            }
            if (!toAdd.isEmpty()) {
                groupMapper.touchCountAndLastMsg(g.getId(), toAdd.size(), null);
                notifyJoined(g.getId(), g.getName(), toAdd);
            }
        }

        log.info("group created: id={}, owner={}, members(+{})", g.getId(), ownerId,
                memberIds == null ? 0 : memberIds.size());
        return g.getId();
    }

    public int addMembers(long groupId, long me, List<Long> userIds) {
        // 拉人对所有群成员开放(不只是群主):我是群成员即可。
        // 与踢人/改名/公告/转让/退群区分 —— 那些仍限群主,见 GroupAccessSupport.assertOwner。
        access.assertMember(groupId, me);
        campusAccess.requireCreateGroup(me, "邀请成员");
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        List<Long> valid = userIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        // 群主拉人时群主必在成员里;群成员拉人时排除自己(不能把自己"拉"进自己已在的群)
        List<Long> newOnes = valid.stream()
                .filter(id -> !id.equals(me))
                .filter(id -> groupMemberMapper.isMember(groupId, id) == 0)
                .toList();
        // 只算"确实存在的人",与 createGroup 同口径
        List<Long> toAdd = newOnes.stream()
                .filter(id -> userMapper.selectById(id) != null)
                .toList();
        if (toAdd.isEmpty()) {
            return 0;
        }
        // 2026-10-28:只能邀请关注我/我关注的人。此前任何群成员都能把任意 userId
        // 拉进群且无通知,是「新号建群 → 批量拉陌生人」骚扰链路的最后一环。
        assertInvitables(me, toAdd);
        // 人数上限:当前成员数 + 本次新增 不得超过 groupMaxMembers
        int now = groupMemberMapper.countByGroup(groupId);
        if (now + toAdd.size() > campus.getGroupMaxMembers()) {
            throw new BusinessException(GROUP_FULL, "群成员已满,无法继续拉人");
        }
        for (Long uid : toAdd) {
            insertMember(groupId, uid, 2);
        }
        groupMapper.touchCountAndLastMsg(groupId, toAdd.size(), null);
        Group g = access.requireGroup(groupId);
        notifyJoined(groupId, g.getName(), toAdd);
        log.info("group addMembers: group={} owner={} +{}", groupId, me, toAdd.size());
        return toAdd.size();
    }

    public void kick(long groupId, long me, long userId) {
        access.assertOwner(groupId, me);
        if (userId == me || userId == groupMapper.selectById(groupId).getOwnerId()) {
            // 不能踢群主自己、不能踢自己
            throw new BusinessException(NOT_GROUP_OWNER, "不能移出群主或自己");
        }
        if (groupMemberMapper.isMember(groupId, userId) == 0) {
            return; // 已不在群里,幂等
        }
        groupMemberMapper.hardDeleteByGroupAndUsers(groupId, List.of(userId));
        groupReadMapper.deleteByGroupAndUser(groupId, userId);
        groupMapper.decMemberCount(groupId);
        log.info("group kick: group={} owner={} kicked={}", groupId, me, userId);
    }

    public void transfer(long groupId, long me, long newOwnerId) {
        access.assertOwner(groupId, me);
        if (newOwnerId == me) {
            throw new BusinessException(BAD_REQUEST, "不能转让给自己");
        }
        if (groupMemberMapper.isMember(groupId, newOwnerId) == 0) {
            throw new BusinessException(NOT_GROUP_MEMBER, "目标不是该群成员");
        }
        // 改群主 + 双方角色:目标升 1、我降 2(我不退出,留在群里当普通成员)
        groupMapper.transferOwner(groupId, newOwnerId);
        groupMemberMapper.updateRole(groupId, newOwnerId, 1);
        groupMemberMapper.updateRole(groupId, me, 2);
        log.info("group transfer: group={} owner {} -> {}", groupId, me, newOwnerId);
    }

    public void leaveGroup(long groupId, long me) {
        Group g = access.requireGroup(groupId);
        access.assertMember(groupId, me);
        if (g.getStatus() == 0) {
            return; // 已解散,幂等
        }
        boolean isOwner = g.getOwnerId().equals(me);
        int remaining = groupMemberMapper.countByGroup(groupId) - 1;
        if (!isOwner) {
            groupMemberMapper.hardDeleteByGroupAndUsers(groupId, List.of(me));
            groupReadMapper.deleteByGroupAndUser(groupId, me);
            groupMapper.decMemberCount(groupId);
            log.info("group leave: group={} member={}", groupId, me);
            return;
        }
        // 群主退
        if (remaining >= 1) {
            // 自动转让给"第一个非我成员":memberIds 里去掉我,取第一个
            long nextOwner = groupMemberMapper.memberIds(groupId).stream()
                    .filter(id -> !id.equals(me))
                    .findFirst()
                    .orElseThrow();
            groupMapper.transferOwner(groupId, nextOwner);
            groupMemberMapper.updateRole(groupId, nextOwner, 1);
            groupMemberMapper.hardDeleteByGroupAndUsers(groupId, List.of(me));
            groupReadMapper.deleteByGroupAndUser(groupId, me);
            groupMapper.decMemberCount(groupId);
            log.info("group owner leave -> transfer to {}: group={}", nextOwner, groupId);
        } else {
            // 只剩我:解散,物理清四表 + 软删群行
            List<Long> all = groupMemberMapper.memberIds(groupId);
            groupMemberMapper.hardDeleteByGroupAndUsers(groupId, all);
            groupReadMapper.deleteByGroup(groupId);
            groupMessageMapper.deleteByGroup(groupId);
            groupMapper.deleteById(groupId);
            log.info("group disbanded (only member left): group={}", groupId);
        }
    }

    private void insertMember(long groupId, long userId, int role) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role);
        groupMemberMapper.insert(m);
    }

    /**
     * 拉人关系校验（2026-10-28）。
     *
     * <p>只允许邀请「关注我的人」或「我关注的人」（type 1/2，任一方向）。
     * 与 {@code AddMembersSheet} 的候选口径一致；API 直接传任意 userId 时，
     * 这里是最后一道拦截。拉黑会删掉关注行，所以被拉黑的人也不会通过。
     */
    private void assertInvitables(long inviterId, List<Long> userIds) {
        for (Long uid : userIds) {
            if (relationMapper.countFollowOrFriendEitherDirection(inviterId, uid) == 0) {
                throw new BusinessException(UNPROCESSABLE, "只能邀请关注你或你关注的人");
            }
        }
    }

    /**
     * 新成员入群通知（2026-10-28）：至少让对方知道自己在哪个群、能自己退。
     * 不阻塞入群——通知写失败不该让邀请失败，用户还能从群列表看到群。
     */
    private void notifyJoined(long groupId, String groupName, List<Long> userIds) {
        for (Long uid : userIds) {
            try {
                notifySupport.pushSystemNotify(uid, NOTIFY_GROUP_INVITE, groupId, null,
                        "「" + groupName + "」");
            } catch (Exception e) {
                log.warn("group invite notify fail: group={}, user={}, err={}",
                        groupId, uid, e.getMessage());
            }
        }
    }
}
