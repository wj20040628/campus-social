package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
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
}
