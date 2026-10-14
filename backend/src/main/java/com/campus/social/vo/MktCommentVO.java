package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** 集市活动留言 VO。 */
@Data
@Builder
public class MktCommentVO {
    private Long id;
    private Long activityId;
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String content;
    private LocalDateTime createTime;
}
