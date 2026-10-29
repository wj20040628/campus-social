package com.campus.social.service.impl;

import com.campus.social.entity.User;
import com.campus.social.mapper.AuditLogMapper;
import com.campus.social.mapper.FileAssetMapper;
import com.campus.social.mapper.MktActivityMapper;
import com.campus.social.mapper.PostMapper;
import com.campus.social.mapper.UserMapper;
import com.campus.social.util.FileLimits;
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
 *               + min(40, min(10, 近30天过审文件)
 *                          + 近30天过审帖 + 集市活动)  产出加分 ×1/条
 *               − 成立举报 × 20 − 封禁 × 40,           违规扣分
 *               0, 100 )
 * </pre>
 * <ul>
 *   <li><b>基准 60 = 受信线</b>：新号即受信（认证+账龄达标后），一次成立举报
 *       掉到 40 退出受信，靠持续产出补回——「恢复」不再是机制而是算术；</li>
 *   <li><b>产出加分只认有效内容</b>：帖子须过审（status=1，被下架不计），
 *       集市活动删除即失分，文件须过审且未删除；文件来源单独封顶 10 分，
 *       因此纯靠传文件最高只到 70 分（1 档），刷文件换不来 2/3 档；</li>
 *   <li><b>待审举报不扣分</b>（防集中举报刷分），扣分只认管理员判成立；</li>
 * </ul>
 *
 * <h2>发布配额（分数的兑现，2026-10-27 统一档位）</h2>
 * <table border=1>
 *   <tr><th></th><th>未认证</th><th>认证 60–79</th><th>80–99</th><th>100</th></tr>
 *   <tr><th>建圈(在世)</th><td>✗</td><td>1</td><td>2</td><td>3</td></tr>
 *   <tr><th>集市在投</th><td>✗</td><td>1</td><td>2</td><td>3</td></tr>
 *   <tr><th>发帖/日</th><td colspan=2>5</td><td>10</td><td>15</td></tr>
 *   <tr><th>文件额度</th><td>✗</td><td>1MB/5MB</td><td>2MB/10MB</td><td>3MB/15MB</td></tr>
 * </table>
 * 文件额度由 {@link FileLimits} 统一持有，上传校验与前端提示同源。
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
    private final FileAssetMapper fileAssetMapper;

    /** 受信账龄下限（天） */
    @Value("${campus.review.trust-min-days:14}")
    private int minDays;

    /** 信誉记录（加分产出与扣分）的计算窗口(天):窗口外不计 */
    @Value("${campus.review.credit-decay-days:30}")
    private int creditDecayDays;

    /** 发帖日额（2026-10-28 起按信誉档位，与建圈/集市/文件同一条 60-80-100 线） */
    @Value("${campus.review.post-daily-tier1:5}")
    private int postDailyTier1;

    @Value("${campus.review.post-daily-tier2:10}")
    private int postDailyTier2;

    @Value("${campus.review.post-daily-tier3:15}")
    private int postDailyTier3;

    static final int SCORE_BASE = 60;
    static final int BONUS_CAP = 40;
    /** 文件来源加分单独封顶：传文件最多把用户带到 70 分（1 档），到不了 2/3 档 */
    static final int FILE_BONUS_CAP = 10;
    static final int DEDUCT_PER_UPHELD = 20;
    static final int DEDUCT_PER_BAN = 40;
    /** 受信(敏感词直发)的分数线,恰为基准分:扣分即掉线,产出可补回 */
    static final int TRUST_SCORE_MIN = 60;
    /** 配额分档线:80/100 → 圈子与集市在投 1/2/3（与文件档位同一条线） */
    static final int TIER2_SCORE = FileLimits.TIER2_MIN_SCORE;
    static final int TIER3_SCORE = FileLimits.TIER3_MIN_SCORE;

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
        int files = fileAssetMapper.countApprovedSince(userId, since);
        int fileBonus = Math.min(FILE_BONUS_CAP, files);
        int bonus = Math.min(BONUS_CAP, posts + mkts + fileBonus);
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
        // 发帖日额与 guardDailyQuota 同源：按信誉档位，不看认证状态（2026-10-28）
        vo.setPostDailyQuota(switch (quotaTier(score)) {
            case 3 -> postDailyTier3;
            case 2 -> postDailyTier2;
            default -> postDailyTier1;
        });
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
        if (u == null) {
            return postDailyTier1;
        }
        return switch (quotaTier(scoreOf(userId, u))) {
            case 3 -> postDailyTier3;
            case 2 -> postDailyTier2;
            default -> postDailyTier1;
        };
    }

    private int scoreOf(long userId, User u) {
        LocalDateTime since = LocalDateTime.now().minusDays(creditDecayDays);
        int fileBonus = Math.min(FILE_BONUS_CAP, fileAssetMapper.countApprovedSince(userId, since));
        int bonus = Math.min(BONUS_CAP,
                postMapper.countApprovedByAuthorSince(userId, since)
                        + mktActivityMapper.countByUserSince(userId, since)
                        + fileBonus);
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
