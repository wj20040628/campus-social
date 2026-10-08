package com.campus.social.service.impl;

import com.campus.social.exception.BusinessException;
import com.campus.social.mapper.RelationMapper;
import com.campus.social.service.RelationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 引导第 3 步的批量关注（从 {@code OnboardingServiceImpl} 抽出以守住单文件 300 行门禁）。
 *
 * <p>单独成类而不是留成私有方法：这段逻辑有独立的业务判断
 *（幂等去重、拉黑跳过、逐条容错），与引导的标签/归属写入无关，
 * 混在一个类里会让「引导事务边界在哪」难以阅读。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingFollowSupport {

    private final RelationService relationService;
    private final RelationMapper relationMapper;

    /**
     * 批量关注。
     *
     * <p>逐个调用 {@code RelationService.follow}（它自带幂等与黑名单拦截），
     * 单个失败不中断整批：引导页让用户勾了 8 个人，
     * 其中1 个恰好拉黑了他，不该让另外 7 个也关注不上。
     *
     * <p>计数只统计真正新增的。已关注的人必须先滤掉，
     * 否则前端会显示「已关注 8 人」而实际只新增了 5 个——
     * 已关注的那 3 个走 follow 的幂等分支，relation 表与计数都没变。
     *
     * @return 实际新增的关注数
     */
    public int followAll(long userId, List<Long> followUserIds) {
        List<Long> targets = sanitizeTargets(userId, followUserIds);
        if (targets.isEmpty()) {
            return 0;
        }
        // 一条 IN 查已关注集合，避免逐个 status() 打 4 条 SQL 造成 N+1
        Set<Long> already = relationMapper.selectFollowedTargets(userId, targets);

        int added = 0;
        for (Long target : targets) {
            if (already.contains(target)) {
                continue;
            }
            try {
                relationService.follow(userId, target);
                added++;
            } catch (BusinessException e) {
                // 被拉黑 / 对方账号异常：跳过这一条，不影响其余
                log.info("onboarding follow skipped: user={}, target={}, reason={}",
                        userId, target, e.getMessage());
            }
        }
        return added;
    }

    /** 去重 + 去空 + 去自己。空的 IN 列表会让 MyBatis 生成 {@code IN ()} 语法错误，故必须先判空 */
    private List<Long> sanitizeTargets(long userId, List<Long> followUserIds) {
        if (followUserIds == null || followUserIds.isEmpty()) {
            return List.of();
        }
        List<Long> targets = new java.util.ArrayList<>();
        for (Long id : new LinkedHashSet<>(followUserIds)) {
            if (id != null && id != userId) {
                targets.add(id);
            }
        }
        return targets;
    }
}