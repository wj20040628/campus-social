package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.ChatPrefDTO;
import com.campus.social.dto.ChatSendDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.interceptor.RequireVerified;
import com.campus.social.service.ChatConvPrefService;
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
    private final ChatConvPrefService prefService;

    public ChatController(ChatService chatService, StrangerQuotaService quotaService,
                          ChatConvPrefService prefService) {
        this.chatService = chatService;
        this.quotaService = quotaService;
        this.prefService = prefService;
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
    /**
     * {@code @RequireVerified}（2026-10-21 新增）：未认证用户不能发起搭话。
     *
     * <p>业务层 {@code ChatGuardServiceImpl} 里本来就有同样的判断，
     * 这里再加一层注解不是重复 —— 那一层顺带管黑名单/额度/陌生人开关，
     * 混在一处会让「未认证」这条规则埋在一堆校验里，
     * 而注解写在端点上，「这个接口需要认证」是一眼可见的事实。
     * 两层都保留：注解负责快速短路（不查库不建会话），业务层负责完整护栏。
     */
    @RequireVerified
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

    /**
     * 会话偏好：置顶 / 免打扰 / 从列表删除（手机端右滑三项）。
     *
     * <p>私信和群聊共用这一个入口，靠 kind 区分：targetId 私信传对方 userId、
     * 群聊传 groupId。放在 /chat 下而不是 /group 下，是因为这是"我对这条会话的态度"，
     * 与消息本身无关；前端右滑也只有一处调用。
     */
    @PostMapping("/pref")
    public Result<Void> setPref(@Valid @RequestBody ChatPrefDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        prefService.set(me, dto.getKind(), dto.getTargetId(), dto.getPinned(), dto.getMuted(), dto.getHidden());
        return Result.ok();
    }

    @PostMapping("/read")
    public Result<Void> read(@RequestParam("peerId") long peerId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        chatService.markRead(me, peerId);
        return Result.ok();
    }
}
