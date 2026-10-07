package com.campus.social.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 服务端 → 客户端 WS 推送
 * 消息体统一 {type, payload, ts}
 *
 * 注意:仅当用户在线时才推送;离线消息落 notify / chat_message 表,
 *       用户上线后通过 REST 拉取(本服务不做离线补发,简化)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsPushService {

    private final WsSessionManager sessionManager;
    private final ObjectMapper om;

    /** 推群消息给除发送者外的全体成员(多播) */
    public void pushGroupMessage(java.util.Collection<Long> receiverIds, long groupId, long msgId, String content, long senderId) {
        try {
            String json = om.writeValueAsString(Map.of(
                    "type", "group_msg",
                    "ts", System.currentTimeMillis(),
                    "payload", Map.of(
                            "groupId", groupId,
                            "msgId", msgId,
                            "content", content,
                            "senderId", senderId
                    )
            ));
            sessionManager.broadcastAll(receiverIds, json);
        } catch (Exception e) {
            log.warn("pushGroupMessage fail: group={}, err={}", groupId, e.getMessage());
        }
    }

    /** 推群"消息撤回"给除发送者外的全体成员 */
    public void pushGroupRecalled(java.util.Collection<Long> receiverIds, long groupId, long msgId, long senderId) {
        try {
            String json = om.writeValueAsString(Map.of(
                    "type", "group_recalled",
                    "ts", System.currentTimeMillis(),
                    "payload", Map.of(
                            "groupId", groupId,
                            "msgId", msgId,
                            "senderId", senderId
                    )
            ));
            sessionManager.broadcastAll(receiverIds, json);
        } catch (Exception e) {
            log.warn("pushGroupRecalled fail: group={}, err={}", groupId, e.getMessage());
        }
    }

    /** 推群"已读回执":reader 读到了 sender 发的群消息,sender 端据此把气泡翻成已读 */
    public void pushGroupRead(java.util.Collection<Long> receiverIds, long groupId, long readerId, long msgId) {
        try {
            String json = om.writeValueAsString(Map.of(
                    "type", "group_read",
                    "ts", System.currentTimeMillis(),
                    "payload", Map.of(
                            "groupId", groupId,
                            "readerId", readerId,
                            "msgId", msgId
                    )
            ));
            sessionManager.broadcastAll(receiverIds, json);
        } catch (Exception e) {
            log.warn("pushGroupRead fail: group={}, err={}", groupId, e.getMessage());
        }
    }

    /** 推私信给 receiver */
    public void pushChat(long receiverId, long convId, long msgId, String content, long senderId) {
        try {
            String json = om.writeValueAsString(Map.of(
                    "type", "chat",
                    "ts", System.currentTimeMillis(),
                    "payload", Map.of(
                            "convId", convId,
                            "msgId", msgId,
                            "content", content,
                            "senderId", senderId
                    )
            ));
            sessionManager.broadcast(receiverId, json);
        } catch (Exception e) {
            log.warn("pushChat fail: receiver={}, err={}", receiverId, e.getMessage());
        }
    }

    /** 推"已读回执"给 sender:receiver 读了 sender 发的消息(senderId + 会话),sender 端据此把气泡改成已读 */
    public void pushChatRead(long senderId, long convId, long readerId) {
        try {
            String json = om.writeValueAsString(Map.of(
                    "type", "chat_read",
                    "ts", System.currentTimeMillis(),
                    "payload", Map.of(
                            "convId", convId,
                            "readerId", readerId
                    )
            ));
            sessionManager.broadcast(senderId, json);
        } catch (Exception e) {
            log.warn("pushChatRead fail: sender={}, err={}", senderId, e.getMessage());
        }
    }

    /** 推"消息撤回"给 receiver:sender 撤回了某条消息(receiverId + 会话 + msgId),receiver 端把气泡置灰 */
    public void pushChatRecalled(long receiverId, long convId, long msgId, long senderId) {
        try {
            String json = om.writeValueAsString(Map.of(
                    "type", "chat_recalled",
                    "ts", System.currentTimeMillis(),
                    "payload", Map.of(
                            "convId", convId,
                            "msgId", msgId,
                            "senderId", senderId
                    )
            ));
            sessionManager.broadcast(receiverId, json);
        } catch (Exception e) {
            log.warn("pushChatRecalled fail: receiver={}, err={}", receiverId, e.getMessage());
        }
    }

    /** 推通知给 receiver */
    public void pushNotify(long receiverId, long notifyId, int type, long targetId, long actorId) {
        try {
            String json = om.writeValueAsString(Map.of(
                    "type", "notify",
                    "ts", System.currentTimeMillis(),
                    "payload", Map.of(
                            "notifyId", notifyId,
                            "nType", type,
                            "targetId", targetId,
                            "actorId", actorId
                    )
            ));
            sessionManager.broadcast(receiverId, json);
        } catch (Exception e) {
            log.warn("pushNotify fail: receiver={}, err={}", receiverId, e.getMessage());
        }
    }
}
