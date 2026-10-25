package com.campus.social.service.impl;

import com.campus.social.entity.User;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.ReportMapper;
import com.campus.social.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审核信任闸门（2026-10-24 新增，替代 AI 机审的队列瘦身方案）。
 *
 * <p><b>思路</b>：单校实名社区里，「谁在发」比「发了什么」是更强的风险信号。
 * 已认证、注册满半月、无违规前科的用户，其 level-1 敏感词命中（词表原形）
 * 直接发布并留痕；新账号或有前科者照旧进人工队列。AI 关闭时人工队列的
 * 主要负担恰恰是老用户的无意命中（提了个被词库误伤的词），这一刀切掉大头。
 *
 * <p><b>判定条件（全部满足才受信）</b>：
 * <ol>
 *   <li>verify_status=1（学号核验通过——实名背书是威慑的根）</li>
 *   <li>注册 ≥ {@code campus.review.trust-min-days} 天（默认 14，冷却新号）</li>
 *   <li>无「举报用户且成立」记录（report target_type=1 status=1）</li>
 *   <li>无直接封禁记录（audit_log account_ban，含 2026-10-24 起的处置）</li>
 * </ol>
 *
 * <p><b>已知盲区（诚实记录）</b>：举报流程封禁（report_ban）的审计
 * target 记的是 report 而非 user，从 audit_log 反查不到「曾被举报封过」；
 * 该信号只能靠条件 3 的近似覆盖。补齐需要给 report_ban 落 user 级审计，
 * 属于后续可做的小改动，不阻塞本闸门上线——受信判定宁可保守。
 *
 * <p><b>调用频率</b>：仅 level-1 直接命中时调用（发帖/编辑各一次），
 * 三次主键/索引查询在校园量级是微秒级，不做缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTrustService {

    private final UserMapper userMapper;
    private final ReportMapper reportMapper;
    private final AuditLogMapper auditLogMapper;

    /** 受信账龄下限（天） */
    @Value("${campus.review.trust-min-days:14}")
    private int minDays;

    /** 该用户是否可豁免 level-1 直接命中的送审 */
    public boolean isTrusted(long userId) {
        return status(userId).getTrusted();
    }

    /**
     * 信誉清单（2026-10-24 加,GET /user/trust-status 用）。
     *
     * <p>与 {@link #isTrusted} 同一套判定、同一次查询拆开呈现——
     * 用户看到的三条清单必须和实际闸门条件严格一致,
     * 「显示达标却仍被送审」会直接摧毁这张卡的可信度。
     */
    public com.campus.social.vo.UserTrustStatusVO status(long userId) {
        User u = userMapper.selectById(userId);
        boolean verified = u != null && Integer.valueOf(1).equals(u.getVerifyStatus());
        int ageDays = 0;
        if (u != null && u.getCreateTime() != null) {
            ageDays = (int) java.time.Duration.between(u.getCreateTime(), LocalDateTime.now()).toDays();
        }
        if (ageDays < 0) {
            ageDays = 0;
        }
        boolean badRecord = (u != null && reportMapper.countUpheldUserReports(userId) > 0)
                || (u != null && auditLogMapper.selectLatest("account_ban", "user", userId) != null);

        com.campus.social.vo.UserTrustStatusVO vo = new com.campus.social.vo.UserTrustStatusVO();
        vo.setVerified(verified);
        vo.setAccountAgeDays(ageDays);
        vo.setTrustMinDays(minDays);
        vo.setHasBadRecord(badRecord);
        vo.setTrusted(u != null && verified && ageDays >= minDays && !badRecord);
        return vo;
    }
}
