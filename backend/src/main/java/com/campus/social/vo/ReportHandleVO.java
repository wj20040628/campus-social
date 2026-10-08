package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 举报处置回执（F4）。
 *
 * <p>{@code status} 回显落库后的状态：ban=1、reject=2、ignore 保持 0。
 * 前端据此就地刷新这一行，不必重拉整页。
 */
@Data
@Builder
public class ReportHandleVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long reportId;

    private Integer status;

    private String action;

    /** action=ban 时的解封时间；永久封禁为 null */
    private LocalDateTime bannedUntil;
}