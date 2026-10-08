package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 完成引导的回执（openapi OnboardingCompleteResponse 的 data）。
 *
 * <p>{@code duplicated = true} 表示本次未写入任何数据（此前已完成过）。
 * 前端据此提示「已完成」而不是重新走一遍引导，
 * 也据此区分「我刚完成」与「我之前就完成了」两种语义。
 */
@Data
@Builder
public class OnboardingCompleteVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 是否处于「已完成」态。duplicated 为 false 时必然为 true */
    private Boolean completed;

    /** true 表示本次是重复提交，未重复写标签、未重复关注 */
    private Boolean duplicated;

    /** 0未开始 1已填归属 2已选标签 3已完成 */
    private Integer onboardingStep;

    /** 本次实际新增的关注数。重复提交时为 0 */
    private Integer followedCount;

    /** 当前生效的标签数。重复提交时返回已生效的数量而非 0 */
    private Integer tagCount;
}
