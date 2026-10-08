package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.entity.*;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.*;
import com.campus.social.service.GroupService;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupVO;
import com.campus.social.vo.UserVO;
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

    /** 撤回窗口(秒):与单聊 RECALL_WINDOW_SECONDS 及前端 canRecall 的 8s 对齐 */
    private static final int RECALL_WINDOW_SECONDS = 8;

    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final GroupReadMapper groupReadMapper;
    private final UserMapper userMapper;
    private final WsPushService wsPush;

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
            int added = 0;
            for (Long uid : valid) {
                if (userMapper.selectById(uid) == null) continue; // 不存在的人跳过
                insertMember(g.getId(), uid, 2);
                added++;
            }
            if (added > 0) {
                groupMapper.touchCountAndLastMsg(g.getId(), added, null);
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
        groupMapper.touchLastMsg(groupId, m.getId());
        List<Long> members = groupMemberMapper.memberIds(groupId);
        List<Long> receivers = members.stream().filter(id -> id != me).toList();
        wsPush.pushGroupMessage(receivers, groupId, m.getId(), content, me);

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
        // WS 推撤回给除我外的全体成员
        List<Long> members = groupMemberMapper.memberIds(m.getGroupId());
        List<Long> receivers = members.stream().filter(id -> id != me).toList();
        wsPush.pushGroupRecalled(receivers, m.getGroupId(), msgId, me);
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
            wsPush.pushGroupRead(List.of(lastMsg.getSenderId()), groupId, me, last);
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
