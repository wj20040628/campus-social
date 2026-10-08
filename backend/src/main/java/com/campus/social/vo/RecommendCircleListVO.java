package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 推荐圈子响应（openapi OnboardingRecommendCirclesResponse 的 data）。
 *
 * <p>冷启动期圈子可能不足，允许返回少于 size 条甚至空数组，
 * 但空态必须有引导文案（AC-19）：{@code emptyHint} 非空时前端渲染
 * 「文案 + 行动按钮」，不允许白屏。
 */
@Data
@Builder
public class RecommendCircleListVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<RecommendCircleVO> circles;

    /** 列表为空时的引导文案；非空时为 null */
    private String emptyHint;
}
