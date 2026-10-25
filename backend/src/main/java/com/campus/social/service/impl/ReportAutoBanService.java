package com.campus.social.service.impl;

import com.campus.social.entity.Report;
import com.campus.social.entity.User;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.AuditService;
import com.campus.social.service.BanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 举报集中自动封禁（2026-10-25，众包风控的「止血」半件套）。
 *
 * <p><b>机制</b>：一个用户（含其名下内容，归因见 {@link ReportTargetResolver}）
 * 在 24 小时内被 ≥ N 个**已认证**的独立举报人举报（待审或成立均计）时，
 * 立即自动封禁 D 天。违规扩散的止血从「管理员下次巡检」提前到
 * 「第 N 个举报人点下去的那一秒」；到期由 BanExpireJob 自动解封。
 *
 * <p><b>三道防滥用护栏（缺一不可，别拆）</b>：
 * <ol>
 *   <li>举报人必须 verify_status=1——未认证账号不计入封禁线，动员小号
 *       刷举报的成本从「注册邮箱」抬到「过学号核验」；</li>
 *   <li>封的是**限期**（默认 3 天）不是永久——被恶意集中举报的无辜用户
 *       三天后自动恢复，管理员复核也可随时手动解封；</li>
 *   <li>DISTINCT reporter + uk_report + 24h 滑窗——同一人重复举报天然去重，
 *       过窗即清零，想再触发需要新的独立认证举报人。</li>
 * </ol>
 *
 * <p><b>与 ReportFoldService 的分工</b>：折叠管「内容」（帖子出 Feed 进人审，
 * 有恢复通道），本服务管「人」（限期封禁止血）。都由举报提交触发，
 * 各自独立判定，互不依赖。
 *
 * <p><b>为什么在事务提交后执行</b>：封禁含 Redis 写（ZSET 解封计划 + 状态缓存），
 * 不可随事务回滚——若举报插入回滚而封禁已执行，就成了「举报没提交成功、
 * 人却被封了」。afterCompletion(STATUS_COMMITTED) 保证只有提交成功才动手。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportAutoBanService {

    private final ReportMapper reportMapper;
    private final UserMapper userMapper;
    private final BanService banService;
    private final AuditService auditService;
    private final ReportTargetResolver targetResolver;

    /** 24h 内独立已认证举报人阈值；<=0 视为关闭本机制 */
    @Value("${campus.review.auto-ban-reporters:3}")
    private int threshold;

    /** 自动封禁天数（限期,到期自动解封;管理员可提前手动解封） */
    @Value("${campus.review.auto-ban-days:3}")
    private int banDays;

    /** 审计动作名 */
    static final String AUDIT_AUTO_BAN = "auto_ban_reports";

    /**
     * 举报提交事务内调用：注册提交后检查。
     * 无事务上下文（理论上不会有）时直接执行,与 PostNotifySupport 同款兜底。
     */
    public void scheduleCheckAfterCommit(Report report) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        runQuietly(report);
                    }
                }
            });
        } else {
            runQuietly(report);
        }
    }

    private void runQuietly(Report report) {
        try {
            check(report);
        } catch (Exception e) {
            // 自动封禁是附加保护,任何异常都不允许影响举报主链路(事务已提交,这里只记日志)
            log.warn("auto-ban check failed (non-fatal): report={}, {}", report.getId(), e.getMessage());
        }
    }

    /**
     * 以 report 的归因目标为线索,检查该目标是否已到封禁线。
     * public 供集成测试直调;生产入口是 scheduleCheckAfterCommit。
     * 包含本条(report 已提交,selectRecent 能查到)。
     */
    public void check(Report report) {
        if (threshold <= 0) {
            return;
        }
        Long targetUser = targetResolver.resolveUserId(report);
        if (targetUser == null) {
            return;
        }
        User target = userMapper.selectById(targetUser);
        if (target == null || Integer.valueOf(1).equals(target.getStatus())) {
            return; // 已冻结(含已被自动封过)/已注销:幂等跳过
        }

        Set<Long> reporters = collectCountableReporters(targetUser);
        int verifiedCount = countVerified(reporters);
        if (verifiedCount < threshold) {
            return;
        }

        banService.ban(targetUser, banDays);
        auditService.recordSystem(AUDIT_AUTO_BAN, "user", targetUser,
                "24h 内 " + verifiedCount + " 名已认证用户举报(阈值 " + threshold
                        + "),自动封禁 " + banDays + " 天,到期自动解封,请复核");
        log.info("user auto-banned by concentrated reports: user={}, verifiedReporters={}, threshold={}, days={}",
                targetUser, verifiedCount, threshold, banDays);
    }

    /**
     * 近 24h 全部举报按「归因到的人」分组,取目标的那组举报人。
     * 计数口径:未被处置的待审(status=0 且 handler_id 空)或成立(status=1);
     * 驳回与被忽略的不算——管理员已看过并否掉的不该再给举报人加权。
     */
    private Set<Long> collectCountableReporters(long targetUser) {
        List<Report> recent = reportMapper.selectRecent(24);
        Map<Long, Set<Long>> byTarget = new HashMap<>();
        for (Report r : recent) {
            boolean countable = (Integer.valueOf(0).equals(r.getStatus()) && r.getHandlerId() == null)
                    || Integer.valueOf(1).equals(r.getStatus());
            if (!countable) {
                continue;
            }
            Long uid = targetResolver.resolveUserId(r);
            if (uid == null) {
                continue;
            }
            byTarget.computeIfAbsent(uid, k -> new HashSet<>()).add(r.getReporterId());
        }
        return byTarget.getOrDefault(targetUser, Set.of());
    }

    private int countVerified(Set<Long> reporters) {
        if (reporters.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (User u : userMapper.selectBatchIds(reporters)) {
            if (Integer.valueOf(1).equals(u.getVerifyStatus())) {
                n++;
            }
        }
        return n;
    }
}
