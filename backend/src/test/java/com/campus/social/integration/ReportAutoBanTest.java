package com.campus.social.integration;

import com.campus.social.entity.Report;
import com.campus.social.entity.User;
import com.campus.social.mapper.UserMapper;
import com.campus.social.service.impl.ReportAutoBanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集中举报自动封禁回归(2026-10-25,配 ReportAutoBanService)。
 *
 * <p>断言三道护栏:独立已认证举报人计数(未认证不算/同一人去重)、
 * 跨内容归因合并(举报其帖子+本人+评论算同一目标)、幂等(已封不重复)。
 * 直接调 check(内存构造 Report 线索),submit 链路的事务后触发由
 * scheduleCheckAfterCommit 保证,不在本测试范围。
 */
@SpringBootTest
@ActiveProfiles("local")
class ReportAutoBanTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime() % 100000);

    @Autowired
    private ReportAutoBanService autoBanService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long targetId;
    private Long postId;
    private Long commentId;
    private Long r1;
    private Long r2;
    private Long r3;
    private Long rUnverified;

    @BeforeEach
    void seed() {
        targetId = insertUser("ab-target-" + SUFFIX, 1);
        r1 = insertUser("ab-r1-" + SUFFIX, 1);
        r2 = insertUser("ab-r2-" + SUFFIX, 1);
        r3 = insertUser("ab-r3-" + SUFFIX, 1);
        rUnverified = insertUser("ab-runver-" + SUFFIX, 0);
        // 目标名下的一条帖子和一条评论(跨内容归因用)
        jdbc.update("INSERT INTO post (author_id, content, status, is_deleted) VALUES (?, 'ab', 1, 0)", targetId);
        postId = jdbc.queryForObject("SELECT id FROM post WHERE author_id = ? AND content = 'ab' ORDER BY id DESC LIMIT 1", Long.class, targetId);
        jdbc.update("INSERT INTO comment (post_id, user_id, content, is_deleted) VALUES (?, ?, 'ab', 0)", postId, targetId);
        commentId = jdbc.queryForObject("SELECT id FROM comment WHERE user_id = ? AND content = 'ab' ORDER BY id DESC LIMIT 1", Long.class, targetId);
    }

    private Long insertUser(String username, int verified) {
        jdbc.update("INSERT INTO user (username, password_hash, nickname, status, role, school_id, "
                        + "verify_status, stranger_msg_enabled, is_deleted) VALUES (?,?,?,0,0,1,?,1,0)",
                username, "x", username, verified);
        return jdbc.queryForObject("SELECT id FROM user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void cleanup() {
        for (Long id : new Long[]{targetId, r1, r2, r3, rUnverified}) {
            if (id != null) {
                jdbc.update("DELETE FROM report WHERE reporter_id = ? OR (target_type = 1 AND target_id = ?)", id, id);
                jdbc.update("DELETE FROM audit_log WHERE target_type = 'user' AND target_id = ?", id);
                jdbc.update("DELETE FROM user WHERE id = ?", id);
            }
        }
        if (postId != null) {
            jdbc.update("DELETE FROM report WHERE target_type = 2 AND target_id = ?", postId);
            jdbc.update("DELETE FROM comment WHERE id = ?", commentId);
            jdbc.update("DELETE FROM post WHERE id = ?", postId);
        }
        if (commentId != null) {
            jdbc.update("DELETE FROM report WHERE target_type = 3 AND target_id = ?", commentId);
        }
    }

    private void insertReport(long reporter, int targetType, long targetId) {
        jdbc.update("INSERT INTO report (reporter_id, target_type, target_id, reason_type, status, is_deleted) "
                + "VALUES (?,?,?,1,0,0)", reporter, targetType, targetId);
    }

    private Report reportOnUser() {
        Report r = new Report();
        r.setReporterId(r1);
        r.setTargetType(1);
        r.setTargetId(targetId);
        return r;
    }

    private User reload(Long id) {
        return userMapper.selectById(id);
    }

    @Test
    void belowThresholdAndUnverifiedDoNotTrigger() {
        // 2 个认证举报人 + 1 个未认证:有效计数 2 < 3,不封
        insertReport(r1, 1, targetId);
        insertReport(r2, 2, postId);
        insertReport(rUnverified, 1, targetId);
        autoBanService.check(reportOnUser());
        assertEquals(0, reload(targetId).getStatus(), "计数不足阈值不应封禁");
    }

    @Test
    void threeVerifiedReportersAcrossTargetsAutoBan() {
        // 三个认证举报人,分别举报其本人/帖子/评论——归因合并后同一目标
        insertReport(r1, 1, targetId);
        insertReport(r2, 2, postId);
        insertReport(r3, 3, commentId);
        autoBanService.check(reportOnUser());

        User target = reload(targetId);
        assertEquals(1, target.getStatus(), "3 名认证举报人跨内容举报应自动封禁");
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'auto_ban_reports' AND target_id = ?",
                Integer.class, targetId);
        assertTrue(audits != null && audits >= 1, "自动封禁应落审计");

        // 幂等:已封状态下再检查不重复封/不重复审计
        autoBanService.check(reportOnUser());
        Integer audits2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'auto_ban_reports' AND target_id = ?",
                Integer.class, targetId);
        assertTrue(audits2 != null && audits2 == 1, "已封禁后重复检查不应再落审计");
    }
}
