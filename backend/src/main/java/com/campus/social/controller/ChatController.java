package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.ChatSendDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.ChatService;
import com.campus.social.service.StrangerQuotaService;
import com.campus.social.vo.ChatMessageVO;
import com.campus.social.vo.ChatSendVO;
import com.campus.social.vo.ConversationVO;
import com.campus.social.vo.StrangerQuotaVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final StrangerQuotaService quotaService;

    public ChatController(ChatService chatService, StrangerQuotaService quotaService) {
        this.chatService = chatService;
        this.quotaService = quotaService;
    }

    @GetMapping("/conversations")
    public Result<CursorPage<ConversationVO>> conversations(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(chatService.conversations(me, lastId, PageSize.clamp(size)));
    }

    @GetMapping("/history")
    public Result<CursorPage<ChatMessageVO>> history(
            @RequestParam("peerId") long peerId,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(chatService.history(me, peerId, lastId, PageSize.clamp(size)));
    }

    /**
     * 发送消息（F3 免好友搭话，含三重护栏）。
     *
     * <p>openapi.yaml:269 把路径定为 {@code /chat/send}，但现有前端
     * {@code frontend/src/api/chat.js} 打的是 {@code /chat/message}。
     * 两条路径同时保留并指向同一实现：等前端切换完成再摘掉旧路径，
     * 否则这一轮发版会直接把发消息功能打挂。
     */
    @PostMapping({"/send", "/message"})
    public Result<ChatSendVO> send(@Valid @RequestBody ChatSendDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(chatService.send(me, dto.getToUserId(), dto.getContent(), dto.getReplyToId()));
    }

    /**
     * 今日剩余破冰额度。只读不扣减，供前端在搭话入口展示，
     * 避免用户试错多次后才发现已被拒。
     *
     * @param peerId 可选。传了才返回「对该对象」的剩余条数——
     *               「剩余」不针对具体对象时没有意义，故不传则为 null。
     */
    @GetMapping("/stranger-quota")
    public Result<StrangerQuotaVO> strangerQuota(
            @RequestParam(value = "peerId", required = false) Long peerId,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(quotaService.snapshot(me, peerId));
    }

    /** 撤回消息:仅发送方、仅 120s 内 */
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
