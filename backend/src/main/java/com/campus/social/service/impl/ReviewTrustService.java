package com.campus.social.service.impl;

import com.campus.social.entity.User;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审核信任闸门（2026-10-24 新增；2026-10-25 升级为信誉分制）。
 *
 * <p><b>思路</b>：单校实名社区里，「谁在发」比「发了什么」是更强的风险信号。
 * 已认证、注册满半月、信誉良好的用户，其 level-1 敏感词命中（词表原形）
 * 直接发布并留痕；新账号或有扣分记录者照旧进人工队列。
 *
 * <h2>信誉分（2026-10-25，动态计算不落库）</h2>
 * <pre>
 *   分数 = 100 − 20 × 近180天成立举报(report_upheld 审计) − 40 × 近180天封禁史(account_ban)
 *   下限 0;受信要求 分数 ≥ 60
 * </pre>
 * <ul>
 *   <li><b>成立举报按人归因</b>：举报帖子/评论/消息/集市成立,同样给**作者**落
 *       user 级审计 report_upheld——2026-10-24 版只认 target_type=1 的用户举报,
 *       「内容举报成立却查无前科」的盲区就此修复(见 ReportServiceImpl.handleBan);</li>
 *   <li><b>待审举报不扣分</b>：扣分只认管理员判成立的,防止集中举报直接刷低
 *       他人信誉(待审举报走另一条止血通道:ReportAutoBanService 的自动封禁线);</li>
 *   <li><b>180 天窗口</b>：分数从审计表动态查询,不落库、无状态;窗口外的记录
 *       自然不计——给改过自新者出路,也免去恢复 Job;</li>
 *   <li><b>解封不还分</b>：封禁记录在窗口内恒扣 40,提前解封不洗白(封禁是
 *       管理员已定性的动作);到期窗口滑过即自然恢复。</li>
 * </ul>
 *
 * <p><b>调用频率</b>：发帖 level-1 命中时与 /user/trust-status 各调一次,
 * 两次索引查询在校园量级是微秒级,不做缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTrustService {

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;

    /** 受信账龄下限（天） */
    @Value("${campus.review.trust-min-days:14}")
    private int minDays;

    /** 信誉扣分记录的计算窗口(天):窗口外的成立举报/封禁不再计分 */
    @Value("${campus.review.credit-decay-days:180}")
    private int creditDecayDays;

    static final int SCORE_BASE = 100;
    static final int DEDUCT_PER_UPHELD = 20;
    static final int DEDUCT_PER_BAN = 40;
    static final int TRUST_SCORE_MIN = 60;

    /** 该用户是否可豁免 level-1 直接命中的送审 */
    public boolean isTrusted(long userId) {
        return status(userId).getTrusted();
    }

    /**
     * 信誉清单（GET /user/trust-status 用;他人徽章 GET /user/{id}/trust-badge 同源）。
     *
     * <p>清单必须和实际闸门条件严格一致——「显示达标却仍被送审」
     * 会直接摧毁这张卡的可信度。
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

        LocalDateTime since = LocalDateTime.now().minusDays(creditDecayDays);
        int upheld = auditLogMapper.countUserActionsSince("report_upheld", userId, since);
        int bans = auditLogMapper.countUserActionsSince("account_ban", userId, since);
        int score = Math.max(0, SCORE_BASE - upheld * DEDUCT_PER_UPHELD - bans * DEDUCT_PER_BAN);
        boolean badRecord = upheld + bans > 0;

        com.campus.social.vo.UserTrustStatusVO vo = new com.campus.social.vo.UserTrustStatusVO();
        vo.setVerified(verified);
        vo.setAccountAgeDays(ageDays);
        vo.setTrustMinDays(minDays);
        vo.setHasBadRecord(badRecord);
        vo.setCreditScore(score);
        vo.setCreditDecayDays(creditDecayDays);
        vo.setTrusted(u != null && verified && ageDays >= minDays && score >= TRUST_SCORE_MIN);
        return vo;
    }
}
