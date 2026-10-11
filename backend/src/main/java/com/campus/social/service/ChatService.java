package com.campus.social.service;

import com.campus.social.common.CursorPage;
import com.campus.social.vo.ChatMessageVO;
import com.campus.social.vo.ChatSendVO;
import com.campus.social.vo.ConversationVO;

public interface ChatService {

    /** 我的会话列表(含未读数) */
    CursorPage<ConversationVO> conversations(long me, Long lastId, int size);

    /** 历史消息(与某 peer) */
    CursorPage<ChatMessageVO> history(long me, long peerId, Long lastId, int size);

    /**
     * 发消息（F3 免好友搭话，含三重护栏）：
     * 1. 护栏校验（黑名单双向 / 日配额 / 敏感词，见 {@code ChatGuardService}）
     * 2. upsert conversation(归一 a<b)
     * 3. INSERT chat_message(status=0, replyToId 可选)
     * 4. conv.unread_x +1
     * 5. WS 推给 receiver
     * 非幂等（每次新消息）
     *
     * @return 发送回执：消息 id + 本次是否陌生人搭话 + 剩余新对象额度
     */
    ChatSendVO send(long me, long toUserId, String content, Long replyToId);

    /** 标记与某 peer 的会话已读:unread=0 + WS ack */
    void markRead(long me, long peerId);

    /**
     * 撤回消息:仅发送方、仅 120s 内、仅未撤回。
     * 置 is_recalled=1 + WS 推 chat_recalled 给 receiver(receiver 端置灰)。
     * 幂等:已撤回直接返(不报错),重复撤不影响。
     */
    void recall(long me, long msgId);
}
