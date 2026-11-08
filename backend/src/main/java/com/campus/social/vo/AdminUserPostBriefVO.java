package com.campus.social.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 用户详情里的帖子画像（2026-11-08）。 */
@Data
public class AdminUserPostBriefVO {
    private Long id;
    /** 截断后的正文摘要 */
    private String excerpt;
    /** 0审核中 1通过 2下架 3删除 */
    private Integer status;
    private Integer likeCount;
    private Integer commentCount;
    private LocalDateTime createTime;
}
