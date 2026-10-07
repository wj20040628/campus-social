package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核任务出参(含帖子内容摘要 + 作者)
 */
@Data
@Builder
public class ReviewTaskVO {
    private Long id;
    private Long postId;
    /** 帖子内容(审核要看) */
    private String content;
    /** 媒体 JSON(若有) */
    private String media;
    private UserVO author;
    /** 0待审 1通过 2驳回 */
    private Integer status;
    private Long reviewerId;
    private String reason;
    /** 命中的敏感词(送审时记录,审核台高亮展示;无则 null/空) */
    private List<String> hitWords;
    /** AI 机审结论:approve / reject / manual(空=未机审或无 key 兜底) */
    private String aiVerdict;
    /** AI 机审理由 */
    private String aiReason;
    /** AI 机审完成时间 */
    private LocalDateTime aiReviewedAt;
    private LocalDateTime createTime;
}
