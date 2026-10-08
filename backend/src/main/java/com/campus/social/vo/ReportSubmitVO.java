package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 提交举报回执（F4，AC-13 幂等）。
 *
 * <p>{@code duplicated = true} 表示此前已举报过同一目标，本次未新增记录。
 * 返回 200 而不是报错，是因为「重复举报」对用户不是错误，是预期。
 */
@Data
@Builder
public class ReportSubmitVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long reportId;

    /** true 表示此前已举报过同一目标，本次未创建新记录 */
    private Boolean duplicated;

    /** 0待审 1成立 2驳回 */
    private Integer status;
}