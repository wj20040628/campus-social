package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.ChatService;
import com.campus.social.vo.ChatMessageVO;
import com.campus.social.vo.ConversationVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations")
    public Result<CursorPage<ConversationVO>> conversations(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(chatService.conversations(me, lastId, size));
    }

    @GetMapping("/history")
    public Result<CursorPage<ChatMessageVO>> history(
            @RequestParam("peerId") long peerId,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(chatService.history(me, peerId, lastId, size));
    }

    @PostMapping("/message")
    public Result<Map<String, Long>> send(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long to = Long.parseLong(String.valueOf(body.get("toUserId")));
        String content = String.valueOf(body.get("content"));
        // 可选:引用回复(被引消息 id,同会话才允许)
        Long replyToId = null;
        Object rti = body.get("replyToId");
        if (rti != null && !String.valueOf(rti).isBlank()) {
            replyToId = Long.parseLong(String.valueOf(rti));
        }
        long msgId = chatService.send(me, to, content, replyToId);
        return Result.ok(Map.of("msgId", msgId));
    }

    /** 撤回消息:仅发送方、仅 60s 内 */
    @DeleteMapping("/message/{id}")
    public Result<Void> recall(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        chatService.recall(me, id);
        return Result.ok();
    }

    @PostMapping("/read")
    public Result<Void> read(@RequestParam("peerId") long peerId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        chatService.markRead(me, peerId);
        return Result.ok();
    }
}
