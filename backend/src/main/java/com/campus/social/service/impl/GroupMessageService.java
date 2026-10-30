package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;
import com.campus.social.entity.Group;
import com.campus.social.entity.GroupMessage;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.GroupMapper;
import com.campus.social.mapper.GroupMemberMapper;
import com.campus.social.mapper.GroupMessageMapper;
import com.campus.social.mapper.GroupReadMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.support.GroupAccessSupport;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupSendVO;
import com.campus.social.websocket.WsAfterCommit;
import com.campus.social.websocket.WsPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.campus.social.common.ResultCode.BAD_REQUEST;
import static com.campus.social.common.ResultCode.GROUP_MUTED;
import static com.campus.social.common.ResultCode.UNPROCESSABLE;

/**
 * 群消息（2026-10-30 从 GroupServiceImpl 拆出）：
 * 发送/撤回/已读/历史记录。镜像单聊的「水位 + 120s 撤回 + DB 为真相源重拉」约定。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMessageService {

    /** 撤回窗口(秒):与单聊 RECALL_WINDOW_SECONDS 及前端 canRecall 对齐 */
    private static final int RECALL_WINDOW_SECONDS = 120;

    private final GroupAccessSupport access;
    private final GroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final GroupMessageMapper groupMessageMapper;
    private final GroupReadMapper groupReadMapper;
    private final UserMapper userMapper;
    private final WsPushService wsPush;
    private final WsAfterCommit wsAfterCommit;
    /** 敏感词横切(2026-10-24 补):此前群消息发送路径无任何敏感词检查 */
    private final MessageContentGuard contentGuard;

    public CursorPage<GroupMessageVO> groupMessages(long groupId, long me, Long lastId, int size) {
        access.assertMember(groupId, me);
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

    public GroupSendVO sendGroupMessage(long me, long groupId, String content, Long replyToId) {
        access.assertMember(groupId, me);
        // 禁言拦截:群主恒可发言;普通成员被群主禁言(muted=1)则拒绝
        Group g = access.requireGroup(groupId);
        if (!g.getOwnerId().equals(me) && groupMemberMapper.mutedOf(groupId, me) == 1) {
            throw new BusinessException(GROUP_MUTED, "你已被群主禁言,无法发言");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(BAD_REQUEST, "消息不能为空");
        }
        /*
         * 敏感词(2026-10-24 补,此前群消息发送路径零检查——level-2 拒发词都畅通):
         * level-2 拒发并审计,level-1 入库前打码;入库、WS 推送统一用打码后文本。
         * 位置放在成员/禁言校验之后:先确认「有资格发言」,再花 DFA 扫描的代价。
         */
        String safeContent = contentGuard.sanitize(me, "group_chat", "group_message", content);
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
        m.setContent(safeContent);
        m.setStatus(0);
        m.setIsRecalled(0);
        m.setReplyToId(replyToId);
        groupMessageMapper.insert(m);

        // 群最近一条消息水位 + 多播给除我外的全体成员
        // WS 推送延迟到事务提交后:若本方法后续回滚,不推「幽灵消息」
        groupMapper.touchLastMsg(groupId, m.getId());
        List<Long> members = groupMemberMapper.memberIds(groupId);
        List<Long> receivers = members.stream().filter(id -> id != me).toList();
        String wsContent = safeContent;
        wsAfterCommit.run(() -> wsPush.pushGroupMessage(receivers, groupId, m.getId(), wsContent, me));

        log.debug("group msg: {} -> group={} id={}", me, groupId, m.getId());
        // 返回打码后文本:前端乐观气泡渲染它,别让发送者看到自己的原文
        return GroupSendVO.builder().msgId(m.getId()).content(safeContent).build();
    }

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

    public void markGroupRead(long me, long groupId) {
        access.assertMember(groupId, me);
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

    private List<GroupMessage> collectReplyTargets(List<GroupMessage> page) {
        List<Long> ids = page.stream()
                .map(GroupMessage::getReplyToId)
                .filter(Objects::nonNull)
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
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, User> byId = userMapper.selectBatchIds(sids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        Map<Long, String> nick = new java.util.HashMap<>();
        for (User u : byId.values()) {
            // 昵称缺失时回退 username 必须脱敏(审查 P1-22:username=学号,原样返回即泄露)
            nick.put(u.getId(), u.getNickname() != null ? u.getNickname()
                    : com.campus.social.assembler.UserVoAssembler.maskUsername(u.getUsername()));
        }
        return nick;
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
