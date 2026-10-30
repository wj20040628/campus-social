package com.campus.social.service;

import com.campus.social.entity.InterestTag;
import com.campus.social.entity.UserTag;
import com.campus.social.vo.TagItemVO;
import com.campus.social.vo.TagTreeVO;
import com.campus.social.vo.UserTagsVO;

import java.util.List;

/**
 * 兴趣标签（支撑 F11 引导第 2 步）。
 *
 * <p>范围说明：<b>不包含</b>「按标签找搭子」的发现页——那属 F6，归 MVP+。
 * 本轮只做字典与用户标签关系，因为 F11 引导明确要求用户选 3-5 个标签。
 */
public interface TagService {

    /** 标签字典（按 category 分组；category 为 null 返回全部分组） */
    TagTreeVO listTree(Integer category);

    /** 我的标签 */
    List<TagItemVO> listMine(long userId);

    /** 他人的标签（公开信息，任意登录用户可查） */
    List<TagItemVO> listByUser(long userId);

    /**
     * 全量覆盖我的标签（同事务）。
     * 上限由 campus.max-tags 控制，超限抛 UNPROCESSABLE。
     */
    UserTagsVO replaceMine(long userId, List<Long> tagIds);

    /**
     * 事务内覆盖写，供引导流程复用。
     * 与 {@link #replaceMine} 的差别：不抛业务异常（引导流程自己校验数量），
     * 且由调用方决定事务边界。
     */
    void replaceTagsInTx(long userId, List<Long> tagIds);

    /**
     * F11 引导候选标签：每组按 use_count 降序取前若干条，总量 ≤ total。
     *
     * <p>每组配额由本方法内部按分类数均分得出，调用方只给总量——
     * 让调用方传 {@code perCategory} 会把「分类有 5 个」这个事实复制到第二个类里，
     * 将来加第 6 个分类时必漏一处。
     */
    List<TagItemVO> onboardingCandidates(int total);

    /** 按 id 集合批量取标签，保持传入顺序 */
    List<TagItemVO> listByIds(List<Long> ids);

    /** 批量取用户-标签对，避免调用方逐个查造成 N+1 */
    List<UserTag> listUserTags(List<Long> userIds);

    /** 校验 tagIds 全部存在且未删除，返回去重后的有序列表；不存在则抛 TAG_NOT_FOUND */
    List<Long> validateTagIds(List<Long> tagIds);

    /** 标签分类名映射，供分组展示 */
    String categoryName(Integer category);

    /** 供引导流程内部使用：把 entity 转 VO */
    TagItemVO toVo(InterestTag tag);
}
