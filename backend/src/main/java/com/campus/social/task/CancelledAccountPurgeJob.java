package com.campus.social.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.CancelledAccountRetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 已注销账号留存清理任务（2026-11-08）。
 *
 * <p>每天 04:10 扫一批「注销超过 N 天且尚未清理」的账号，删除其私信、
 * 会话偏好与通知，并写 purged_at 防重复。N 默认 180 天，可由
 * {@code CAMPUS_ACCOUNT_CANCELLED_RETENTION_DAYS} 调整。
 *
 * <p>单批 100 个：校园量级下注销账号很少，限批是为了避免一次长事务；
 * 清不完的下一轮继续（purged_at 未写），不需要额外游标。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelledAccountPurgeJob {

    private static final int BATCH = 100;

    private final UserMapper userMapper;
    private final CancelledAccountRetentionService retentionService;

    @Value("${campus.account.cancelled-retention-days:180}")
    private int retentionDays;

    @Scheduled(cron = "0 10 4 * * ?")
    public void purgeExpiredCancelledAccounts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<User> due = userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, 2)
                .isNull(User::getPurgedAt)
                .lt(User::getUpdateTime, cutoff)
                .orderByAsc(User::getId)
                .last("LIMIT " + BATCH));
        if (due.isEmpty()) {
            return;
        }
        int ok = 0;
        for (User u : due) {
            try {
                retentionService.purgeUser(u.getId());
                ok++;
            } catch (Exception e) {
                // 不写 purged_at，下一轮继续；单个失败不拖累整批
                log.error("cancelled account purge fail: uid={}, err={}", u.getId(), e.getMessage());
            }
        }
        log.info("[注销留存] 到期清理完成: 成功={}, 待重试={}, 留存天数={}", ok, due.size() - ok, retentionDays);
    }
}
