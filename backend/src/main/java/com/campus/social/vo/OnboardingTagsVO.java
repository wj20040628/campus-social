package com.campus.social.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 引导候选标签响应（openapi OnboardingTagsResponse 的 data）。
 *
 * <p>{@code minSelect} / {@code maxSelect} 由服务端下发而非前端写死：
 * 运营把 campus.onboarding-min-tags 从 3 调到 4 时，
 * 前端不该出现「后端要 4 个、前端文案却说 3 个」的分裂。
 */
@Data
@Builder
public class OnboardingTagsVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 候选标签，每组已做梯度裁剪，总量 ≤ size */
    private List<TagItemVO> tags;

    /** 引导要求最少选几个 */
    private Integer minSelect;

    /** 引导要求最多选几个 */
    private Integer maxSelect;
}
