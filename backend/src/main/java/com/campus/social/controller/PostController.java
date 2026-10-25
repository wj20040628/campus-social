package com.campus.social.controller;

import com.campus.social.common.CursorPage;
import com.campus.social.common.PageSize;
import com.campus.social.common.Result;
import com.campus.social.dto.CreateCommentDTO;
import com.campus.social.dto.CreatePostDTO;
import com.campus.social.interceptor.AuthInterceptor;
import com.campus.social.service.PostService;
import com.campus.social.vo.CommentChildrenPageVO;
import com.campus.social.vo.CommentVO;
import com.campus.social.vo.PostVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/post")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /** 发动态 */
    @PostMapping
    public Result<Map<String, Long>> create(@Valid @RequestBody CreatePostDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long id = postService.create(me, dto);
        return Result.ok(Map.of("postId", id));
    }

    /** 编辑动态(仅作者) */
    @PutMapping("/{id}")
    public Result<com.campus.social.vo.PostVO> edit(@PathVariable("id") long id,
            @Valid @RequestBody CreatePostDTO dto, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.edit(id, dto, me));
    }

    /** 帖子详情 */
    @GetMapping("/{id}")
    public Result<PostVO> detail(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.detail(id, me != null ? me : 0L));
    }

    /** 软删除(作者) */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        postService.delete(id, me);
        return Result.ok();
    }

    /** 写扩散 Feed(我关注的) */
    @GetMapping("/feed")
    public Result<CursorPage<PostVO>> feed(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "by", defaultValue = "time") String by,
            @RequestParam(value = "order", defaultValue = "desc") String order,
            @RequestParam(value = "topic", required = false) String topic,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.feed(me, lastId, PageSize.clamp(size), by, order, topic));
    }

    /** 探索页(热门) */
    @GetMapping("/hot")
    public Result<CursorPage<PostVO>> hot(
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "lastHotScore", required = false) Long lastHotScore,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.hot(PageSize.clamp(size), lastHotScore, me != null ? me : 0L));
    }

    /** 话题落地页:某话题下所有帖子 */
    @GetMapping("/topic")
    public Result<CursorPage<PostVO>> topic(
            @RequestParam("topic") String topic,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.feedByTopic(topic, lastId, PageSize.clamp(size), me != null ? me : 0L));
    }

    /** 关注的人的帖子(读 feed_item 收件箱,写扩散闭环) */
    @GetMapping("/feed/following")
    public Result<CursorPage<PostVO>> feedFollowing(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(postService.feedFollowing(me, lastId, PageSize.clamp(size)));
    }

    /** 点赞(幂等) */
    @PostMapping("/{id}/like")
    public Result<Void> like(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        postService.like(id, me);
        return Result.ok();
    }

    /** 取消点赞(幂等) */
    @DeleteMapping("/{id}/like")
    public Result<Void> unlike(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        postService.unlike(id, me);
        return Result.ok();
    }

    /**
     * 收藏(幂等)。刻意不发通知给作者 —— 收藏是私人整理动作,与点赞区分开。
     */
    @PostMapping("/{id}/favorite")
    public Result<Void> favorite(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        postService.favorite(id, me);
        return Result.ok();
    }

    /** 取消收藏(幂等),走物理删除 */
    @DeleteMapping("/{id}/favorite")
    public Result<Void> unfavorite(@PathVariable("id") long id, HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        postService.unfavorite(id, me);
        return Result.ok();
    }

    /**
     * 我收藏的帖子(按收藏时间倒序)。
     *
     * <p>lastId 是**收藏记录 id**而非帖子 id,前端把上一页的 nextCursor 原样回传即可。
     * 按收藏时间排序就必须用收藏记录做游标,否则今天收藏一条旧帖会让翻页错乱。
     */
    @GetMapping("/favorites")
    public Result<CursorPage<PostVO>> favorites(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(postService.favoritePosts(me, me, lastId, PageSize.clamp(size)));
    }

    /** 我赞过的帖子(按点赞时间倒序),游标语义同 favorites */
    @GetMapping("/liked")
    public Result<CursorPage<PostVO>> liked(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        if (me == null) {
            return Result.fail(401, "请先登录");
        }
        return Result.ok(postService.likedPosts(me, me, lastId, PageSize.clamp(size)));
    }

    /** 评论 */
    @PostMapping("/{id}/comment")
    public Result<Map<String, Long>> comment(@PathVariable("id") long id,
                                              @Valid @RequestBody CreateCommentDTO dto,
                                              HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        long commentId = postService.comment(id, dto, me);
        return Result.ok(Map.of("commentId", commentId));
    }

    /**
     * 删除自己的评论(2026-10-24 新增):仅作者可删,连同其子回复软删。
     * 403 FORBIDDEN 删他人的;404 COMMENT_NOT_FOUND 不存在或已删。
     */
    @DeleteMapping("/{id}/comment/{commentId}")
    public Result<Map<String, String>> deleteComment(@PathVariable("id") long id,
                                                     @PathVariable("commentId") long commentId,
                                                     HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        postService.deleteComment(id, commentId, me);
        return Result.ok(Map.of("message", "评论已删除"));
    }

    /** 评论列表（一级评论 + 各条的 children 树） */
    @GetMapping("/{id}/comments")
    public Result<CursorPage<CommentVO>> comments(
            @PathVariable("id") long id,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.comments(id, lastId, PageSize.clamp(size), me != null ? me : 0L));
    }

    /**
     * 某条评论的全部直接子回复（children 溢出时补齐用）。
     *
     * <p>不递归：二级的孩子是三级、三级没有孩子，最多一层就该停。
     * 再往下前端拿本页返回的 id 再调一次即可。
     */
    @GetMapping("/{id}/comment/{commentId}/children")
    public Result<CommentChildrenPageVO> commentChildren(
            @PathVariable("id") long id,
            @PathVariable("commentId") long commentId,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.commentChildren(id, commentId, lastId, PageSize.clamp(size), me != null ? me : 0L));
    }

    /** 某用户发的帖子(本人视角,含待审/驳回) */
    @GetMapping("/mine")
    public Result<CursorPage<PostVO>> myPosts(
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "size", defaultValue = "20") int size,
            HttpServletRequest req) {
        Long me = (Long) req.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return Result.ok(postService.myPosts(me, me, lastId, PageSize.clamp(size)));
    }
}
