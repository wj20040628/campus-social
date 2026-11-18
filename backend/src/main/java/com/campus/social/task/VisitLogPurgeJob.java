package com.campus.social.task;

import com.campus.social.mapper.VisitLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 访客访问记录清理（2026-11-14）。
 *
 * <p>隐私政策写明访问统计保留 90 天。分批删除而不是一条 DELETE 全清：
 * 校园量级下单日数据很小，但长假积压后一次性删除会长时间持锁，
 * 影响正在进行的心跳写入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitLogPurgeJob {

    private static final int RETENTION_DAYS = 90;
    private static final int BATCH_SIZE = 1000;
    private static final int MAX_BATCHES = 100;

    private final VisitLogMapper visitLogMapper;

    @Scheduled(cron = "0 20 4 * * ?")
    public void purgeExpired() {
        LocalDateTime before = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = visitLogMapper.deleteBefore(before, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("visit log purge: removed {} rows older than {} days", total, RETENTION_DAYS);
        }
    }
}