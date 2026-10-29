package com.campus.social.task;

import com.campus.social.service.BanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 封禁到期恢复任务（F4）。
 *
 * <p>每 5 分钟扫一次 {@code ban:expiring} ZSET 中score 已到期且 <= 当前时刻的成员，
 * 把它们的 {@code user.status} 从冻结恢复为正常，并清掉封禁计划。
 *
 * <p>为什么是 5 分钟而不是更短：封禁到期精度到分钟级对用户已足够，
 * 而更密的轮询只是白耗Redis 连接。也不能只依赖用户登录时惰性解封——
 * 那意味着「封 7 天」实际变成「登录后才解封」，行为不可预期。
 *
 * <p>单个用户恢复失败不影响其余：逐个 try-catch，失败只记日志留待下轮重试
 * （计划未被 clearPlan 清掉，下次仍会被扫到）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BanExpireJob {

    private final BanService banService;
    /** 到期解封也回执（2026-10-28）：用户回来时至少知道「为什么被封过、现在好了」 */
    private final com.campus.social.service.impl.PostNotifySupport notifySupport;

    /** 每 5 分钟扫一次到期的封禁计划 */
    @Scheduled(cron = "0 */5 * * * ?")
    public void releaseExpiredBans() {
        Set<String> expired = banService.findExpired(System.currentTimeMillis());
        if (expired.isEmpty()) {
            return;
        }
        int ok = 0;
        for (String userId : expired) {
            try {
                // 统一走 banService.unban:清 ZSET 计划 + 恢复 status + 写审计(account_unban)
                banService.unban(Long.parseLong(userId));
                ok++;
                notifyQuietly(Long.parseLong(userId));
            } catch (Exception e) {
                // 不清计划：留到下一轮重试，避免一次网络抖动就让人永久封禁
                log.error("释放封禁失败,保留计划待重试: user={}", userId, e);
            }
        }
        log.info("[封禁] 到期释放完成: 成功={}, 待重试={}", ok, expired.size() - ok);
    }

    /** 通知失败不回滚解封,也不触发外层「保留计划」——人已经放出来了 */
    private void notifyQuietly(long userId) {
        try {
            notifySupport.pushSystemNotify(userId, 21, 0L, null,
                    "封禁期限已到，你的账号已恢复正常，可以继续发帖和搭话。");
        } catch (Exception e) {
            log.warn("ban-expire notify fail: user={}, err={}", userId, e.getMessage());
        }
    }
}