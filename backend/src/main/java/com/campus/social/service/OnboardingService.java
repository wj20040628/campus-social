package com.campus.social.service;

import com.campus.social.dto.OnboardingCompleteDTO;
import com.campus.social.vo.OnboardingCompleteVO;
import com.campus.social.vo.OnboardingTagsVO;
import com.campus.social.vo.RecommendCircleListVO;
import com.campus.social.vo.RecommendUserListVO;

/**
 * F11 新用户引导（3 步：填归属 → 选标签 → 关注推荐）。
 *
 * <p>第 1 步「填归属」复用 {@code CampusVerifyService.saveSchoolProfile}，
 * 不在本接口重复实现——同一份归属写两个入口，迟早会出现口径不一致。
 */
public interface OnboardingService {

    /**
     * 引导第 2 步的候选标签。
     *
     * <p>与 {@code GET /tags} 的区别是<b>首次梯度裁剪</b>：
     * 每个 category 取 use_count 最高的若干条，总量 ≤ size，
     * 让用户第一屏看到的是本校最热的标签而不是一份 60 条的完整字典。
     */
    OnboardingTagsVO listTags(int size);

    /**
     * 完成引导（写归属 + 覆盖写标签 + 批量关注，幂等）。
     *
     * <p>幂等键是用户维度：判据为 {@code user.onboarding_step = 3}，
     * 重复提交返回 {@code duplicated = true} 且不重复写入。
     */
    OnboardingCompleteVO complete(long userId, OnboardingCompleteDTO dto);

    /** 引导第 3 步的推荐关注人选，已排除自己、已关注、已拉黑、冻结与管理员账号 */
    RecommendUserListVO recommendUsers(long userId, int size);

    /** 推荐圈子，已排除已加入的；允许返回空数组（带 emptyHint，不白屏） */
    RecommendCircleListVO recommendCircles(long userId, int size);
}
