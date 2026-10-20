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
import com.campus.social.service.ChatGuardService;
import com.campus.social.service.ChatService;
import com.campus.social.vo.ChatMessageVO;
import com.campus.social.vo.ChatSendVO;
import com.campus.social.vo.ConversationVO;
import com.campus.social.vo.UserVO;
import com.campus.social.websocket.WsAfterCommit;
import com.campus.social.websocket.WsPushService;
import com.campus.social.assembler.UserVoAssembler;
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
    private final WsAfterCommit wsAfterCommit;
    private final ChatGuardService chatGuardService;
    private final PostNotifySupport postNotifySupport;

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
    public ChatSendVO send(long me, long toUserId, String content, Long replyToId) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(BAD_REQUEST, "消息不能为空");
        }

        // 护栏前置：认证 / 黑名单 / 接收方开关 / 配额 / 敏感词五道。
        // 放在最前面是因为后面每一步都要写库并推 WS，被护栏拒掉的请求
        // 不该在会话表和消息表留下任何痕迹。
        ChatSendVO guard = chatGuardService.check(me, toUserId, content);

        // 引用回复:被引消息必须存在且同会话(防跨会话引用)
        if (replyToId != null) {
            Long convIdCheck = findConvId(me, toUserId);
            ChatMessage ref = msgMapper.selectById(replyToId);
            if (ref == null || ref.getConvId() == null || !ref.getConvId().equals(convIdCheck)) {
                throw new BusinessException(UNPROCESSABLE, "引用的消息不存在或不在当前会话");
            }
        }

        long a = Math.min(me, toUserId);
        // 先查,查到直接用(避免无谓 upsert)
        Long convId = findConvId(me, toUserId);
        if (convId == null) {
            // 幂等建会话:并发首发也不会双双 insert 撞 uk_conv
            convMapper.upsertConversation(a, Math.max(me, toUserId), null);
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
        // lambda 捕获需 effectively final:取 insert 后的 msgId / convId 副本
        long finalMsgId = m.getId();
        long finalConvId = convId;

        // 私信通知去重:仅当接收方该会话「原本未读为 0」(这条消息开启新一轮未读)才发,
        // 避免连续消息每条都刷一条通知造成轰炸。放在 touch 之前读增量前的值。
        ChatConversation conv = convMapper.selectById(finalConvId);
        int recvUnreadBefore = (a == me)
                ? (conv.getUnreadB() == null ? 0 : conv.getUnreadB())
                : (conv.getUnreadA() == null ? 0 : conv.getUnreadA());
        if (recvUnreadBefore == 0) {
            postNotifySupport.pushNotify(toUserId, 4, finalConvId, me, null);
        }

        // 接收方未读 +1:未读记在"没读的那个人(接收方)"头上,不是发送方
        // me 是发送方:若 me==a,则接收方 toUserId==b → 加 unread_b;反之加 unread_a
        if (a == me) {
            convMapper.touchUnreadB(convId, 1, finalMsgId);
        } else {
            convMapper.touchUnreadA(convId, 1, finalMsgId);
        }

// WS 推给接收方(延迟到事务提交后,防回滚幽灵消息)
        wsAfterCommit.run(() -> wsPush.pushChat(toUserId, finalConvId, finalMsgId, content, me));
        log.debug("chat send: {} -> {} conv={} replyTo={} stranger={}",
                me, toUserId, convId, replyToId, guard.getIsStranger());

        return ChatSendVO.builder()
                .msgId(m.getId())
                .isStranger(guard.getIsStranger())
                .remainingNewPeerQuota(guard.getRemainingNewPeerQuota())
                .build();
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
        // WS 推撤回给 receiver(receiver 端把气泡置灰;延迟到事务提交后,防回滚幽灵消息)
        wsAfterCommit.run(() -> wsPush.pushChatRecalled(m.getReceiverId(), m.getConvId(), m.getId(), me));
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
            // 延迟到事务提交后,防回滚幽灵消息
            wsAfterCommit.run(() -> wsPush.pushChatRead(peerId, convId, me));
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
        UserVO pvo = UserVoAssembler.of(userMapper.selectById(peerId));

        int unread = c.getMemberA().equals(me) ? c.getUnreadA() : c.getUnreadB();

        String preview = "";
        ChatMessage last = null;
        if (c.getLastMsgId() != null) {
            last = msgMapper.selectById(c.getLastMsgId());
            if (last != null && last.getContent() != null) {
                preview = last.getContent().length() > 50
                        ? last.getContent().substring(0, 50) + "…"
                        : last.getContent();
            }
        }

        // 待回应纸条:最后一条消息来自对方(我欠回复)且最后活动在 24h 内。
        // 用发送方方向而非 unread>0:已读但未回的纸条同样算「待回应」,24h 后自动失效。
        boolean pending = false;
        if (c.getLastMsgId() != null && last != null
                && last.getSenderId() != null && !last.getSenderId().equals(me)) {
            pending = c.getUpdateTime() != null
                    && c.getUpdateTime().isAfter(java.time.LocalDateTime.now().minusHours(24));
        }

        return ConversationVO.builder()
                .convId(c.getId())
                .peer(pvo)
                .unread(unread)
                .lastPreview(preview)
                .lastMsgId(c.getLastMsgId())
                .lastMsgTime(c.getUpdateTime())
                .pendingReply(pending)
                .build();
    }

    /** 撤回窗口(秒):与前端 canRecall 对齐。120s 是微信/QQ 同档,8s 等于没有撤回 */
    private static final int RECALL_WINDOW_SECONDS = 120;

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
