package com.campus.social.integration;

import com.campus.social.service.impl.ReviewTrustService;
import com.campus.social.vo.UserTrustStatusVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信誉分 v2 回归(2026-10-25,基准 60 + 产出加分 + 配额分档)。
 *
 * <p>分数 = 60 + min(过审帖+集市+文件, 40) − 成立举报×20 − 封禁×40,受信线 60=基准。
 * 配额:认证后按分数 60/80/100 → 圈子与集市 1/2/3,帖子 3/30。
 * 自清理自播种(CI 只加载 schema)。
 */
@SpringBootTest
@ActiveProfiles("local")
class TrustBadgeTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private ReviewTrustService trustService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long cleanUserId;
    private Long oneUpheldUserId;
    private Long bannedUserId;
    private Long unverifiedUserId;
    private Long producerUserId;

    @BeforeEach
    void seed() {
        cleanUserId = insertUser("trust-clean-" + SUFFIX, 1);
        oneUpheldUserId = insertUser("trust-one-" + SUFFIX, 1);
        bannedUserId = insertUser("trust-banned-" + SUFFIX, 1);
        unverifiedUserId = insertUser("trust-unver-" + SUFFIX, 0);
        producerUserId = insertUser("trust-prod-" + SUFFIX, 1);
        for (Long id : new Long[]{cleanUserId, oneUpheldUserId, bannedUserId,
                unverifiedUserId, producerUserId}) {
            jdbc.update("UPDATE user SET create_time = DATE_SUB(NOW(), INTERVAL 30 DAY) WHERE id = ?", id);
        }
        jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                + "VALUES (1, 'report_upheld', 'user', ?, ?)", oneUpheldUserId, "T1" + SUFFIX);
        jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                + "VALUES (1, 'account_ban', 'user', ?, ?)", bannedUserId, "T3" + SUFFIX);
        // 产出型用户:20 条过审帖 → +20 分 → 80 分(80 档,配额 2)
        for (int i = 0; i < 20; i++) {
            jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?, 'prod', 1, 0)",
                    producerUserId);
        }
    }

    private Long insertUser(String username, int verified) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,1,0)",
                username, "x", username, verified);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        for (Long id : new Long[]{cleanUserId, oneUpheldUserId, bannedUserId,
                unverifiedUserId, producerUserId}) {
            if (id != null) {
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
                jdbc.update("DELETE FROM post WHERE author_id = ?", id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
    }

    @Test
    void creditScoreV2BaseBonusDeductionAndQuotas() {
        // 干净认证老号:基准 60,即受信(线=基准),配额一档
        UserTrustStatusVO clean = trustService.status(cleanUserId);
        assertTrue(clean.getTrusted(), "基准 60=受信线,干净认证老号应受信");
        assertEquals(60, clean.getCreditScore());
        assertEquals(1, clean.getCircleQuota());
        assertEquals(1, clean.getMktQuota());
        // 2026-10-28:发帖日额改成信誉档位(60 分 5 / 80 分 10 / 100 分 15)
        assertEquals(5, clean.getPostDailyQuota());

        // 单条成立举报:60-20=40,掉线;产出可补回
        UserTrustStatusVO one = trustService.status(oneUpheldUserId);
        assertEquals(40, one.getCreditScore());
        assertFalse(one.getTrusted());

        // 封禁史:60-40=20,掉线
        UserTrustStatusVO banned = trustService.status(bannedUserId);
        assertEquals(20, banned.getCreditScore());
        assertFalse(banned.getTrusted());

        // 产出加分:20 条过审帖 +20 → 80,80 档配额 2
        UserTrustStatusVO producer = trustService.status(producerUserId);
        assertEquals(80, producer.getCreditScore());
        assertEquals(20, producer.getCreditBonus());
        assertEquals(2, producer.getCircleQuota());
        assertEquals(2, producer.getMktQuota());
        assertEquals(10, producer.getPostDailyQuota(), "80 分对应 10 条/日");

        // 未认证:建圈/集市锁死为 0;发帖按信誉档位,基准 60 → 1 档 5 条
        UserTrustStatusVO unverified = trustService.status(unverifiedUserId);
        assertEquals(60, unverified.getCreditScore());
        assertFalse(unverified.getTrusted());
        assertEquals(0, unverified.getCircleQuota());
        assertEquals(0, unverified.getMktQuota());
        assertEquals(5, unverified.getPostDailyQuota());
    }
}
