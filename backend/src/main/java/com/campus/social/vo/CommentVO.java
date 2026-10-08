package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class CommentVO {
    private Long id;
    private Long postId;
    private Long userId;
    private UserVO user;
    private Long parentId;
    private Integer level;
    private Long replyToUserId;
    /** 被回复的人的昵称(前端展示"回复 @xxx") */
    private String replyToNickname;
    private String content;
    private Integer likeCount;
    private boolean liked;
    private LocalDateTime createTime;
    /** 被 @ 的用户:昵称 → id(前端渲染 @昵称 时据此跳对应主页,昵称可能重名故存 id) */
    private Map<String, Long> mentioned;

    /**
     * 直接子回复数组，元素仍是 CommentVO（递归同构，三级封顶）。
     *
     * <p>为什么必须服务端组装：只返回一级评论时，二三级的 {@code parentId}
     * 指向的父评论根本不在响应里，前端无从嵌套。实际后果是用户点「回复」
     * 接口返 200、刷新后回复消失，且不报任何错——静默错误最难排查。
     *
     * <p>三级恒为 {@code []}：设计上禁止楼层缩进超过 2 层。
     */
    private List<CommentVO> children;

    /**
     * 直接子回复因上限被截断的条数，0 表示全部返回。
     *
     * <p>存在于契约而非仅文档，是为了让前端能区分「只有 2 条回复」
     * 与「还有 18 条没给你」——后者若只返回前20 条而不告知条数，
     * 用户会以为回复被系统吞了。
     */
    private Integer childrenOverflowCount;
}
