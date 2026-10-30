package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.GroupService;
import com.campus.social.vo.GroupMemberInfoVO;
import com.campus.social.vo.GroupMessageVO;
import com.campus.social.vo.GroupVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    /**
     * 建群 + 拉人:body { name, avatarUrl, memberIds[] }。
     *
     * <p>校园信息不参与；建群/拉人由账号满 3 天 + 信誉 60 兜底，
     * 拉人仍只能邀请互关的人。
     */
    @PostMapping
    public Result<Map<String, Long>> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        String avatarUrl = body.get("avatarUrl") == null ? null : String.valueOf(body.get("avatarUrl"));
        // 复用 parseIds:它正确累加整个 id 列表。此前手写解析对 Iterable 走
        // Arrays.asList(单个)逐个覆写,建群塞多个 id 时只剩最后一个,其余全丢。
        java.util.List<Long> memberIds = parseIds(body.get("memberIds"));
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
        return Result.ok(groupService.myGroups(me, lastId, PageSize.clamp(size)));
    }

    /** 群历史消息 */
    @GetMapping("/{groupId}/messages")
    public Result<CursorPage<GroupMessageVO>> messages(
            @PathVariable("groupId") long groupId,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(groupService.groupMessages(groupId, me, lastId, PageSize.clamp(size)));
    }

    /** 发群消息:body { content, replyToId }。群成员即可发言，不再检查校园信息。 */
    @PostMapping("/{groupId}/message")
    public Result<com.campus.social.vo.GroupSendVO> sendMessage(
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
        // 返回打码后文本(GroupSendVO.content):前端乐观气泡用它渲染
        return Result.ok(groupService.sendGroupMessage(me, groupId, content, replyToId));
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

    // ---------------------------------------------------------------- 群管理(群信息头)

    /** 查群信息头:群名/人数/群主/公告 + 成员名单(校验我是成员) */
    @GetMapping("/{groupId}/members")
    public Result<GroupMemberInfoVO> members(
            @PathVariable("groupId") long groupId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(groupService.members(groupId, me));
    }

    /** 拉人(群成员均可):body { userIds[] }。邀请人需账号满 3 天 + 信誉 60。 */
    @PostMapping("/{groupId}/members")
    public Result<Map<String, Integer>> addMembers(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        List<Long> userIds = parseIds(body.get("userIds"));
        int added = groupService.addMembers(groupId, me, userIds);
        return Result.ok(Map.of("added", added));
    }

    /** 踢人(仅群主):body { userId } */
    @PostMapping("/{groupId}/kick")
    public Result<Void> kick(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        Long userId = parseLong(body.get("userId"));
        groupService.kick(groupId, me, userId);
        return Result.ok();
    }

    /** 群改名(仅群主):body { name } */
    @PutMapping("/{groupId}/name")
    public Result<Void> rename(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        groupService.rename(groupId, me, asString(body.get("name")));
        return Result.ok();
    }

    /** 群公告(仅群主):body { text },空=清除 */
    @PutMapping("/{groupId}/announcement")
    public Result<Void> announcement(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        groupService.setAnnouncement(groupId, me, asString(body.get("text")));
        return Result.ok();
    }

    /** 群主转让(仅群主):body { newOwnerId } */
    @PostMapping("/{groupId}/transfer")
    public Result<Void> transfer(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        Long newOwnerId = parseLong(body.get("newOwnerId"));
        groupService.transfer(groupId, me, newOwnerId);
        return Result.ok();
    }

    /** 退群:我退出;我是群主且还有他人则自动转让、只剩我则解散 */
    @DeleteMapping("/{groupId}/members/me")
    public Result<Void> leave(
            @PathVariable("groupId") long groupId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        groupService.leaveGroup(groupId, me);
        return Result.ok();
    }

    // ---------------------------------------------------------------- 群禁言(群主)

    /** 禁言某成员(仅群主):body { userId, muted }(muted 缺省=true 禁言) */
    @PostMapping("/{groupId}/mute")
    public Result<Void> mute(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long userId = parseLong(body.get("userId"));
        boolean muted = !Boolean.FALSE.equals(body.get("muted")); // 缺省=禁言
        groupService.mute(groupId, me, userId, muted);
        return Result.ok();
    }

    /** 禁言全员(仅群主):body { muted }(muted 缺省=true 全员禁言) */
    @PostMapping("/{groupId}/mute-all")
    public Result<Void> muteAll(
            @PathVariable("groupId") long groupId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        boolean muted = !Boolean.FALSE.equals(body.get("muted")); // 缺省=全员禁言
        groupService.muteAll(groupId, me, muted);
        return Result.ok();
    }

    /** 我是否被该群禁言(前端据此禁用输入框;后端发消息亦有 GROUP_MUTED 兜底) */
    @GetMapping("/{groupId}/muted-me")
    public Result<Map<String, Object>> mutedMe(
            @PathVariable("groupId") long groupId, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(Map.of("muted", groupService.isMuted(groupId, me)));
    }

    // ---------------------------------------------------------------- body 解析工具
    /** 把 Map 里的 id 字段解析为 long,缺失/非数字则 0(由 service 按 0 判定拒绝) */
    private Long parseLong(Object raw) {
        if (raw == null) return 0L;
        String s = String.valueOf(raw);
        return s.isBlank() ? 0L : Long.parseLong(s);
    }

    private String asString(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    /** 解析 body 里的人 id 列表,兼容 JSON 数组与 Java 数组两种形态(镜像 create) */
    private List<Long> parseIds(Object rawMembers) {
        List<Long> ids = new java.util.ArrayList<>();
        if (rawMembers instanceof Iterable<?> it) {
            for (Object o : it) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    ids.add(Long.parseLong(String.valueOf(o)));
                }
            }
        } else if (rawMembers instanceof Object[] arr) {
            for (Object o : arr) {
                if (o != null) ids.add(Long.parseLong(String.valueOf(o)));
            }
        }
        return ids;
    }
}
