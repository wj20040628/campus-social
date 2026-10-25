package com.campus.social.service.impl;

import com.campus.social.entity.User;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 信誉分与发布配额（2026-10-24 信任闸门；10-25 v2 融合为统一信誉分）。
 *
 * <h2>分数构成（动态计算不落库）</h2>
 * <pre>
 *   分数 = clamp( 60                                   基准
 *               + min(近30天过审帖 + 集市活动, 40)     产出加分 ×1/条
 *               − 成立举报 × 20 − 封禁 × 40,           违规扣分
 *               0, 100 )
 * </pre>
 * <ul>
 *   <li><b>基准 60 = 受信线</b>：新号即受信（认证+账龄达标后），一次成立举报
 *       掉到 40 退出受信，靠持续产出补回——「恢复」不再是机制而是算术；</li>
 *   <li><b>产出加分只认有效内容</b>：帖子须过审（status=1，被下架不计），
 *       集市活动删除即失分。刷垃圾帖要么被举报下架（不加分）要么留着
 *       污染主页，两头不划算；封顶 40 让刷分上限可控；</li>
 *   <li><b>待审举报不扣分</b>（防集中举报刷分），扣分只认管理员判成立；
 *       集中举报走 ReportAutoBanService 的自动封禁线止血。</li>
 * </ul>
 *
 * <h2>发布配额（分数的兑现，2026-10-25）</h2>
 * <table border=1>
 *   <tr><th></th><th>未认证</th><th>认证 60–74</th><th>75–89</th><th>90–100</th></tr>
 *   <tr><th>建圈(在世)</th><td>✗</td><td>1</td><td>2</td><td>3</td></tr>
 *   <tr><th>集市在投</th><td>✗</td><td>1</td><td>2</td><td>3</td></tr>
 *   <tr><th>发帖/日</th><td>3</td><td colspan=3>30</td></tr>
 * </table>
 * 未认证禁建圈/集市发布由 @RequireVerified 拦截（既有行为）；
 * 帖子日额在 {@link PostWriteService#create} 扣减（Redis 自然日分桶）。
 *
 * <p><b>调用频率</b>：发帖命中/发集市/建圈/设置页各一次，四次索引查询
 * 在校园量级是毫秒级，不做缓存。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewTrustService {

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final PostMapper postMapper;
    private final MktActivityMapper mktActivityMapper;

    /** 受信账龄下限（天） */
    @Value("${campus.review.trust-min-days:14}")
    private int minDays;

    /** 信誉记录（加分产出与扣分）的计算窗口(天):窗口外不计 */
    @Value("${campus.review.credit-decay-days:30}")
    private int creditDecayDays;

    /** 未认证用户的发帖日额（防机器刷；认证后大幅放宽） */
    @Value("${campus.review.post-daily-unverified:3}")
    private int postDailyUnverified;

    /** 已认证用户的发帖日额 */
    @Value("${campus.review.post-daily-verified:30}")
    private int postDailyVerified;

    static final int SCORE_BASE = 60;
    static final int BONUS_CAP = 40;
    static final int DEDUCT_PER_UPHELD = 20;
    static final int DEDUCT_PER_BAN = 40;
    /** 受信(敏感词直发)的分数线,恰为基准分:扣分即掉线,产出可补回 */
    static final int TRUST_SCORE_MIN = 60;
    /** 配额分档线:75/90 → 圈子与集市在投 1/2/3 */
    static final int TIER2_SCORE = 75;
    static final int TIER3_SCORE = 90;

    /** 该用户是否可豁免 level-1 直接命中的送审 */
    public boolean isTrusted(long userId) {
        return status(userId).getTrusted();
    }

    /**
     * 信誉档案（GET /user/trust-status 用;他人徽章 GET /user/{id}/trust-badge 同源）。
     * 清单必须和实际闸门条件严格一致——「显示达标却仍被送审」会摧毁可信度。
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
        int posts = postMapper.countApprovedByAuthorSince(userId, since);
        int mkts = mktActivityMapper.countByUserSince(userId, since);
        int bonus = Math.min(BONUS_CAP, posts + mkts);
        int upheld = auditLogMapper.countUserActionsSince("report_upheld", userId, since);
        int bans = auditLogMapper.countUserActionsSince("account_ban", userId, since);
        int score = Math.max(0, Math.min(100,
                SCORE_BASE + bonus - upheld * DEDUCT_PER_UPHELD - bans * DEDUCT_PER_BAN));
        boolean badRecord = upheld + bans > 0;

        com.campus.social.vo.UserTrustStatusVO vo = new com.campus.social.vo.UserTrustStatusVO();
        vo.setVerified(verified);
        vo.setAccountAgeDays(ageDays);
        vo.setTrustMinDays(minDays);
        vo.setHasBadRecord(badRecord);
        vo.setCreditScore(score);
        vo.setCreditBonus(bonus);
        vo.setCreditDecayDays(creditDecayDays);
        int tier = verified ? quotaTier(score) : 0;
        vo.setCircleQuota(tier);
        vo.setMktQuota(tier);
        vo.setPostDailyQuota(verified ? postDailyVerified : postDailyUnverified);
        vo.setTrusted(u != null && verified && ageDays >= minDays && score >= TRUST_SCORE_MIN);
        return vo;
    }

    /** 建圈/集市在投配额（检查点直取,不走完整 status） */
    public int creationQuota(long userId) {
        User u = userMapper.selectById(userId);
        if (u == null || !Integer.valueOf(1).equals(u.getVerifyStatus())) {
            return 0;
        }
        return quotaTier(scoreOf(userId, u));
    }

    /** 发帖日额（PostWriteService 的 Redis 计数上限） */
    public int postDailyLimit(long userId) {
        User u = userMapper.selectById(userId);
        boolean verified = u != null && Integer.valueOf(1).equals(u.getVerifyStatus());
        return verified ? postDailyVerified : postDailyUnverified;
    }

    private int scoreOf(long userId, User u) {
        LocalDateTime since = LocalDateTime.now().minusDays(creditDecayDays);
        int bonus = Math.min(BONUS_CAP,
                postMapper.countApprovedByAuthorSince(userId, since)
                        + mktActivityMapper.countByUserSince(userId, since));
        int upheld = auditLogMapper.countUserActionsSince("report_upheld", userId, since);
        int bans = auditLogMapper.countUserActionsSince("account_ban", userId, since);
        return Math.max(0, Math.min(100,
                SCORE_BASE + bonus - upheld * DEDUCT_PER_UPHELD - bans * DEDUCT_PER_BAN));
    }

    private int quotaTier(int score) {
        if (score >= TIER3_SCORE) {
            return 3;
        }
        return score >= TIER2_SCORE ? 2 : 1;
    }
}
