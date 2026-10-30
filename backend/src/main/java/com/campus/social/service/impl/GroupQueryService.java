package com.campus.social.service.impl;

import com.campus.social.common.CursorPage;
import com.campus.social.entity.Group;
import com.campus.social.entity.GroupMember;
import com.campus.social.entity.User;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.GroupMemberMapper;
import com.campus.social.mapper.GroupMessageMapper;
import com.campus.social.mapper.GroupReadMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.ChatConvPrefService;
import com.campus.social.service.support.GroupAccessSupport;
import com.campus.social.vo.GroupMemberInfoVO;
import com.campus.social.vo.GroupVO;
import com.campus.social.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.campus.social.assembler.UserVoAssembler;

/**
 * 群读取（2026-10-30 从 GroupServiceImpl 拆出）：群信息头/成员列表、
 * 我的群列表、是否被禁言。群未读记在「没读的一方」（每个成员水位之后、
 * 非我发的条数），而非发送方 —— 与单聊对称。
 */
@Service
@RequiredArgsConstructor
public class GroupQueryService {

    private final GroupAccessSupport access;
    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final GroupReadMapper groupReadMapper;
    private final UserMapper userMapper;
    private final ChatConvPrefService convPrefService;

    public GroupMemberInfoVO members(long groupId, long me) {
        Group g = access.requireGroup(groupId);
        access.assertMember(groupId, me);

        List<Long> userIds = groupMemberMapper.memberIds(groupId);
        Map<Long, User> byId = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        long ownerId = g.getOwnerId();
        // 一次查出全群状态行:批量回填 muted 与 create_time(避免 N+1)
        List<GroupMember> stateRows = groupMemberMapper.memberStateRows(groupId);
        Map<Long, Integer> mutedByUser = stateRows.stream()
                .collect(Collectors.toMap(GroupMember::getUserId,
                        m -> m.getMuted() == null ? 0 : m.getMuted(), (a, b) -> a));
        Map<Long, String> joinDateByUser = stateRows.stream()
                .collect(Collectors.toMap(GroupMember::getUserId,
                        m -> joinDate(m.getCreateTime()), (a, b) -> a));
        List<GroupMemberInfoVO.MemberItem> items = new java.util.ArrayList<>();
        // 群主置顶:先放群主(若还在成员里),再按 user_id 顺序放其余
        items.add(memberItem(ownerId, byId, ownerId, me, 0,
                joinDateByUser.get(ownerId))); // 群主恒可发言,muted=0
        for (Long uid : userIds) {
            if (uid.equals(ownerId)) continue;
            items.add(memberItem(uid, byId, ownerId, me,
                    mutedByUser.getOrDefault(uid, 0), joinDateByUser.get(uid)));
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

    public CursorPage<GroupVO> myGroups(long me, Long lastId, int size) {
        List<Long> groupIds = groupMemberMapper.groupIdsByUser(me);
        if (groupIds.isEmpty()) {
            return CursorPage.empty();
        }
        Map<Long, com.campus.social.entity.ChatConvPref> prefs =
                convPrefService.loadByKind(me, com.campus.social.entity.ChatConvPref.KIND_GROUP);
        // 过滤 + 置顶排序都在切页之前(同 ChatServiceImpl.conversations 的理由)
        List<Group> rows = groupMapper.selectBatchIds(groupIds).stream()
                .filter(g -> g.getStatus() != null && g.getStatus() == 1)
                .filter(g -> visibleUnderPref(g, prefs.get(g.getId())))
                .sorted(java.util.Comparator
                        .comparingInt((Group g) -> pinnedOf(prefs.get(g.getId())) ? 0 : 1)
                        .thenComparing((a, b) -> Long.compare(b.getId(), a.getId()))) // 群 id 倒序≈最近创建在前
                .toList();
        boolean hasMore = rows.size() > size;
        List<Group> page = rows.subList(0, Math.min(size, rows.size()));

        List<GroupVO> vos = page.stream().map(g -> toGroupVo(g, me, prefs.get(g.getId()))).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

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

    /** 单个成员项:User 不存在(极端并发删号)则跳过由调用方兜底,这里只取已有 */
    private GroupMemberInfoVO.MemberItem memberItem(long userId, Map<Long, User> byId, long ownerId,
                                                    long me, int muted, String joinDate) {
        boolean isOwner = userId == ownerId;
        return GroupMemberInfoVO.MemberItem.builder()
                .user(UserVoAssembler.of(byId.get(userId)))
                .isOwner(isOwner)
                .isSelf(userId == me)
                .muted(muted == 1)
                .joinDate(joinDate)
                .build();
    }

    /** create_time -> yyyy-MM-dd;null(历史行没填)返回 null,前端那头就不显示日期 */
    private static String joinDate(LocalDateTime t) {
        return t == null ? null : t.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /** 群是否该出现在我的列表里：删除水位之后有新消息就自动回来 */
    private boolean visibleUnderPref(Group g, com.campus.social.entity.ChatConvPref pref) {
        if (pref == null || pref.getHidden() == null || pref.getHidden() == 0) {
            return true;
        }
        long last = g.getLastMsgId() == null ? 0L : g.getLastMsgId();
        long upto = pref.getHiddenUpto() == null ? 0L : pref.getHiddenUpto();
        return last > upto;
    }

    private boolean pinnedOf(com.campus.social.entity.ChatConvPref pref) {
        return pref != null && pref.getPinned() != null && pref.getPinned() == 1;
    }

    private GroupVO toGroupVo(Group g, long me, com.campus.social.entity.ChatConvPref pref) {
        // 我侧未读 = 我读到的水位之后、非我发的消息数
        long water = groupReadMapper.lastRead(g.getId(), me);
        int unread = groupMessageMapper.unreadCountAfter(g.getId(), me, water);

        UserVO ovo = UserVoAssembler.of(userMapper.selectById(g.getOwnerId()));

        // 最近一条消息摘要
        String preview = "";
        if (g.getLastMsgId() != null) {
            var last = groupMessageMapper.selectById(g.getLastMsgId());
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
                .pinned(pinnedOf(pref))
                .muted(pref != null && pref.getMuted() != null && pref.getMuted() == 1)
                .build();
    }
}
