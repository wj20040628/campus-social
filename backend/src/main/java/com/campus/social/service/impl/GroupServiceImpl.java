package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.config.CampusProperties;
import com.campus.social.entity.*;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.*;
import com.campus.social.service.GroupService;
import com.campus.social.service.UploadService;
import com.campus.social.vo.GroupMemberInfoVO;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupVO;
import com.campus.social.vo.UserVO;
import com.campus.social.websocket.WsAfterCommit;
import com.campus.social.websocket.WsPushService;
import com.campus.social.assembler.UserVoAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.campus.social.common.ResultCode.*;

/**
 * 群聊实现:镜像单聊 ChatServiceImpl 的"水位 + 8s 撤回 + DB 为真相源重拉"约定。
 * 群未读记在【没读的一方】(每个成员的水位后、非我发的条数),而非发送方 —— 对称口径(铁律 C3)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupServiceImpl implements GroupService {

    /** 撤回窗口(秒):与单聊 RECALL_WINDOW_SECONDS 及前端 canRecall 对齐 */
    private static final int RECALL_WINDOW_SECONDS = 120;

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final GroupReadMapper groupReadMapper;
    private final UserMapper userMapper;
    private final WsPushService wsPush;
    private final WsAfterCommit wsAfterCommit;
    private final CampusProperties campus;
    /** 群头像归属校验:引用 /uploads 文件必须确属建群人上传 */
    private final UploadService uploadService;

    @Override
    @Transactional
    public long createGroup(long ownerId, String name, String avatarUrl, List<Long> memberIds) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(GROUP_NAME_EMPTY, "群名称不能为空");
        }
        User owner = userMapper.selectById(ownerId);
        if (owner == null || owner.getStatus() == 1) {
            throw new BusinessException(USER_CANCELLED);
        }

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
            }
        }

        log.info("group created: id={}, owner={}, members(+{})", g.getId(), ownerId,
                memberIds == null ? 0 : memberIds.size());
        return g.getId();
    }

    private void insertMember(long groupId, long userId, int role) {
        GroupMember m = new GroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role);
        groupMemberMapper.insert(m);
    }

    // ---------------------------------------------------------------- 群管理(群信息头)

    @Override
    public GroupMemberInfoVO members(long groupId, long me) {
        Group g = requireGroup(groupId);
        assertMember(groupId, me);

        List<Long> userIds = groupMemberMapper.memberIds(groupId);
        Map<Long, User> byId = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        long ownerId = g.getOwnerId();
        // 批量回填每个成员的 muted(避免 N+1):user_id -> muted(0/1)
        Map<Long, Integer> mutedByUser = groupMemberMapper.memberMutedRows(groupId).stream()
                .collect(Collectors.toMap(GroupMember::getUserId,
                        m -> m.getMuted() == null ? 0 : m.getMuted(), (a, b) -> a));
        List<GroupMemberInfoVO.MemberItem> items = new java.util.ArrayList<>();
        // 群主置顶:先放群主(若还在成员里),再按 user_id 顺序放其余
        items.add(memberItem(ownerId, byId, ownerId, me, 0)); // 群主恒可发言,muted=0
        for (Long uid : userIds) {
            if (uid.equals(ownerId)) continue;
            items.add(memberItem(uid, byId, ownerId, me,
                    mutedByUser.getOrDefault(uid, 0)));
        }

        return GroupMemberInfoVO.builder()
                .groupId(groupId)
                .name(g.getName())
                .ownerId(ownerId)
                .announcement(g.getAnnouncement())
                .memberCount(items.size())
                .members(items)
                .build();
    }

    /** 单个成员项:User 不存在(极端并发删号)则跳过由调用方兜底,这里只取已有 */
    private GroupMemberInfoVO.MemberItem memberItem(long userId, Map<Long, User> byId, long ownerId, long me, int muted) {
        boolean isOwner = userId == ownerId;
        return GroupMemberInfoVO.MemberItem.builder()
                .user(UserVoAssembler.of(byId.get(userId)))
                .isOwner(isOwner)
                .isSelf(userId == me)
                .muted(muted == 1)
                .build();
    }

    @Override
    @Transactional
    public int addMembers(long groupId, long me, List<Long> userIds) {
        // 拉人对所有群成员开放(不只是群主):我是群成员即可。
        // 与踢人/改名/公告/转让/退群区分 —— 那些仍限群主,见 assertOwner。
        assertMember(groupId, me);
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
        // 人数上限:当前成员数 + 本次新增 不得超过 groupMaxMembers
        int now = groupMemberMapper.countByGroup(groupId);
        if (now + toAdd.size() > campus.getGroupMaxMembers()) {
            throw new BusinessException(GROUP_FULL, "群成员已满,无法继续拉人");
        }
        for (Long uid : toAdd) {
            insertMember(groupId, uid, 2);
        }
        groupMapper.touchCountAndLastMsg(groupId, toAdd.size(), null);
        log.info("group addMembers: group={} owner={} +{}", groupId, me, toAdd.size());
        return toAdd.size();
    }

    @Override
    @Transactional
    public void kick(long groupId, long me, long userId) {
        assertOwner(groupId, me);
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

    @Override
    @Transactional
    public void rename(long groupId, long me, String name) {
        assertOwner(groupId, me);
        if (name == null || name.isBlank()) {
            throw new BusinessException(GROUP_NAME_EMPTY, "群名称不能为空");
        }
        Group upd = new Group();
        upd.setId(groupId);
        upd.setName(name.trim());
        groupMapper.updateById(upd);
        log.info("group rename: group={} owner={}", groupId, me);
    }

    @Override
    @Transactional
    public void setAnnouncement(long groupId, long me, String text) {
        assertOwner(groupId, me);
        Group upd = new Group();
        upd.setId(groupId);
        upd.setAnnouncement(text == null ? null : text.trim());
        groupMapper.updateById(upd);
        log.info("group announcement: group={} owner={} cleared={}", groupId, me, text == null || text.isBlank());
    }

    @Override
    @Transactional
    public void transfer(long groupId, long me, long newOwnerId) {
        assertOwner(groupId, me);
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

    @Override
    @Transactional
    public void leaveGroup(long groupId, long me) {
        Group g = requireGroup(groupId);
        assertMember(groupId, me);
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

    // ---------------------------------------------------------------- 群禁言(群主)

    @Override
    @Transactional
    public void mute(long groupId, long me, long userId, boolean muted) {
        Group g = assertOwner(groupId, me);
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

    @Override
    @Transactional
    public void muteAll(long groupId, long me, boolean muted) {
        Group g = assertOwner(groupId, me);
        int n = groupMemberMapper.setAllMuted(groupId, g.getOwnerId(), muted ? 1 : 0);
        log.info("group muteAll: group={} owner={} muted={} rows={}", groupId, me, muted, n);
    }

    @Override
    public boolean isMuted(long groupId, long me) {
        // 非成员则 false(发消息入口已有 assertMember 前置,这里仅作兜底判断)
        if (groupMemberMapper.isMember(groupId, me) == 0) {
            return false;
        }
        // 群主恒可发言,口径与 sendGroupMessage 的 `!owner.equals(me)` 一致:
        // 即使 DB 里群主行被误设 muted=1,前端输入框也不该禁用。
        Group g = groupMapper.selectById(groupId);
        if (g != null && g.getOwnerId().equals(me)) {
            return false;
        }
        Integer m = groupMemberMapper.mutedOf(groupId, me);
        return m != null && m == 1;
    }

    /** 取群行(存在性 + 群主信息),已解散/不存在则 NOT_FOUND */
    private Group requireGroup(long groupId) {
        Group g = groupMapper.selectById(groupId);
        if (g == null || g.getStatus() == 0) {
            throw new BusinessException(NOT_FOUND, "群不存在或已解散");
        }
        return g;
    }

    /** 校验我是群主,返回群行 */
    private Group assertOwner(long groupId, long me) {
        Group g = requireGroup(groupId);
        if (!g.getOwnerId().equals(me)) {
            throw new BusinessException(NOT_GROUP_OWNER, "仅群主可操作");
        }
        return g;
    }

    @Override
    public CursorPage<GroupVO> myGroups(long me, Long lastId, int size) {
        List<Long> groupIds = groupMemberMapper.groupIdsByUser(me);
        if (groupIds.isEmpty()) {
            return CursorPage.empty();
        }
        List<Group> rows = groupMapper.selectBatchIds(groupIds).stream()
                .filter(g -> g.getStatus() != null && g.getStatus() == 1)
                .sorted((a, b) -> Long.compare(b.getId(), a.getId())) // 群 id 倒序≈最近创建在前
                .toList();
        boolean hasMore = rows.size() > size;
        List<Group> page = rows.subList(0, Math.min(size, rows.size()));

        List<GroupVO> vos = page.stream().map(g -> toGroupVo(g, me)).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public CursorPage<GroupMessageVO> groupMessages(long groupId, long me, Long lastId, int size) {
        assertMember(groupId, me);
        LambdaQueryWrapper<GroupMessage> qw = new LambdaQueryWrapper<GroupMessage>()
                .eq(GroupMessage::getGroupId, groupId)
                .lt(lastId != null, GroupMessage::getId, lastId)
                .orderByDesc(GroupMessage::getId)
                .last("LIMIT " + (size + 1));
        List<GroupMessage> rows = groupMessageMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<GroupMessage> page = new java.util.ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        Collections.reverse(page); // 时间正序

        // 批量回填被引消息片段 + 被引发送者昵称(避免 N+1)
        List<GroupMessage> replyTargets = collectReplyTargets(page);
        Map<Long, GroupMessage> replyById = replyTargets.stream()
                .collect(Collectors.toMap(GroupMessage::getId, x -> x, (a, b) -> a));
        Map<Long, String> senderNick = batchSenderNicknames(page, replyTargets);

        List<GroupMessageVO> vos = page.stream()
                .map(m -> toMsgVo(m, me, replyById, senderNick))
                .toList();
        Long nextCursor = hasMore ? page.get(0).getId() : null; // 最早的为下一页游标
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    private List<GroupMessage> collectReplyTargets(List<GroupMessage> page) {
        List<Long> ids = page.stream()
                .map(GroupMessage::getReplyToId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return groupMessageMapper.selectReplyTargets(ids);
    }

    /** 被引发送者昵称批量查(群内要带名字,合并本批 + 被引批的 sender_id) */
    private Map<Long, String> batchSenderNicknames(List<GroupMessage> page, List<GroupMessage> replyTargets) {
        List<Long> sids = java.util.stream.Stream.concat(
                        page.stream().map(GroupMessage::getSenderId),
                        replyTargets.stream().map(GroupMessage::getSenderId))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (sids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, User> byId = userMapper.selectBatchIds(sids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        Map<Long, String> nick = new java.util.HashMap<>();
        for (User u : byId.values()) {
            nick.put(u.getId(), u.getNickname() != null ? u.getNickname() : u.getUsername());
        }
        return nick;
    }

    @Override
    @Transactional
    public long sendGroupMessage(long me, long groupId, String content, Long replyToId) {
        assertMember(groupId, me);
        // 禁言拦截:群主恒可发言;普通成员被群主禁言(muted=1)则拒绝
        Group g = requireGroup(groupId);
        if (!g.getOwnerId().equals(me) && groupMemberMapper.mutedOf(groupId, me) == 1) {
            throw new BusinessException(GROUP_MUTED, "你已被群主禁言,无法发言");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(BAD_REQUEST, "消息不能为空");
        }
        // 引用回复:被引消息必须存在且同群
        if (replyToId != null) {
            GroupMessage ref = groupMessageMapper.selectById(replyToId);
            if (ref == null || !ref.getGroupId().equals(groupId)) {
                throw new BusinessException(UNPROCESSABLE, "引用的消息不存在或不在当前群");
            }
        }

        GroupMessage m = new GroupMessage();
        m.setGroupId(groupId);
        m.setSenderId(me);
        m.setContent(content);
        m.setStatus(0);
        m.setIsRecalled(0);
        m.setReplyToId(replyToId);
        groupMessageMapper.insert(m);

        // 群最近一条消息水位 + 多播给除我外的全体成员
        // WS 推送延迟到事务提交后:若本方法后续回滚,不推「幽灵消息」
        groupMapper.touchLastMsg(groupId, m.getId());
        List<Long> members = groupMemberMapper.memberIds(groupId);
        List<Long> receivers = members.stream().filter(id -> id != me).toList();
        wsAfterCommit.run(() -> wsPush.pushGroupMessage(receivers, groupId, m.getId(), content, me));

        log.debug("group msg: {} -> group={} id={}", me, groupId, m.getId());
        return m.getId();
    }

    @Override
    @Transactional
    public void recallGroupMessage(long me, long msgId) {
        GroupMessage m = groupMessageMapper.selectById(msgId);
        if (m == null) {
            return; // 幂等
        }
        if (!m.getSenderId().equals(me)) {
            throw new BusinessException(UNPROCESSABLE, "只能撤回自己发的消息");
        }
        if (m.getIsRecalled() == 1) {
            return; // 已撤回,幂等
        }
        Duration elapsed = Duration.between(m.getCreateTime(), java.time.LocalDateTime.now());
        if (elapsed.getSeconds() > RECALL_WINDOW_SECONDS) {
            throw new BusinessException(UNPROCESSABLE, "消息超过 " + RECALL_WINDOW_SECONDS + " 秒,无法撤回");
        }
        groupMessageMapper.markRecalled(msgId);
        // WS 推撤回给除我外的全体成员(延迟到事务提交后,防回滚幽灵消息)
        List<Long> members = groupMemberMapper.memberIds(m.getGroupId());
        List<Long> receivers = members.stream().filter(id -> id != me).toList();
        wsAfterCommit.run(() -> wsPush.pushGroupRecalled(receivers, m.getGroupId(), msgId, me));
        log.info("group msg recalled: group={}, msg={}, sender={}", m.getGroupId(), msgId, me);
    }

    @Override
    @Transactional
    public void markGroupRead(long me, long groupId) {
        assertMember(groupId, me);
        // 更新我的水位到群最新一条消息 id(没新消息则水位不动,幂等)
        Long last = groupMessageMapper.lastMsgId(groupId);
        if (last == null || last == 0L) {
            return; // 群里还没消息
        }
        long myWater = groupReadMapper.lastRead(groupId, me);
        if (last <= myWater) {
            return; // 已读到最新,幂等
        }
        groupReadMapper.upsertWater(groupId, me, last);

        // 已读回执:把我"发给群里、其他人还没读"的那条消息,回执给该消息发送者
        // 简化:回执给这条 last 消息的发送者(若不是我发的)
        GroupMessage lastMsg = groupMessageMapper.selectById(last);
        if (lastMsg != null && !lastMsg.getSenderId().equals(me)) {
            wsAfterCommit.run(() -> wsPush.pushGroupRead(List.of(lastMsg.getSenderId()), groupId, me, last));
        }
        log.debug("group read: me={} group={} water={}", me, groupId, last);
    }

    // ---------------------------------------------------------------- 私有

    private void assertMember(long groupId, long me) {
        if (groupMemberMapper.isMember(groupId, me) == 0) {
            throw new BusinessException(NOT_GROUP_MEMBER, "你不是该群成员");
        }
    }

    private GroupVO toGroupVo(Group g, long me) {
        // 我侧未读 = 我读到的水位之后、非我发的消息数
        long water = groupReadMapper.lastRead(g.getId(), me);
        int unread = groupMessageMapper.unreadCountAfter(g.getId(), me, water);

        UserVO ovo = UserVoAssembler.of(userMapper.selectById(g.getOwnerId()));

        // 最近一条消息摘要
        String preview = "";
        if (g.getLastMsgId() != null) {
            GroupMessage last = groupMessageMapper.selectById(g.getLastMsgId());
            if (last != null && last.getIsRecalled() != 1 && last.getContent() != null) {
                preview = last.getContent().length() > 50
                        ? last.getContent().substring(0, 50) + "…"
                        : last.getContent();
            }
        }

        return GroupVO.builder()
                .id(g.getId())
                .name(g.getName())
                .avatarUrl(g.getAvatarUrl())
                .ownerId(g.getOwnerId())
                .owner(ovo)
                .memberCount(g.getMemberCount() == null ? 1 : g.getMemberCount())
                .announcement(g.getAnnouncement())
                .myUnread(unread)
                .lastMsgId(g.getLastMsgId())
                .lastPreview(preview)
                .lastMsgTime(g.getUpdateTime())
                .build();
    }

    private GroupMessageVO toMsgVo(GroupMessage m, long me,
                                   Map<Long, GroupMessage> replyById, Map<Long, String> senderNick) {
        boolean recalled = m.getIsRecalled() != null && m.getIsRecalled() == 1;
        String replyToContent = null;
        String replyToNickname = null;
        if (m.getReplyToId() != null) {
            GroupMessage ref = replyById.get(m.getReplyToId());
            if (ref != null) {
                boolean refRecalled = ref.getIsRecalled() != null && ref.getIsRecalled() == 1;
                replyToContent = refRecalled ? "(已撤回)"
                        : ref.getContent().length() > 30 ? ref.getContent().substring(0, 30) + "…" : ref.getContent();
                replyToNickname = senderNick.getOrDefault(ref.getSenderId(), "未知用户");
            }
        }
        return GroupMessageVO.builder()
                .id(m.getId())
                .groupId(m.getGroupId())
                .senderId(m.getSenderId())
                // 已撤回:content 出参置空(前端只显示"这条消息已撤回"),防刷新后旧文案"复活"
                .content(recalled ? "" : m.getContent())
                .status(recalled ? null : m.getStatus())
                .recalled(recalled)
                .replyToId(recalled ? null : m.getReplyToId())
                .replyToContent(recalled ? null : replyToContent)
                .replyToNickname(recalled ? null : replyToNickname)
                .createTime(m.getCreateTime())
                .fromMe(m.getSenderId().equals(me))
                .build();
    }
}
