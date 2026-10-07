package com.campus.social.task;

import com.campus.social.mapper.ActivityMapper;

import com.campus.social.util.SensitiveWordFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务:
 * - 敏感词字典每 5 分钟全量刷新(DFA 重建)
 * - 活动状态机每 30 秒流转(到 start_time → 进行中,到 end_time → 已结束)
 * - 帖子热度分每 10 分钟衰减重算
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordRefreshTask {

    private final SensitiveWordFilter filter;
    private final ActivityMapper activityMapper;

    /** 5 分钟刷新敏感词 */
    @Scheduled(fixedRate = 300_000)
    public void refreshSensitiveWords() {
        try {
            filter.load();
        } catch (Exception e) {
            log.warn("refreshSensitiveWords fail: {}", e.getMessage());
        }
    }

    /** 30 秒:活动状态机流转 */
    @Scheduled(fixedRate = 30_000)
    public void advanceActivityStatus() {
        try {
            int inProgress = activityMapper.markInProgress();
            int finished = activityMapper.markFinished();
            if (inProgress > 0 || finished > 0) {
                log.info("activity status advance: inProgress={}, finished={}", inProgress, finished);
            }
        } catch (Exception e) {
            log.warn("advanceActivityStatus fail: {}", e.getMessage());
        }
    }
}
