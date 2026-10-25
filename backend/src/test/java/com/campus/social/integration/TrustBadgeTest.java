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
 * 信誉分制回归(2026-10-25,配 ReviewTrustService 分数化)。
 *
 * <p>分数 = 100 − 成立举报×20 − 封禁×40(近180天审计),受信要求 ≥60。
 * 播种五档用户,断言分数与受信联动;他人徽章(trust-badge)与清单同源,不重复断言。
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
    private Long threeUpheldUserId;
    private Long bannedUserId;
    private Long unverifiedUserId;

    @BeforeEach
    void seed() {
        cleanUserId = insertUser("trust-clean-" + SUFFIX, 1);
        oneUpheldUserId = insertUser("trust-one-" + SUFFIX, 1);
        threeUpheldUserId = insertUser("trust-three-" + SUFFIX, 1);
        bannedUserId = insertUser("trust-banned-" + SUFFIX, 1);
        unverifiedUserId = insertUser("trust-unver-" + SUFFIX, 0);
        for (Long id : new Long[]{cleanUserId, oneUpheldUserId, threeUpheldUserId,
                bannedUserId, unverifiedUserId}) {
            jdbc.update("UPDATE user SET create_time = DATE_SUB(NOW(), INTERVAL 30 DAY) WHERE id = ?", id);
        }
        insertUpheld(oneUpheldUserId, "T1");
        insertUpheld(threeUpheldUserId, "T2a");
        insertUpheld(threeUpheldUserId, "T2b");
        insertUpheld(threeUpheldUserId, "T2c");
        jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                + "VALUES (1, 'account_ban', 'user', ?, ?)", bannedUserId, "T3" + SUFFIX);
    }

    private void insertUpheld(Long target, String tag) {
        jdbc.update("INSERT INTO audit_log (user_id, action, target_type, target_id, detail) "
                + "VALUES (1, 'report_upheld', 'user', ?, ?)", target, tag + SUFFIX);
    }

    private Long insertUser(String username, int verified) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,1,0)",
                username, "x", username, verified);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        for (Long id : new Long[]{cleanUserId, oneUpheldUserId, threeUpheldUserId,
                bannedUserId, unverifiedUserId}) {
            if (id != null) {
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
    }

    @Test
    void creditScoreDeductsAndGatesTrust() {
        // 干净老号:满分受信
        UserTrustStatusVO clean = trustService.status(cleanUserId);
        assertTrue(clean.getTrusted());
        assertEquals(100, clean.getCreditScore());

        // 单条成立:100-20=80 ≥60,仍受信(从「一票永否」改为扣分制)
        UserTrustStatusVO one = trustService.status(oneUpheldUserId);
        assertEquals(80, one.getCreditScore());
        assertTrue(one.getTrusted(), "80 分仍应受信");
        assertTrue(one.getHasBadRecord());

        // 三条成立:100-60=40 <60,不受信
        UserTrustStatusVO three = trustService.status(threeUpheldUserId);
        assertEquals(40, three.getCreditScore());
        assertFalse(three.getTrusted(), "40 分不应受信");

        // 封禁史:100-40=60,踩线仍受信(≥60)
        UserTrustStatusVO banned = trustService.status(bannedUserId);
        assertEquals(60, banned.getCreditScore());
        assertTrue(banned.getTrusted(), "60 分踩线应受信");

        // 未认证:分数再高也不受信
        UserTrustStatusVO unverified = trustService.status(unverifiedUserId);
        assertEquals(100, unverified.getCreditScore());
        assertFalse(unverified.getTrusted());
    }
}
