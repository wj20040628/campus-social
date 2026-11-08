package com.campus.social.integration;

import com.campus.social.service.CancelledAccountRetentionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 已注销账号留存清理（2026-11-08）。
 *
 * <p>清理范围只含私信/会话偏好/通知；帖子、评论、举报、审计日志不在此任务里，
 * 这是与隐私政策对齐的口径，测试用「存在且不被删」来固定它。
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class CancelledAccountRetentionTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private CancelledAccountRetentionService retentionService;
    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;
    private Long peerId;

    @BeforeEach
    void seed() {
        userId = insertUser("cacc-gone-" + SUFFIX, 2);
        peerId = insertUser("cacc-peer-" + SUFFIX, 0);
        jdbc.update("INSERT INTO notify (user_id, type, target_id, is_read, is_deleted) VALUES (?,6,0,0,0)", userId);
        jdbc.update("INSERT INTO chat_message (conv_id, sender_id, receiver_id, content, is_deleted) "
                + "VALUES (1,?,?,?,0)", userId, peerId, "留存测试消息");
        jdbc.update("INSERT INTO chat_conv_pref (user_id, kind, target_id) VALUES (?,'dm',?)", userId, peerId);
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?,?,1,0)",
                userId, "注销用户的历史帖-" + SUFFIX);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM notify WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM chat_message WHERE sender_id = ? OR receiver_id = ?", userId, userId);
        jdbc.update("DELETE FROM chat_conv_pref WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM post WHERE author_id = ?", userId);
        jdbc.update("DELETE FROM user WHERE id IN (?,?)", userId, peerId);
    }

    @Test
    void purgeRemovesMessagesAndNotificationsButKeepsContent() {
        retentionService.purgeUser(userId);

        assertEquals(0, count("SELECT COUNT(*) FROM notify WHERE user_id = ?", userId));
        assertEquals(0, count("SELECT COUNT(*) FROM chat_message WHERE sender_id = ? OR receiver_id = ?",
                userId, userId));
        assertEquals(0, count("SELECT COUNT(*) FROM chat_conv_pref WHERE user_id = ?", userId));
        assertEquals(1, count("SELECT COUNT(*) FROM post WHERE author_id = ?", userId),
                "帖子属于内容留痕，不在留存清理范围");

        LocalDateTime purgedAt = jdbc.queryForObject(
                "SELECT purged_at FROM user WHERE id = ?", LocalDateTime.class, userId);
        assertNotNull(purgedAt, "清理完成必须写 purged_at，保证任务幂等");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private Long insertUser(String username, int status) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,?,0,1,0,1,0)",
                username, "x", username, status);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }
}
