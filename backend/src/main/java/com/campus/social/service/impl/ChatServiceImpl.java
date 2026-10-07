package com.campus.social.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.common.CursorPage;

import com.campus.social.entity.ChatConversation;
import com.campus.social.entity.ChatMessage;
import com.campus.social.entity.User;
import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.ChatConversationMapper;
import com.campus.social.mapper.ChatMessageMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.ChatService;
import com.campus.social.vo.ChatMessageVO;
import com.campus.social.vo.ConversationVO;
import com.campus.social.vo.UserVO;
import com.campus.social.websocket.WsPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

import static com.campus.social.common.ResultCode.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatConversationMapper convMapper;
    private final ChatMessageMapper msgMapper;
    private final UserMapper userMapper;
    private final WsPushService wsPush;

    @Override
    public CursorPage<ConversationVO> conversations(long me, Long lastId, int size) {
        // 全量拉,内存分页(校园场景会话数小;后续量大可换游标)
        List<ChatConversation> rows = convMapper.selectByUser(me);
        boolean hasMore = rows.size() > size;
        List<ChatConversation> page = rows.stream().limit(size).toList();
        List<ConversationVO> vos = page.stream().map(c -> toConvVo(c, me)).toList();
        Long nextCursor = hasMore ? page.get(size - 1).getId() : null;
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    @Override
    public CursorPage<ChatMessageVO> history(long me, long peerId, Long lastId, int size) {
        Long convId = findConvId(me, peerId);
        if (convId == null) {
            return CursorPage.empty();
        }
        LambdaQueryWrapper<ChatMessage> qw = new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConvId, convId)
                .lt(lastId != null, ChatMessage::getId, lastId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + (size + 1));
        List<ChatMessage> rows = msgMapper.selectList(qw);
        boolean hasMore = rows.size() > size;
        List<ChatMessage> page = new java.util.ArrayList<>(rows.subList(0, Math.min(size, rows.size())));
        Collections.reverse(page); // 时间正序

        // 批量回填被引消息片段(避免逐条查 N+1)
        List<ChatMessage> replyTargets = collectReplyTargets(page);
        java.util.Map<Long, ChatMessage> replyById = replyTargets.stream()
                .collect(java.util.stream.Collectors.toMap(ChatMessage::getId, x -> x, (a, b) -> a));
        List<ChatMessageVO> vos = page.stream()
                .map(m -> toMsgVo(m, me, replyById))
                .toList();
        Long nextCursor = hasMore ? page.get(0).getId() : null; // 最早的为下一页游标
        return new CursorPage<>(vos, nextCursor, hasMore);
    }

    /** 取一批消息里所有被引消息的原文(批量查,避免 N+1) */
    private List<ChatMessage> collectReplyTargets(List<ChatMessage> page) {
        List<Long> ids = page.stream()
                .map(ChatMessage::getReplyToId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return msgMapper.selectBatchIds(ids);
    }

    @Override
    @Transactional
    public long send(long me, long toUserId, String content, Long replyToId) {
        if (me == toUserId) {
            throw new BusinessException(CHAT_SELF, "不能给自己发消息");
        }
        User peer = userMapper.selectById(toUserId);
        if (peer == null) {
            throw new BusinessException(NOT_FOUND, "对方不存在");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(BAD_REQUEST, "消息不能为空");
        }
        // 引用回复:被引消息必须存在且同会话(防跨会话引用)
        if (replyToId != null) {
            Long convIdCheck = findConvId(me, toUserId);
            ChatMessage ref = msgMapper.selectById(replyToId);
            if (ref == null || ref.getConvId() == null || !ref.getConvId().equals(convIdCheck)) {
                throw new BusinessException(UNPROCESSABLE, "引用的消息不存在或不在当前会话");
            }
        }

        long a = Math.min(me, toUserId);
        long b = Math.max(me, toUserId);
        // 先查,查到直接用(避免无谓 upsert)
        Long convId = findConvId(me, toUserId);
        if (convId == null) {
            // 幂等建会话:并发首发也不会双双 insert 撞 uk_conv
            convMapper.upsertConversation(a, b, null);
            convId = convMapper.lastConvId();
        }

        ChatMessage m = new ChatMessage();
        m.setConvId(convId);
        m.setSenderId(me);
        m.setReceiverId(toUserId);
        m.setContent(content);
        m.setStatus(0);
        m.setIsRecalled(0);
        m.setReplyToId(replyToId);
        msgMapper.insert(m);

        // 接收方未读 +1:未读记在"没读的那个人(接收方)"头上,不是发送方
        // me 是发送方:若 me==a,则接收方 toUserId==b → 加 unread_b;反之加 unread_a
        if (a == me) {
            convMapper.touchUnreadB(convId, 1, m.getId());
        } else {
            convMapper.touchUnreadA(convId, 1, m.getId());
        }

        // WS 推给接收方
        wsPush.pushChat(toUserId, convId, m.getId(), content, me);
        log.debug("chat send: {} -> {} conv={} replyTo={}", me, toUserId, convId, replyToId);
        return m.getId();
    }

    @Override
    @Transactional
    public void recall(long me, long msgId) {
        ChatMessage m = msgMapper.selectById(msgId);
        if (m == null) {
            return; // 不存在,幂等
        }
        if (!m.getSenderId().equals(me)) {
            throw new BusinessException(UNPROCESSABLE, "只能撤回自己发的消息");
        }
        if (m.getIsRecalled() == 1) {
            return; // 已撤回,幂等
        }
        // 8s 内才可撤(服务端为准,前端按钮显示同步 8s)
        java.time.Duration elapsed = java.time.Duration.between(m.getCreateTime(), java.time.LocalDateTime.now());
        if (elapsed.getSeconds() > RECALL_WINDOW_SECONDS) {
            throw new BusinessException(UNPROCESSABLE, "消息超过 " + RECALL_WINDOW_SECONDS + " 秒,无法撤回");
        }
        m.setIsRecalled(1);
        msgMapper.updateById(m);
        // WS 推撤回给 receiver(receiver 端把气泡置灰)
        wsPush.pushChatRecalled(m.getReceiverId(), m.getConvId(), m.getId(), me);
        log.info("chat recalled: msg={}, sender={}", msgId, me);
    }

    @Override
    @Transactional
    public void markRead(long me, long peerId) {
        Long convId = findConvId(me, peerId);
        if (convId == null) {
            return;
        }
        long a = Math.min(me, peerId);
        // 清"我"这侧的未读:me 是 a → 清 unread_a;me 是 b → 清 unread_b
        if (a == me) {
            convMapper.clearUnreadA(convId);
        } else {
            convMapper.clearUnreadB(convId);
        }
        // 消息级已读:把"对方发给我、我还没读"的消息标记已读(对方据此看到"已读")
        List<Long> toRead = msgMapper.selectUnreadIdsBySender(convId, peerId);
        if (!toRead.isEmpty()) {
            msgMapper.markSenderMessagesRead(toRead);
            // 在线则实时推已读回执给 peer;不在线的,下次它拉 history 时 status 已是 1
            wsPush.pushChatRead(peerId, convId, me);
        }
    }

    // ---------------------------------------------------------------- 私有

    private Long findConvId(long me, long peerId) {
        long a = Math.min(me, peerId);
        long b = Math.max(me, peerId);
        LambdaQueryWrapper<ChatConversation> qw = new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getMemberA, a)
                .eq(ChatConversation::getMemberB, b);
        List<ChatConversation> list = convMapper.selectList(qw);
        return list.isEmpty() ? null : list.get(0).getId();
    }

    private ConversationVO toConvVo(ChatConversation c, long me) {
        long peerId = c.getMemberA().equals(me) ? c.getMemberB() : c.getMemberA();
        User peer = userMapper.selectById(peerId);
        UserVO pvo = peer == null ? null
                : UserVO.builder()
                        .id(peer.getId())
                        .username(peer.getUsername())
                        .nickname(peer.getNickname() != null ? peer.getNickname() : peer.getUsername())
                        .avatarUrl(peer.getAvatarUrl())
                        .build();

        int unread = c.getMemberA().equals(me) ? c.getUnreadA() : c.getUnreadB();

        String preview = "";
        if (c.getLastMsgId() != null) {
            ChatMessage last = msgMapper.selectById(c.getLastMsgId());
            if (last != null && last.getContent() != null) {
                preview = last.getContent().length() > 50
                        ? last.getContent().substring(0, 50) + "…"
                        : last.getContent();
            }
        }

        return ConversationVO.builder()
                .convId(c.getId())
                .peer(pvo)
                .unread(unread)
                .lastPreview(preview)
                .lastMsgId(c.getLastMsgId())
                .lastMsgTime(c.getUpdateTime())
                .build();
    }

    /** 撤回窗口(秒):与前端 canRecall 的 8s 对齐 */
    private static final int RECALL_WINDOW_SECONDS = 8;

    private ChatMessageVO toMsgVo(ChatMessage m, long me, java.util.Map<Long, ChatMessage> replyById) {
        boolean recalled = m.getIsRecalled() != null && m.getIsRecalled() == 1;
        String replyToContent = null;
        if (m.getReplyToId() != null) {
            ChatMessage ref = replyById.get(m.getReplyToId());
            if (ref != null) {
                boolean refRecalled = ref.getIsRecalled() != null && ref.getIsRecalled() == 1;
                replyToContent = refRecalled ? "(已撤回)"
                        : ref.getContent().length() > 30 ? ref.getContent().substring(0, 30) + "…" : ref.getContent();
            }
        }
        return ChatMessageVO.builder()
                .id(m.getId())
                .convId(m.getConvId())
                .senderId(m.getSenderId())
                .receiverId(m.getReceiverId())
                // 已撤回:content 出参置空(前端只显示"这条消息已撤回"),防刷新后旧文案"复活"
                .content(recalled ? "" : m.getContent())
                .status(recalled ? null : m.getStatus())
                .recalled(recalled)
                .replyToId(recalled ? null : m.getReplyToId())
                .replyToContent(recalled ? null : replyToContent)
                .createTime(m.getCreateTime())
                .fromMe(m.getSenderId().equals(me))
                .build();
    }
}
