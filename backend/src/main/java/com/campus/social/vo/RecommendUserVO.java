package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 推荐关注的人（openapi RecommendUserItem）。
 *
 * <p>{@code reason} 是这个接口的灵魂：不上算法换来的是<b>规则可解释</b>——
 * 直接告诉用户「同院系 · 计算机科学与技术学院」，
 * 比一个无从解释的匹配度数字更可信，也更容易被接受。
 *
 * <p>{@code score} 不下发（见 {@link RecommendUserRow}）：内部排序依据一旦暴露，
 * 前端就会开始依赖它渲染顺序，改打分规则时被迫同步改前端。
 */
@Data
@Builder
public class RecommendUserVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;

    private String nickname;

    private String avatarUrl;

    private String dept;

    private String grade;

    private Integer followerCount;

    /** 推荐理由，直接展示 */
    private String reason;

    /** 与我的共同兴趣标签数 */
    private Integer commonTagCount;

    /**
     * 调用时是否已被我关注。
     *
     * <p>引导第 3 步的推荐列表按契约<b>已排除</b>了已关注与已拉黑的对象，
     * 所以正常情况下恒为 false。保留字段是因为契约要求，且推荐规则一旦放宽
     * （例如允许推荐已关注的人用于「你可能还喜欢」），前端不必再改。
     */
    private Boolean followed;
}
