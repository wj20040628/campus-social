package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 提交反馈回执（2026-10-18）。
 *
 * <p>回执里带 {@code status} 而不是只带 id：提交方需要立刻知道
 * 「进的是待处理队列」而不是「已被采纳」—— 前者要提示「我们会看」，
 * 后者会误导用户以为已经改好了。
 */
@Data
@Builder
public class FeedbackSubmitVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long feedbackId;

    /** 0待处理 1已采纳 2已忽略。新提交恒为 0 */
    private Integer status;
}
