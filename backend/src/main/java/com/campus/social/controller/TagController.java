package com.campus.social.controller;

import com.campus.social.common.Result;
import com.campus.social.dto.UserTagsPutDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.TagService;
import com.campus.social.vo.TagItemVO;
import com.campus.social.vo.TagTreeVO;
import com.campus.social.vo.UserTagsVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 兴趣标签端点（支撑 F11 引导第 2 步）。
 *
 * <p>与 {@code UserController} 的 {@code GET /user/{id}} 路径不冲突：
 * 那个是单段路径，这个是 {@code /user/{id}/tags} 两段，Spring 按最长匹配区分。
 * 同理 {@code PUT /user/tags} 与已有的 {@code PUT /user/me} 也是不同路径。
 */
@RestController
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /** 标签字典（按 category 分组）。不做分页：字典量恒定在 30-60 条 */
    @GetMapping("/tags")
    public Result<TagTreeVO> listTags(
            @RequestParam(value = "category", required = false) Integer category) {
        return Result.ok(tagService.listTree(category));
    }

    /** 设置我的兴趣标签（全量覆盖，上限 8，超限 422） */
    @PutMapping("/user/tags")
    public Result<UserTagsVO> replaceMyTags(@Valid @RequestBody UserTagsPutDTO dto,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(tagService.replaceMine(me, dto.getTagIds()));
    }

    /** 查看他人的兴趣标签（公开信息，任意登录用户可查） */
    @GetMapping("/user/{id}/tags")
    public Result<List<TagItemVO>> listUserTags(@PathVariable("id") long userId) {
        return Result.ok(tagService.listByUser(userId));
    }
}
