package com.campus.social.integration;

import com.campus.social.controller.UserController;
import com.campus.social.vo.TrustBadgeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开信誉徽章回归(2026-10-25,GET /user/{id}/trust-badge)。
 *
 * <p>判定与信任闸门同源(ReviewTrustService.status),本测试沿它的四条件播种:
 * 已认证+老号+无记录 → true;有「成立」的用户举报 → false;未认证 → false。
 * 自清理自播种(CI 只加载 schema)。
 */
@SpringBootTest
@ActiveProfiles("local")
class TrustBadgeTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private UserController userController;
    @Autowired
    private JdbcTemplate jdbc;

    private Long cleanUserId;
    private Long reportedUserId;
    private Long unverifiedUserId;
    private Long reporterId;

    @BeforeEach
    void seed() {
        // 三个老号(注册 30 天):干净受信 / 有成立举报 / 未认证
        cleanUserId = insertUser("trust-clean-" + SUFFIX, 1);
        reportedUserId = insertUser("trust-reported-" + SUFFIX, 1);
        unverifiedUserId = insertUser("trust-unver-" + SUFFIX, 0);
        reporterId = insertUser("trust-reporter-" + SUFFIX, 1);
        for (Long id : new Long[]{cleanUserId, reportedUserId, unverifiedUserId, reporterId}) {
            jdbc.update("UPDATE user SET create_time = DATE_SUB(NOW(), INTERVAL 30 DAY) WHERE id = ?", id);
        }
        // 举报成立:target_type=1(用户),target_id=被举报人,status=1
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, status, handler_id) "
                + "VALUES (?, 1, ?, 1, 1, 1)", reporterId, reportedUserId);
    }

    private Long insertUser(String username, int verified) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,1,0)",
                username, "x", username, verified);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM report WHERE reporter_id = ?", reporterId);
        for (Long id : new Long[]{cleanUserId, reportedUserId, unverifiedUserId, reporterId}) {
            if (id != null) {
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
    }

    @Test
    void badgeReflectsTrustConditions() {
        assertTrue(userController.trustBadge(cleanUserId).getData().getTrusted(),
                "已认证+注册满+无记录应受信");
        assertFalse(userController.trustBadge(reportedUserId).getData().getTrusted(),
                "有成立的用户举报不应受信");
        assertFalse(userController.trustBadge(unverifiedUserId).getData().getTrusted(),
                "未认证不应受信");
        // 不存在的用户:判定降级为 false 而不是 404(徽章只是个布尔,页面已由用户卡兜 404)
        assertFalse(userController.trustBadge(-1L).getData().getTrusted(),
                "不存在的用户按不受信处理");
    }
}
