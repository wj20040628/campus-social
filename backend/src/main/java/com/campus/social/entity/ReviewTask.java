package com.campus.social.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("review_task")
public class ReviewTask implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;
    private Long reviewerId;

    /** 0待审 1通过 2驳回 */
    private Integer status;

    private String reason;

    /** 命中的敏感词 JSON 数组(送审时落库,审核台展示用;驳回时不动) */
    private String hitWords;

    /** AI 机审结论:approve / reject / manual(空=未机审或无 key 走纯规则兜底) */
    private String aiVerdict;

    /** AI 机审理由(LLM 返回;approve/reject/manual 均可带) */
    private String aiReason;

    /** AI 机审完成时间 */
    private java.time.LocalDateTime aiReviewedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
