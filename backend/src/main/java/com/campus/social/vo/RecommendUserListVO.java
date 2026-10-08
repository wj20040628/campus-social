package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 推荐关注的人响应（openapi OnboardingRecommendUsersResponse 的 data）。
 *
 * <p>{@code emptyHint} 由服务端下发而非前端硬编码（AC-19）：
 * 空列表必须展示引导文案 + 一个可点击行动按钮，不允许白屏。
 * 文案放前端就意味着前后端各写一份，措辞迟早漂移；
 * 放这里则改一句话前后端同时生效。
 * 列表非空时为 null。
 */
@Data
@Builder
public class RecommendUserListVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<RecommendUserVO> users;

    /** 列表为空时的引导文案；非空时为 null */
    private String emptyHint;
}
