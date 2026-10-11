package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.GroupService;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 群聊接口(镜像 ChatController):/group 前缀。
 * 建群/拉人/群消息/群已读/群撤回,读 me 走 AuthInterceptor 请求属性。
 */
@RestController
@RequestMapping("/group")
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    /** 建群 + 拉人:body { name, avatarUrl, memberIds[] } */
    @PostMapping
    public Result<Map<String, Long>> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        String avatarUrl = body.get("avatarUrl") == null ? null : String.valueOf(body.get("avatarUrl"));
        java.util.List<Long> memberIds = java.util.List.of();
        Object rawMembers = body.get("memberIds");
        if (rawMembers instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    memberIds = java.util.Arrays.asList(Long.parseLong(String.valueOf(o)));
                }
            }
        } else if (rawMembers instanceof Object[] arr) {
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (Object o : arr) {
                if (o != null) ids.add(Long.parseLong(String.valueOf(o)));
                memberIds = ids;
            }
        }
        long groupId = groupService.createGroup(me, name, avatarUrl, memberIds);
        return Result.ok(Map.of("groupId", groupId));
    }

    /** 我加入的群(含我侧未读) */
    @GetMapping
    public Result<CursorPage<GroupVO>> myGroups(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(groupService.myGroups(me, lastId, size));
    }

    /** 群历史消息 */
    @GetMapping("/{groupId}/messages")
    public Result<CursorPage<GroupMessageVO>> messages(
            @PathVariable("groupId") long groupId,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(groupService.groupMessages(groupId, me, lastId, size));
    }

    /** 发群消息:body { content, replyToId } */
    @PostMapping("/{groupId}/message")
    public Result<Map<String, Long>> sendMessage(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        Long replyToId = null;
        Object rti = body.get("replyToId");
        if (rti != null && !String.valueOf(rti).isBlank()) {
            replyToId = Long.parseLong(String.valueOf(rti));
        }
        long msgId = groupService.sendGroupMessage(me, groupId, content, replyToId);
        return Result.ok(Map.of("msgId", msgId));
    }

    /** 撤回群消息:仅发送方、120s 窗口 */
    @DeleteMapping("/message/{msgId}")
    public Result<Void> recall(@PathVariable("msgId") long msgId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        groupService.recallGroupMessage(me, msgId);
        return Result.ok();
    }

    /** 群已读:更新我侧水位 */
    @PostMapping("/{groupId}/read")
    public Result<Void> read(@PathVariable("groupId") long groupId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        groupService.markGroupRead(me, groupId);
        return Result.ok();
    }
}
